package dev.connectplus.geyserbridge.handler;

import dev.connectplus.geyserbridge.protocol.BridgeMaps;
import dev.connectplus.geyserbridge.protocol.BridgeProtocol;
import dev.connectplus.geyserbridge.protocol.BridgeMaps.BridgeRequestException;
import dev.connectplus.geyserbridge.protocol.Xuid;
import dev.connectplus.geyserbridge.session.BridgeSession;
import dev.connectplus.geyserbridge.session.BridgeSessionIndex;
import dev.connectplus.geyserbridge.util.AddressUtil;
import io.netty.channel.Channel;

import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * ConnectPlus → 扩展的 op 分发（协议 4.2 RESOLVE / VALIDATE / DISCONNECT）。
 *
 * apply() 永不阻塞调用方线程：RESOLVE 的下游匹配轮询在桥接调度器上进行，
 * 全部响应以 CompletionStage 返回。响应始终回传 protocolVersion=1 与当前
 * providerEpoch，并原样回传请求中的 connectionId / bridgeSessionId。
 */
public final class BridgeRequestHandler implements Function<Map<String, Object>, CompletionStage<Map<String, Object>>> {

    /** RESOLVE 轮询预算：必须在 ConnectPlus 侧 3 秒超时之内收敛。 */
    static final long RESOLVE_DEADLINE_MS = 2_500;
    static final long RESOLVE_POLL_INTERVAL_MS = 50;

    public interface Environment {
        /** 当前 providerEpoch；桥接未注册时返回 null。 */
        String currentEpoch();

        ScheduledExecutorService scheduler();

        SessionAccess access();

        BridgeSessionIndex index();

        Consumer<String> logger();

        /** RESOLVE 单轮等待与总预算；默认值满足 ConnectPlus 3 秒超时之内收敛。 */
        default long resolvePollIntervalMs() {
            return RESOLVE_POLL_INTERVAL_MS;
        }

        default long resolveDeadlineMs() {
            return RESOLVE_DEADLINE_MS;
        }
    }

    private final Environment env;

    public BridgeRequestHandler(Environment env) {
        this.env = Objects.requireNonNull(env);
    }

    @Override
    public CompletionStage<Map<String, Object>> apply(Map<String, Object> request) {
        CompletableFuture<Map<String, Object>> response = new CompletableFuture<>();
        try {
            Map<String, Object> req = BridgeMaps.requireMap(request, "request");
            String op = BridgeMaps.requireString(req, BridgeProtocol.F_OP);
            int protocolVersion = BridgeMaps.requireInt(req, BridgeProtocol.F_PROTOCOL_VERSION);
            String requestEpoch = BridgeMaps.requireString(req, BridgeProtocol.F_PROVIDER_EPOCH);
            String currentEpoch = env.currentEpoch();

            if (protocolVersion != BridgeProtocol.PROTOCOL_VERSION) {
                response.complete(reject(op, request, BridgeProtocol.S_REJECTED,
                        BridgeProtocol.R_UNSUPPORTED_PROTOCOL));
                return response;
            }
            if (currentEpoch == null || !currentEpoch.equals(requestEpoch)) {
                // 注册已被换代或停用：迟到的请求不得影响当前状态
                response.complete(reject(op, req, BridgeProtocol.S_UNAVAILABLE,
                        BridgeProtocol.R_STALE_EPOCH));
                return response;
            }

            switch (op) {
                case BridgeProtocol.OP_RESOLVE -> env.scheduler().schedule(
                        () -> resolveLoop(req, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(env.resolveDeadlineMs()), response),
                        0, TimeUnit.MILLISECONDS);
                case BridgeProtocol.OP_VALIDATE -> response.complete(validate(req));
                case BridgeProtocol.OP_DISCONNECT -> {
                    CompletionStage<Map<String, Object>> stage = disconnect(req);
                    stage.whenComplete((result, error) -> {
                        if (error != null) {
                            response.complete(reject(BridgeProtocol.OP_DISCONNECT, req,
                                    BridgeProtocol.S_UNAVAILABLE, BridgeProtocol.R_BRIDGE_UNAVAILABLE));
                        } else {
                            response.complete(result);
                        }
                    });
                }
                default -> response.complete(reject(op, req, BridgeProtocol.S_REJECTED,
                        BridgeProtocol.R_INVALID_REQUEST));
            }
        } catch (BridgeRequestException e) {
            response.complete(Map.of(
                    BridgeProtocol.F_STATUS, BridgeProtocol.S_REJECTED,
                    BridgeProtocol.F_PROTOCOL_VERSION, BridgeProtocol.PROTOCOL_VERSION,
                    BridgeProtocol.F_REASON_CODE, BridgeProtocol.R_INVALID_REQUEST));
        } catch (RuntimeException e) {
            env.logger().accept("handler error: " + e);
            response.complete(Map.of(
                    BridgeProtocol.F_STATUS, BridgeProtocol.S_UNAVAILABLE,
                    BridgeProtocol.F_PROTOCOL_VERSION, BridgeProtocol.PROTOCOL_VERSION));
        }
        return response;
    }

