package dev.connectplus.geyserbridge.session;

import dev.connectplus.geyserbridge.protocol.BridgeProtocol;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次真实基岩连接的桥接档案。协议第 3 节：
 * bridgeSessionId 是扩展为每个真实连接生成的 UUID，同 XUID 重连会得到新的 id；
 * 绑定建立后不得转移到另一个 Channel；connectionId 出现（非空）表示该会话已被
 * ConnectPlus RESOLVE 过，即"由 ConnectPlus 管理"。
 * session 字段只保存 Geyser 内部会话对象的引用，用于同一性判断与透传，不序列化。
 */
public final class BridgeSession {

    private final String bridgeSessionId;
    private final String providerEpoch;
    private final Object session;
    private final long registeredAtNanos = System.nanoTime();

    private volatile String xuid;
    private volatile String bedrockUsername;
    private volatile String clientVersion;
    private volatile String locale;
    private volatile Object boundChannel;
    private volatile String connectionId;
    private volatile boolean loginSeen;
    private volatile boolean closed;

    public BridgeSession(String providerEpoch, Object session) {
        this.bridgeSessionId = UUID.randomUUID().toString();
        this.providerEpoch = Objects.requireNonNull(providerEpoch, "providerEpoch");
        this.session = Objects.requireNonNull(session, "session");
    }

    public String bridgeSessionId() {
        return bridgeSessionId;
    }

    public String providerEpoch() {
        return providerEpoch;
    }

    public Object session() {
        return session;
    }

    public long registeredAtNanos() {
        return registeredAtNanos;
    }

    public String xuid() {
        return xuid;
    }

    public void xuid(String xuid) {
        this.xuid = xuid;
    }

    public String bedrockUsername() {
        return bedrockUsername;
    }

    public void bedrockUsername(String bedrockUsername) {
        this.bedrockUsername = bedrockUsername;
    }

    public String clientVersion() {
        return clientVersion;
    }

    public void clientVersion(String clientVersion) {
        this.clientVersion = clientVersion;
    }

    public String locale() {
        return locale;
    }

    public void locale(String locale) {
        this.locale = locale;
    }

    /**
     * 第一次成功的 RESOLVE 决定绑定对象；此后拒绝转移到其他 Channel（协议 5）。
     *
     * @return true 表示本次绑定成功（或绑定本来就是同一对象）。
     */
    public boolean bindChannel(Object channel) {
        Objects.requireNonNull(channel, "channel");
        Object current = boundChannel;
        if (current == null) {
            synchronized (this) {
                current = boundChannel;
                if (current == null) {
                    boundChannel = channel;
                    return true;
                }
            }
        }
        return current == channel;
    }

    public Object boundChannel() {
        return boundChannel;
    }

    public String connectionId() {
        return connectionId;
    }

    /** @return true 表示第一次赋值成功；重复 RESOLVE 携带相同 connectionId 也返回 true。 */
    public boolean setConnectionIdIfAbsent(String connectionId) {
        Objects.requireNonNull(connectionId, "connectionId");
        String current = this.connectionId;
        if (current == null) {
            synchronized (this) {
                current = this.connectionId;
                if (current == null) {
                    this.connectionId = connectionId;
                    return true;
                }
            }
        }
        return current.equals(connectionId);
    }

    /** @return true 当该会话已被 ConnectPlus 管理（RESOLVE 成功过）。 */
    public boolean isManaged() {
        return connectionId != null;
    }

    public boolean isLoginSeen() {
        return loginSeen;
    }

    public void markLoginSeen() {
        this.loginSeen = true;
    }

    public boolean isClosed() {
        return closed;
    }

    public void markClosed() {
        this.closed = true;
    }

    /** 诊断用脱敏表示：不含完整 XUID（协议 6 交付要求）。 */
    @Override
    public String toString() {
        String x = xuid;
        return "BridgeSession{id=" + bridgeSessionId
                + ", xuid=" + (x == null ? "unknown" : "<len " + x.length() + ">")
                + ", managed=" + isManaged()
                + ", epoch=" + BridgeProtocol.PROTOCOL_VERSION + "}@" + Integer.toHexString(System.identityHashCode(session));
    }
}
