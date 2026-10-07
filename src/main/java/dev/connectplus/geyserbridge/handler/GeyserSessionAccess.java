package dev.connectplus.geyserbridge.handler;

import dev.connectplus.geyserbridge.adapter.GeyserViaProxyAdapter;
import dev.connectplus.geyserbridge.identity.GeyserIdentityVerifier;
import org.geysermc.geyser.session.GeyserSession;

import java.net.SocketAddress;
import java.util.UUID;

/**
 * {@link SessionAccess} 的真实实现：唯一允许触碰 Geyser 内部会话类型的地方之一。
 */
public final class GeyserSessionAccess implements SessionAccess {

    private final GeyserIdentityVerifier verifier;

    public GeyserSessionAccess(GeyserIdentityVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public Object matchC2p(SocketAddress rawLocal, SocketAddress rawRemote) {
        return GeyserViaProxyAdapter.matchC2p(rawLocal, rawRemote);
    }

    @Override
    public boolean isClosed(Object session) {
        return !(session instanceof GeyserSession s) || s.isClosed();
    }

    @Override
    public boolean downstreamAlive(Object session) {
        return session instanceof GeyserSession s && GeyserViaProxyAdapter.downstreamAlive(s);
    }

    @Override
    public String xuid(Object session) {
        return session instanceof GeyserSession s ? s.xuid() : null;
    }

    @Override
    public String bedrockUsername(Object session) {
        return session instanceof GeyserSession s ? s.bedrockUsername() : null;
    }

    @Override
    public String clientVersion(Object session) {
        return session instanceof GeyserSession s ? s.version() : null;
    }

    @Override
    public String locale(Object session) {
        return session instanceof GeyserSession s ? s.locale() : null;
    }

    @Override
    public Object javaUuid(Object session) {
        if (!(session instanceof GeyserSession s)) {
            return null;
        }
        UUID uuid = s.javaUuid();
        return uuid == null ? null : uuid;
    }

    @Override
    public boolean identityTrusted(Object session) {
        return session instanceof GeyserSession s && verifier.isTrustworthy(s);
    }

    @Override
    public void disconnect(Object session, String message, Runnable after) {
        if (session instanceof GeyserSession s) {
            GeyserViaProxyAdapter.disconnect(s, message, after);
        } else if (after != null) {
            after.run();
        }
    }
}