    // ---- RESOLVE ----

    private void resolveLoop(Map<String, Object> request, long deadlineNanos, CompletableFuture<Map<String, Object>> response) {
        if (response.isDone()) {
            return;
        }
        Map<String, Object> result = resolveAttempt(request);
        if (result != null) {
            response.complete(result);
            return;
        }
        if (System.nanoTime() >= deadlineNanos) {
            // 超时仍未匹配：NO_MATCH 只表示没匹配到基岩会话，不表示是 Java 玩家
            response.complete(reject(BridgeProtocol.OP_RESOLVE, request, BridgeProtocol.S_NO_MATCH, null));
            return;
        }
        env.scheduler().schedule(
                () -> resolveLoop(request, deadlineNanos, response),
                env.resolvePollIntervalMs(), TimeUnit.MILLISECONDS);
    }

    /** @return null = 尚无结论（继续轮询）；否则为最终响应。 */
    private Map<String, Object> resolveAttempt(Map<String, Object> request) {
        try {
            Object channelObj = BridgeMaps.requireValue(request, BridgeProtocol.F_CHANNEL);
            if (!(channelObj instanceof Channel channel)) {
                return reject(BridgeProtocol.OP_RESOLVE, request, BridgeProtocol.S_REJECTED,
                        BridgeProtocol.R_INVALID_REQUEST);
            }
            Object rawLocalObj = BridgeMaps.requireValue(request, BridgeProtocol.F_RAW_LOCAL_ADDRESS);
            Object rawRemoteObj = BridgeMaps.requireValue(request, BridgeProtocol.F_RAW_REMOTE_ADDRESS);
            if (!(rawLocalObj instanceof SocketAddress rawLocal)
                    || !(rawRemoteObj instanceof SocketAddress rawRemote)) {
                return reject(BridgeProtocol.OP_RESOLVE, request, BridgeProtocol.S_REJECTED,
                        BridgeProtocol.R_INVALID_REQUEST);
            }
            String connectionId = BridgeMaps.requireString(request, BridgeProtocol.F_CONNECTION_ID);

            // 协议 4.2：请求若带了 bridgeSessionId，必须与本会话档案一致
            String requestedSessionId = BridgeMaps.optionalString(request, BridgeProtocol.F_BRIDGE_SESSION_ID);
            SessionAccess access = env.access();
            Object session = access.matchC2p(rawLocal, rawRemote);
            if (session == null) {
                return null; // 继续等待下游连接建立
            }
            BridgeSessionIndex index = env.index();
            BridgeSession bridge = index.bySession(session);
            if (bridge == null) {
                // 会话早于本桥接启用（扩展中途启动）：身份从未被我们观测过
                return reject(BridgeProtocol.OP_RESOLVE, request, BridgeProtocol.S_REJECTED,
                        "UNTRACKED_SESSION");
            }
            if (requestedSessionId != null && !requestedSessionId.equals(bridge.bridgeSessionId())) {
                return reject(BridgeProtocol.OP_RESOLVE, request, BridgeProtocol.S_REJECTED,
                        "SESSION_ID_MISMATCH");
            }
            if (access.isClosed(session)) {
                return null; // 会话正在消亡，等下一轮
            }
            if (!access.identityTrusted(session)) {
                // 已识别到会话，但认证不可信：负面响应不得携带身份快照
                return reject(BridgeProtocol.OP_RESOLVE, request, BridgeProtocol.S_REJECTED,
                        "IDENTITY_UNTRUSTED");
            }
            String xuid = access.xuid(session);
            if (!Xuid.isValid(xuid)) {
                return reject(BridgeProtocol.OP_RESOLVE, request, BridgeProtocol.S_REJECTED,
                        BridgeProtocol.R_INVALID_IDENTITY);
            }
            if (!channel.isActive() || !access.downstreamAlive(session)) {
                return null; // 生命周期检查失败，等下一轮
            }
            if (!bridge.bindChannel(channel)) {
                // 绑定已属于另一个 Channel，绝不转移
                return reject(BridgeProtocol.OP_RESOLVE, request, BridgeProtocol.S_REJECTED,
                        "CHANNEL_CONFLICT");
            }
            if (!bridge.setConnectionIdIfAbsent(connectionId)) {
                return reject(BridgeProtocol.OP_RESOLVE, request, BridgeProtocol.S_REJECTED,
                        "CONNECTION_ID_CONFLICT");
            }
            String wireUuid = BridgeMaps.optionalString(request, BridgeProtocol.F_WIRE_UUID);
            if (wireUuid != null) {
                Object javaUuid = access.javaUuid(session);
                if (javaUuid != null && !wireUuid.equalsIgnoreCase(String.valueOf(javaUuid))) {
                    return reject(BridgeProtocol.OP_RESOLVE, request, BridgeProtocol.S_REJECTED,
                            "WIRE_MISMATCH");
                }
            }

            index.bindXuid(bridge, xuid);
            bridge.xuid(xuid);
            bridge.bedrockUsername(String.valueOf(access.bedrockUsername(session)));
            bridge.clientVersion(access.clientVersion(session));
            bridge.locale(access.locale(session));
            Map<String, Object> out = baseResponse(BridgeProtocol.OP_RESOLVE, BridgeProtocol.S_VERIFIED, request);
            out.put(BridgeProtocol.F_BRIDGE_SESSION_ID, bridge.bridgeSessionId()); // RESOLVE 成功时新增
            out.put(BridgeProtocol.F_XUID, xuid);
            out.put(BridgeProtocol.F_BEDROCK_USERNAME, String.valueOf(access.bedrockUsername(session)));
            String clientVersion = access.clientVersion(session);
            if (clientVersion != null) {
                out.put(BridgeProtocol.F_CLIENT_VERSION, clientVersion);
            }
            String locale = access.locale(session);
            if (locale != null) {
                out.put(BridgeProtocol.F_LOCALE, locale);
            }
            return out;
        } catch (BridgeRequestException e) {
            return reject(BridgeProtocol.OP_RESOLVE, request, BridgeProtocol.S_REJECTED,
                    BridgeProtocol.R_INVALID_REQUEST);
        } catch (RuntimeException e) {
            env.logger().accept("resolve attempt failed: " + e);
            return reject(BridgeProtocol.OP_RESOLVE, request, BridgeProtocol.S_UNAVAILABLE,
                    BridgeProtocol.R_BRIDGE_UNAVAILABLE);
        }
    }

