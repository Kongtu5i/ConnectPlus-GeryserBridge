package dev.connectplus.geyserbridge.protocol;

/**
 * ConnectPlus 身份桥接协议 v1 的字面常量。唯一事实来源是 docs/geyser-bridge-v1.md 第 3、4 节；
 * 修改任何字段名、状态值或 op 名称前必须先同步该文档。
 */
public final class BridgeProtocol {

    private BridgeProtocol() {
    }

    /** 协议版本，固定为 1。 */
    public static final int PROTOCOL_VERSION = 1;

    /** 提供者标识，固定值。 */
    public static final String PROVIDER_ID = "connectplus-geyser-bridge";

    // ---- 通用字段名 ----
    public static final String F_OP = "op";
    public static final String F_PROTOCOL_VERSION = "protocolVersion";
    public static final String F_PROVIDER_EPOCH = "providerEpoch";
    public static final String F_REGISTRATION_ID = "registrationId";
    public static final String F_STATUS = "status";
    public static final String F_REASON_CODE = "reasonCode";
    public static final String F_MESSAGE = "message";

    // ---- 注册 descriptor 字段 ----
    public static final String F_PROVIDER_ID = "providerId";
    public static final String F_BRIDGE_VERSION = "bridgeVersion";
    public static final String F_GEYSER_VERSION = "geyserVersion";
    public static final String F_VIAPROXY_VERSION = "viaproxyVersion";
    public static final String F_CAPABILITIES = "capabilities";

    // ---- 身份字段 ----
    public static final String F_CONNECTION_ID = "connectionId";
    public static final String F_BRIDGE_SESSION_ID = "bridgeSessionId";
    public static final String F_XUID = "xuid";
    public static final String F_BEDROCK_USERNAME = "bedrockUsername";
    public static final String F_CLIENT_VERSION = "clientVersion";
    public static final String F_LOCALE = "locale";

    // ---- 请求特有字段 ----
    public static final String F_CHANNEL = "channel";
    public static final String F_RAW_LOCAL_ADDRESS = "rawLocalAddress";
    public static final String F_RAW_REMOTE_ADDRESS = "rawRemoteAddress";
    public static final String F_WIRE_UUID = "wireUuid";
    public static final String F_WIRE_NAME = "wireName";
    public static final String F_OLD_BRIDGE_SESSION_ID = "oldBridgeSessionId";
    public static final String F_NEW_BRIDGE_SESSION_ID = "newBridgeSessionId";

    // ---- 注册结果 ----
    public static final String S_REGISTERED = "REGISTERED";
    public static final String S_REJECTED = "REJECTED";

    // ---- handler op 请求 ----
    public static final String OP_RESOLVE = "RESOLVE";
    public static final String OP_VALIDATE = "VALIDATE";
    public static final String OP_DISCONNECT = "DISCONNECT";

    // ---- handler op 响应状态 ----
    public static final String S_VERIFIED = "VERIFIED";
    public static final String S_NO_MATCH = "NO_MATCH";
    public static final String S_VALID = "VALID";
    public static final String S_INVALID = "INVALID";
    public static final String S_CLOSED = "CLOSED";
    public static final String S_ALREADY_CLOSED = "ALREADY_CLOSED";
    public static final String S_STALE = "STALE";
    public static final String S_UNAVAILABLE = "UNAVAILABLE";

    // ---- 注册失败原因 ----
    public static final String R_DISABLED = "DISABLED";
    public static final String R_UNSUPPORTED_PROTOCOL = "UNSUPPORTED_PROTOCOL";
    public static final String R_UNSUPPORTED_RUNTIME = "UNSUPPORTED_RUNTIME";
    public static final String R_MISSING_CAPABILITY = "MISSING_CAPABILITY";
    public static final String R_PROVIDER_ALREADY_REGISTERED = "PROVIDER_ALREADY_REGISTERED";
    public static final String R_INVALID_REQUEST = "INVALID_REQUEST";

    // ---- DISCONNECT / 停用原因 ----
    public static final String R_REPLACED = "REPLACED";
    public static final String R_BRIDGE_UNAVAILABLE = "BRIDGE_UNAVAILABLE";

    // ---- 事件 op（扩展 → ConnectPlus）----
    public static final String OP_SESSION_CLOSED = "SESSION_CLOSED";
    public static final String OP_PROVIDER_STOPPING = "PROVIDER_STOPPING";
    public static final String OP_VERIFIED_DUPLICATE_CANDIDATE = "VERIFIED_DUPLICATE_CANDIDATE";

    // ---- 事件响应 ----
    public static final String S_ACK = "ACK";
    public static final String S_READY = "READY";
    public static final String S_DENIED = "DENIED";

    // ---- 重复登录协调 denied 原因 ----
    public static final String R_OLD_SESSION_NOT_MANAGED = "OLD_SESSION_NOT_MANAGED";
    public static final String R_SAVE_FAILED = "SAVE_FAILED";
    public static final String R_TIMEOUT = "TIMEOUT";
    public static final String R_INVALID_IDENTITY = "INVALID_IDENTITY";

    /** 本扩展自身的诊断前缀：不与协议保留值冲突，用于 UNAVAILABLE 等场景的补充说明。 */
    public static final String R_STALE_EPOCH = "STALE_EPOCH";

    /** REPLACED 断开提示，必须原样传给玩家（文档 4.2）。 */
    public static final String REPLACED_MESSAGE = "检测到异地登录，你的账号已在其他客户端登录，当前连接已断开。";

    /** v1 三项必需基础能力；重复登录准入为可选保留能力。 */
    public static final String CAP_VERIFIED_XUID = "verified-xuid";
    public static final String CAP_EXACT_CHANNEL_BINDING = "exact-channel-binding";
    public static final String CAP_TARGETED_DISCONNECT = "targeted-disconnect";
    public static final String CAP_AUTHENTICATED_DUPLICATE_ADMISSION = "authenticated-duplicate-admission";
}
