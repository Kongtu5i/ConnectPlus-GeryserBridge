package dev.connectplus.geyserbridge.handler;

import java.net.SocketAddress;

/**
 * handler 与 Geyser 内部 API 之间的窄接口，便于在 JVM 单元测试中替换。
 * 所有方法都只在桥接自己的线程族上被调用；返回值是快照，不做跨线程缓存。
 */
public interface SessionAccess {

    /** 精确关联：c2p 原始地址 → 唯一活跃内部会话；无匹配或多义返回 null。 */
    Object matchC2p(SocketAddress rawLocal, SocketAddress rawRemote);

    boolean isClosed(Object session);

    boolean downstreamAlive(Object session);

    String xuid(Object session);

    String bedrockUsername(Object session);

    String clientVersion(Object session);

    String locale(Object session);

    /** @return Java UUID（wireUuid 交叉核对用）；登录完成前可为 null。 */
    Object javaUuid(Object session);

    /** @return true 当会话已完成可信 Xbox 身份校验（authData + 运行模式约束）。 */
    boolean identityTrusted(Object session);

    /** 在会话自己的事件循环上断开；after 在断开调用发起后执行。 */
    void disconnect(Object session, String message, Runnable after);
}