    // ---- VALIDATE ----

    private Map<String, Object> validate(Map<String, Object> request) {
        try {
            String connectionId = BridgeMaps.requireString(request, BridgeProtocol.F_CONNECTION_ID);
            String bridgeSessionId = BridgeMaps.requireString(request, BridgeProtocol.F_BRIDGE_SESSION_ID);
            Object channelObj = BridgeMaps.requireValue(request, BridgeProtocol.F_CHANNEL);
            if (!(channelObj instanceof Channel channel)) {
                return reject(BridgeProtocol.OP_VALIDATE, request, BridgeProtocol.S_REJECTED,
                        BridgeProtocol.R_INVALID_REQUEST);
            }
            BridgeSession bridge = env.index().byId(bridgeSessionId);
            if (bridge == null) {
                return reject(BridgeProtocol.OP_VALIDATE, request, BridgeProtocol.S_INVALID, null);
            }
            if (!connectionId.equals(bridge.connectionId())) {
                return reject(BridgeProtocol.OP_VALIDATE, request, BridgeProtocol.S_INVALID, null);
            }
            SessionAccess access = env.access();
            Object session = bridge.session();
            boolean sameChannel = bridge.boundChannel() == channel && channel.isActive();
            if (!sameChannel || access.isClosed(session) || !access.downstreamAlive(session)) {
                return reject(BridgeProtocol.OP_VALIDATE, request, BridgeProtocol.S_INVALID, null);
            }
            Map<String, Object> out = baseResponse(BridgeProtocol.OP_VALIDATE, BridgeProtocol.S_VALID, request);
            out.put(BridgeProtocol.F_XUID, bridge.xuid());
            return out;
        } catch (BridgeRequestException e) {
            return reject(BridgeProtocol.OP_VALIDATE, request, BridgeProtocol.S_REJECTED,
                    BridgeProtocol.R_INVALID_REQUEST);
        } catch (RuntimeException e) {
            env.logger().accept("validate failed: " + e);
            return reject(BridgeProtocol.OP_VALIDATE, request, BridgeProtocol.S_UNAVAILABLE,
                    BridgeProtocol.R_BRIDGE_UNAVAILABLE);
        }
    }

    // ---- DISCONNECT ----

