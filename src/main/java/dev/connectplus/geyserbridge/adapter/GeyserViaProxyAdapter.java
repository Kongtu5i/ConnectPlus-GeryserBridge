package dev.connectplus.geyserbridge.adapter;

import dev.connectplus.geyserbridge.util.AddressUtil;
import dev.connectplus.geyserbridge.connectplus.ConnectPlusLocator;
import io.netty.channel.Channel;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.session.DownstreamSession;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.network.ClientSession;

import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Geyser/ViaProxy 内部 API 的唯一隔离点（协议第 2.1、5 节）。
 *
 * 匹配算法与 Geyser-ViaProxy 自身 IP 直通（GeyserViaProxyPlugin#onClient2ProxyChannelInitialize）
 * 采用同一谓词：下游 ClientSession 的本地地址 == c2p Channel 的远端地址（PRE 阶段原始值）。
 * 在此之上按文档要求追加约束：
 *  - 两端地址同时匹配（rawLocal ↔ 下游远端，rawRemote ↔ 下游本地）；
 *  - 仅接受下游连接存活中的会话；
 *  - 候选必须唯一，命中多个一律 NO_MATCH（快速重连/端口复用时宁可拒绝也不猜）。
 *
 * {@link #selfCheck()} 先检查 Geyser 最低版本、ViaProxy 版本系列、宿主及 JDK，再只读探测内部 API；不匹配时桥接停用，
 * 绝不允许猜一个匹配结果。
 */
public final class GeyserViaProxyAdapter {

    /** selfCheck 的结果；problems 非空 = 拒绝启用精确绑定。 */
    public record Health(boolean available, List<String> problems, String geyserVersion) {
    }

    private GeyserViaProxyAdapter() {
    }

    public static Health selfCheck() {
        List<String> problems = new ArrayList<>();
        GeyserImpl geyser = GeyserImpl.getInstance();
        if (geyser == null) {
            problems.add("GeyserImpl.getInstance() is null");
            return new Health(false, problems, "unknown");
        }
        String version = "unknown";
        try {
            // BuildData fields are compile-time constants. Reflection reads the host's
            // values instead of accidentally inlining this extension's compile dependency.
            Class<?> buildData = Class.forName("org.geysermc.geyser.BuildData");
            version = String.valueOf(buildData.getField("VERSION").get(null));
            problems.addAll(RuntimeCompatibility.problems(version, ConnectPlusLocator.viaProxyVersion(),
                    Runtime.version().feature(), geyser.platformType().platformName()));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            problems.add("cannot establish the host's Geyser version");
        }
        if (!problems.isEmpty()) {
            return new Health(false, List.copyOf(problems), version);
        }
        probe(problems, "org.geysermc.geyser.session.GeyserSession", "getDownstream");
        probe(problems, "org.geysermc.geyser.session.GeyserSession", "getAuthData");
        probe(problems, "org.geysermc.geyser.session.GeyserSession", "getUpstream");
        probe(problems, "org.geysermc.geyser.session.GeyserSession", "xuid");
        probe(problems, "org.geysermc.geyser.session.GeyserSession", "bedrockUsername");
        probe(problems, "org.geysermc.geyser.session.GeyserSession", "isClosed");
        probe(problems, "org.geysermc.geyser.session.GeyserSession", "disconnect", String.class);
        probe(problems, "org.geysermc.geyser.session.DownstreamSession", "getSession");
        probe(problems, "org.geysermc.mcprotocollib.network.Session", "getLocalAddress");
        probe(problems, "org.geysermc.mcprotocollib.network.Session", "getRemoteAddress");
        probe(problems, "org.geysermc.mcprotocollib.network.Session", "isConnected");
        probe(problems, "org.cloudburstmc.protocol.bedrock.BedrockSession", "getPeer");
        probe(problems, "org.cloudburstmc.protocol.bedrock.BedrockPeer", "getChannel");
        return new Health(problems.isEmpty(), problems, version);
    }

    private static void probe(List<String> problems, String className, String method, Class<?>... params) {
        try {
            Class<?> clazz = Class.forName(className);
            clazz.getMethod(method, params);
        } catch (ReflectiveOperationException | LinkageError e) {
            problems.add("missing " + className + "#" + method);
        }
    }

    /**
     * 把 ConnectPlus 在 PRE 阶段捕获的原始 c2p 地址关联到唯一活跃基岩会话。
     *
     * @return 命中的会话；null 表示 NO_MATCH（无匹配或多义，调用方不做区分以避免
     *         把多义性当成可继续使用的依据）。
     */
    public static GeyserSession matchC2p(SocketAddress rawLocal, SocketAddress rawRemote) {
        if (rawRemote == null) {
            return null;
        }
        List<GeyserSession> candidates = new ArrayList<>();
        for (GeyserSession session : GeyserImpl.getInstance().onlineConnections()) {
            if (session.isClosed()) {
                continue;
            }
            DownstreamSession downstream = session.getDownstream();
            if (downstream == null || downstream.isClosed()) {
                continue;
            }
            ClientSession clientSession = downstream.getSession();
            if (clientSession == null || !clientSession.isConnected()) {
                continue;
            }
            SocketAddress downstreamLocal = clientSession.getLocalAddress();
            SocketAddress downstreamRemote = clientSession.getRemoteAddress();
            if (!AddressUtil.matchesConnection(rawLocal, rawRemote, downstreamLocal, downstreamRemote)) {
                continue;
            }
            candidates.add(session);
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /**
     * 把断开操作调度到该会话的 bedrock 事件循环，避免跨线程操作协议库状态。
     */
    public static void disconnect(GeyserSession session, String message, Runnable after) {
        try {
            Channel channel = session.getUpstream().getSession().getPeer().getChannel();
            if (channel != null && channel.isActive()) {
                channel.eventLoop().execute(() -> {
                    try {
                        session.disconnect(message);
                    } finally {
                        if (after != null) {
                            after.run();
                        }
                    }
                });
                return;
            }
        } catch (RuntimeException ignored) {
            // 通道已不存在：会话多半已关闭，直接同步调用兜底
        }
        try {
            session.disconnect(message);
        } finally {
            if (after != null) {
                after.run();
            }
        }
    }

    /** 下游连接是否仍然存活（VALIDATE 用）。 */
    public static boolean downstreamAlive(GeyserSession session) {
        if (session.isClosed()) {
            return false;
        }
        DownstreamSession downstream = session.getDownstream();
        if (downstream == null || downstream.isClosed()) {
            return false;
        }
        ClientSession clientSession = downstream.getSession();
        return clientSession != null && clientSession.isConnected();
    }
}