    private CompletionStage<Map<String, Object>> disconnect(Map<String, Object> request) {
        try {
            String connectionId = BridgeMaps.requireString(request, BridgeProtocol.F_CONNECTION_ID);
            String bridgeSessionId = BridgeMaps.requireString(request, BridgeProtocol.F_BRIDGE_SESSION_ID);
            String reasonCode = BridgeMaps.requireString(request, BridgeProtocol.F_REASON_CODE);
            String message = BridgeMaps.optionalString(request, BridgeProtocol.F_MESSAGE);
            if (!BridgeProtocol.R_REPLACED.equals(reasonCode)
                    && !BridgeProtocol.R_BRIDGE_UNAVAILABLE.equals(reasonCode)) {
                return completed(reject(BridgeProtocol.OP_DISCONNECT, request, BridgeProtocol.S_REJECTED,
                        BridgeProtocol.R_INVALID_REQUEST));
            }
            String effectiveMessage = message != null ? message
                    : (BridgeProtocol.R_REPLACED.equals(reasonCode) ? BridgeProtocol.REPLACED_MESSAGE : null);
            if (effectiveMessage == null) {
                return completed(reject(BridgeProtocol.OP_DISCONNECT, request, BridgeProtocol.S_REJECTED,
                        BridgeProtocol.R_INVALID_REQUEST));
            }

            BridgeSessionIndex index = env.index();
            BridgeSession bridge = index.byId(bridgeSessionId);
            if (bridge == null) {
                if (index.wasClosedOnce(bridgeSessionId)) {
                    // 指定的旧会话确实已关闭
                    return completed(reject(BridgeProtocol.OP_DISCONNECT, request, BridgeProtocol.S_ALREADY_CLOSED, null));
                }
                return completed(reject(BridgeProtocol.OP_DISCONNECT, request, BridgeProtocol.S_REJECTED,
                        BridgeProtocol.R_INVALID_REQUEST));
            }
            if (!connectionId.equals(bridge.connectionId())) {
                // 同 XUID 已换代：绝不关闭新会话
                return completed(reject(BridgeProtocol.OP_DISCONNECT, request, BridgeProtocol.S_STALE, null));
            }
            Object channelObj = request.get(BridgeProtocol.F_CHANNEL);
            if (channelObj instanceof Channel channel && bridge.boundChannel() != null
                    && bridge.boundChannel() != channel) {
                return completed(reject(BridgeProtocol.OP_DISCONNECT, request, BridgeProtocol.S_STALE, null));
            }
            Object session = bridge.session();
            SessionAccess access = env.access();
            if (access.isClosed(session)) {
                index.close(bridge);
                return completed(reject(BridgeProtocol.OP_DISCONNECT, request, BridgeProtocol.S_ALREADY_CLOSED, null));
            }
            CompletableFuture<Map<String, Object>> done = new CompletableFuture<>();
            access.disconnect(session, effectiveMessage, () -> {
                index.close(bridge);
                done.complete(baseResponse(BridgeProtocol.OP_DISCONNECT, BridgeProtocol.S_CLOSED, request));
            });
            return done;
        } catch (BridgeRequestException e) {
            return completed(reject(BridgeProtocol.OP_DISCONNECT, request, BridgeProtocol.S_REJECTED,
                    BridgeProtocol.R_INVALID_REQUEST));
        } catch (RuntimeException e) {
            env.logger().accept("disconnect failed: " + e);
            return completed(reject(BridgeProtocol.OP_DISCONNECT, request, BridgeProtocol.S_UNAVAILABLE,
                    BridgeProtocol.R_BRIDGE_UNAVAILABLE));
        }
    }

    private static CompletionStage<Map<String, Object>> completed(Map<String, Object> response) {
        return CompletableFuture.completedStage(response);
    }

    // ---- 响应构造 ----

    private Map<String, Object> baseResponse(String op, String status, Map<String, Object> request) {
        Map<String, Object> out = new HashMap<>();
        out.put(BridgeProtocol.F_OP, op);
        out.put(BridgeProtocol.F_STATUS, status);
        out.put(BridgeProtocol.F_PROTOCOL_VERSION, BridgeProtocol.PROTOCOL_VERSION);
        out.put(BridgeProtocol.F_PROVIDER_EPOCH, env.currentEpoch());
        BridgeMaps.echoIfPresent(request, out, BridgeProtocol.F_CONNECTION_ID);
        BridgeMaps.echoIfPresent(request, out, BridgeProtocol.F_BRIDGE_SESSION_ID);
        return out;
    }

    private Map<String, Object> reject(String op, Map<String, Object> request, String status, String reasonCode) {
        Map<String, Object> out = baseResponse(op, status, request);
        if (reasonCode != null) {
            out.put(BridgeProtocol.F_REASON_CODE, reasonCode);
        }
        return out;
    }

    /** 诊断辅助：地址描述供日志使用。 */
    public static String describeAddress(SocketAddress address) {
        return AddressUtil.describe(address);
    }
}
