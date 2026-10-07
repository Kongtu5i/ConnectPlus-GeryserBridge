package dev.connectplus.geyserbridge.handler;

import dev.connectplus.geyserbridge.protocol.BridgeProtocol;
import dev.connectplus.geyserbridge.session.BridgeSession;
import dev.connectplus.geyserbridge.session.BridgeSessionIndex;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeRequestHandlerTest {

    private static final String EPOCH = "11111111-1111-1111-1111-111111111111";

    /** 可编程的会话访问替身。 */
    private static final class FakeAccess implements SessionAccess {
        Object nextMatch;
        boolean trusted = true;
        boolean closed;
        boolean downstreamAlive = true;
        String xuid = "12345678901234567890";
        String javaUuid;
        String lastDisconnectMessage;
        final Runnable[] disconnectRan = new Runnable[1];

        @Override
        public Object matchC2p(SocketAddress rawLocal, SocketAddress rawRemote) {
            return nextMatch;
        }

        @Override
        public boolean isClosed(Object session) {
            return closed;
        }

        @Override
        public boolean downstreamAlive(Object session) {
            return downstreamAlive;
        }

        @Override
        public String xuid(Object session) {
            return xuid;
        }

        @Override
        public String bedrockUsername(Object session) {
            return "Steve";
        }

        @Override
        public String clientVersion(Object session) {
            return "1.21.50";
        }

        @Override
        public String locale(Object session) {
            return "en_US";
        }

        @Override
        public Object javaUuid(Object session) {
            return javaUuid;
        }

        @Override
        public boolean identityTrusted(Object session) {
            return trusted;
        }

        @Override
        public void disconnect(Object session, String message, Runnable after) {
            this.lastDisconnectMessage = message;
            // 模拟真实实现：断开调度到事件循环后执行 after 回调
            this.disconnectRan[0] = after;
            if (after != null) {
                after.run();
            }
        }
    }

    private static final class FakeEnv implements BridgeRequestHandler.Environment {
        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        final BridgeSessionIndex index = new BridgeSessionIndex();
        final FakeAccess access = new FakeAccess();
        String epoch = EPOCH;

        @Override
        public String currentEpoch() {
            return epoch;
        }

        @Override
        public ScheduledExecutorService scheduler() {
            return scheduler;
        }

        @Override
        public SessionAccess access() {
            return access;
        }

        @Override
        public BridgeSessionIndex index() {
            return index;
        }

        @Override
        public Consumer<String> logger() {
            return message -> { };
        }

        @Override
        public long resolvePollIntervalMs() {
            return 10;
        }

        @Override
        public long resolveDeadlineMs() {
            return 400;
        }
    }

    private static Map<String, Object> baseRequest(String op) {
        Map<String, Object> request = new HashMap<>();
        request.put(BridgeProtocol.F_OP, op);
        request.put(BridgeProtocol.F_PROTOCOL_VERSION, BridgeProtocol.PROTOCOL_VERSION);
        request.put(BridgeProtocol.F_PROVIDER_EPOCH, EPOCH);
        return request;
    }

    private static Map<String, Object> resolveRequest(Channel channel, String connectionId) {
        Map<String, Object> request = baseRequest(BridgeProtocol.OP_RESOLVE);
        request.put(BridgeProtocol.F_CONNECTION_ID, connectionId);
        request.put(BridgeProtocol.F_CHANNEL, channel);
        request.put(BridgeProtocol.F_RAW_LOCAL_ADDRESS, new InetSocketAddress("127.0.0.1", 25565));
        request.put(BridgeProtocol.F_RAW_REMOTE_ADDRESS, new InetSocketAddress("127.0.0.1", 50001));
        return request;
    }

    private static Map<String, Object> await(CompletionStage<Map<String, Object>> stage) throws Exception {
        return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void rejectsMalformedAndUnknownRequests() throws Exception {
        FakeEnv env = new FakeEnv();
        BridgeRequestHandler handler = new BridgeRequestHandler(env);

        Map<String, Object> badVersion = baseRequest(BridgeProtocol.OP_VALIDATE);
        badVersion.put(BridgeProtocol.F_PROTOCOL_VERSION, 2);
        assertEquals(BridgeProtocol.S_REJECTED, await(handler.apply(badVersion)).get(BridgeProtocol.F_STATUS));

        Map<String, Object> unknown = baseRequest("SOMETHING");
        Map<String, Object> unknownResponse = await(handler.apply(unknown));
        assertEquals(BridgeProtocol.S_REJECTED, unknownResponse.get(BridgeProtocol.F_STATUS));
        assertEquals(BridgeProtocol.R_INVALID_REQUEST, unknownResponse.get(BridgeProtocol.F_REASON_CODE));

        Map<String, Object> missingFields = new HashMap<>();
        assertEquals(BridgeProtocol.S_REJECTED, await(handler.apply(missingFields)).get(BridgeProtocol.F_STATUS));
    }

    @Test
    void staleEpochYieldsUnavailableWithoutTouchingState() throws Exception {
        FakeEnv env = new FakeEnv();
        BridgeRequestHandler handler = new BridgeRequestHandler(env);
        env.epoch = "22222222-2222-2222-2222-222222222222"; // 注册已换代

        Map<String, Object> request = resolveRequest(new EmbeddedChannel(), UUID.randomUUID().toString());
        Map<String, Object> response = await(handler.apply(request));
        assertEquals(BridgeProtocol.S_UNAVAILABLE, response.get(BridgeProtocol.F_STATUS));
        assertEquals(BridgeProtocol.R_STALE_EPOCH, response.get(BridgeProtocol.F_REASON_CODE));
        assertEquals("22222222-2222-2222-2222-222222222222", response.get(BridgeProtocol.F_PROVIDER_EPOCH));
    }

    @Test
    void resolveReturnsVerifiedIdentityWithSessionId() throws Exception {
        FakeEnv env = new FakeEnv();
        BridgeRequestHandler handler = new BridgeRequestHandler(env);
        Object geyserSession = new Object();
        env.access.nextMatch = geyserSession;
        BridgeSession bridge = env.index.register(EPOCH, geyserSession);

        Channel channel = new EmbeddedChannel();
        Map<String, Object> response = await(handler.apply(resolveRequest(channel, "conn-1")));

        assertEquals(BridgeProtocol.S_VERIFIED, response.get(BridgeProtocol.F_STATUS));
        assertEquals("conn-1", response.get(BridgeProtocol.F_CONNECTION_ID));
        assertEquals(bridge.bridgeSessionId(), response.get(BridgeProtocol.F_BRIDGE_SESSION_ID));
        assertEquals(env.access.xuid, response.get(BridgeProtocol.F_XUID));
        assertEquals("Steve", response.get(BridgeProtocol.F_BEDROCK_USERNAME));
        assertEquals("1.21.50", response.get(BridgeProtocol.F_CLIENT_VERSION));
        assertEquals("en_US", response.get(BridgeProtocol.F_LOCALE));
        assertTrue(env.access.xuid.equals(bridge.xuid()));
        assertTrue(bridge.isManaged());
    }

    @Test
    void resolvePollsUntilMatchAppearsOrTimesOut() throws Exception {
        FakeEnv env = new FakeEnv();
        BridgeRequestHandler handler = new BridgeRequestHandler(env);
        Channel channel = new EmbeddedChannel();

        // 无匹配 → 预算耗尽后 NO_MATCH
        Map<String, Object> response = await(handler.apply(resolveRequest(channel, "conn-x")));
        assertEquals(BridgeProtocol.S_NO_MATCH, response.get(BridgeProtocol.F_STATUS));

        // 第二次请求期间出现匹配 → VERIFIED
        Object geyserSession = new Object();
        env.access.nextMatch = geyserSession;
        env.index.register(EPOCH, geyserSession);
        Map<String, Object> second = await(handler.apply(resolveRequest(new EmbeddedChannel(), "conn-y")));
        assertEquals(BridgeProtocol.S_VERIFIED, second.get(BridgeProtocol.F_STATUS));
    }

    @Test
    void resolveNeverTransfersChannelBinding() throws Exception {
        FakeEnv env = new FakeEnv();
        BridgeRequestHandler handler = new BridgeRequestHandler(env);
        Object geyserSession = new Object();
        env.access.nextMatch = geyserSession;
        env.index.register(EPOCH, geyserSession);

        Channel first = new EmbeddedChannel();
        assertEquals(BridgeProtocol.S_VERIFIED,
                await(handler.apply(resolveRequest(first, "conn-1"))).get(BridgeProtocol.F_STATUS));

        // 同一会话出现到另一个 Channel：拒绝，绑定不转移
        Channel second = new EmbeddedChannel();
        Map<String, Object> response = await(handler.apply(resolveRequest(second, "conn-1")));
        assertEquals(BridgeProtocol.S_REJECTED, response.get(BridgeProtocol.F_STATUS));
        assertEquals("CHANNEL_CONFLICT", response.get(BridgeProtocol.F_REASON_CODE));
    }

    @Test
    void resolveRejectsUntrustedIdentityWithoutLeakingSnapshot() throws Exception {
        FakeEnv env = new FakeEnv();
        BridgeRequestHandler handler = new BridgeRequestHandler(env);
        Object geyserSession = new Object();
        env.access.nextMatch = geyserSession;
        env.index.register(EPOCH, geyserSession);
        env.access.trusted = false;

        Map<String, Object> response = await(handler.apply(resolveRequest(new EmbeddedChannel(), "conn-1")));
        assertEquals(BridgeProtocol.S_REJECTED, response.get(BridgeProtocol.F_STATUS));
        assertEquals("IDENTITY_UNTRUSTED", response.get(BridgeProtocol.F_REASON_CODE));
        assertEquals(null, response.get(BridgeProtocol.F_XUID)); // 负面响应不携带身份快照
    }

    @Test
    void validateChecksSessionChannelAndConnectionId() throws Exception {
        FakeEnv env = new FakeEnv();
        BridgeRequestHandler handler = new BridgeRequestHandler(env);
        Object geyserSession = new Object();
        env.access.nextMatch = geyserSession;
        BridgeSession bridge = env.index.register(EPOCH, geyserSession);

        Channel channel = new EmbeddedChannel();
        channel.id(); // 确保初始化
        assertEquals(BridgeProtocol.S_VERIFIED,
                await(handler.apply(resolveRequest(channel, "conn-1"))).get(BridgeProtocol.F_STATUS));

        Map<String, Object> validate = baseRequest(BridgeProtocol.OP_VALIDATE);
        validate.put(BridgeProtocol.F_CONNECTION_ID, "conn-1");
        validate.put(BridgeProtocol.F_BRIDGE_SESSION_ID, bridge.bridgeSessionId());
        validate.put(BridgeProtocol.F_CHANNEL, channel);
        assertEquals(BridgeProtocol.S_VALID, await(handler.apply(validate)).get(BridgeProtocol.F_STATUS));

        // 换 Channel → INVALID
        validate.put(BridgeProtocol.F_CHANNEL, new EmbeddedChannel());
        assertEquals(BridgeProtocol.S_INVALID, await(handler.apply(validate)).get(BridgeProtocol.F_STATUS));

        // 换回正确 Channel 但会话已死 → INVALID
        validate.put(BridgeProtocol.F_CHANNEL, channel);
        env.access.closed = true;
        assertEquals(BridgeProtocol.S_INVALID, await(handler.apply(validate)).get(BridgeProtocol.F_STATUS));

        // 未知 id → INVALID
        env.access.closed = false;
        validate.put(BridgeProtocol.F_BRIDGE_SESSION_ID, "does-not-exist");
        assertEquals(BridgeProtocol.S_INVALID, await(handler.apply(validate)).get(BridgeProtocol.F_STATUS));
    }

    @Test
    void disconnectClosesOnlyTheTargetedSession() throws Exception {
        FakeEnv env = new FakeEnv();
        BridgeRequestHandler handler = new BridgeRequestHandler(env);
        Object geyserSession = new Object();
        env.access.nextMatch = geyserSession;
        BridgeSession bridge = env.index.register(EPOCH, geyserSession);

        Channel channel = new EmbeddedChannel();
        assertEquals(BridgeProtocol.S_VERIFIED,
                await(handler.apply(resolveRequest(channel, "conn-1"))).get(BridgeProtocol.F_STATUS));

        Map<String, Object> request = baseRequest(BridgeProtocol.OP_DISCONNECT);
        request.put(BridgeProtocol.F_CONNECTION_ID, "conn-1");
        request.put(BridgeProtocol.F_BRIDGE_SESSION_ID, bridge.bridgeSessionId());
        request.put(BridgeProtocol.F_REASON_CODE, BridgeProtocol.R_REPLACED);
        request.put(BridgeProtocol.F_CHANNEL, channel);
        request.remove(BridgeProtocol.F_MESSAGE); // REPLACED 有协议规定的默认文案

        Map<String, Object> response = await(handler.apply(request));
        assertEquals(BridgeProtocol.S_CLOSED, response.get(BridgeProtocol.F_STATUS));
        assertEquals(BridgeProtocol.REPLACED_MESSAGE, env.access.lastDisconnectMessage);
        assertTrue(bridge.isClosed());

        // 重放同一请求 → ALREADY_CLOSED（墓碑）
        assertEquals(BridgeProtocol.S_ALREADY_CLOSED, await(handler.apply(request)).get(BridgeProtocol.F_STATUS));

        // 另一个活跃会话收到 connectionId 不匹配的 DISCONNECT → STALE
        Object survivorSession = new Object();
        env.access.nextMatch = survivorSession;
        BridgeSession survivor = env.index.register(EPOCH, survivorSession);
        assertEquals(BridgeProtocol.S_VERIFIED,
                await(handler.apply(resolveRequest(new EmbeddedChannel(), "conn-2"))).get(BridgeProtocol.F_STATUS));
        Map<String, Object> staleRequest = baseRequest(BridgeProtocol.OP_DISCONNECT);
        staleRequest.put(BridgeProtocol.F_CONNECTION_ID, "conn-9");
        staleRequest.put(BridgeProtocol.F_BRIDGE_SESSION_ID, survivor.bridgeSessionId());
        staleRequest.put(BridgeProtocol.F_REASON_CODE, BridgeProtocol.R_REPLACED);
        assertEquals(BridgeProtocol.S_STALE, await(handler.apply(staleRequest)).get(BridgeProtocol.F_STATUS));
    }

    @Test
    void disconnectRejectsUnknownReasonCodes() throws Exception {
        FakeEnv env = new FakeEnv();
        BridgeRequestHandler handler = new BridgeRequestHandler(env);
        Map<String, Object> request = baseRequest(BridgeProtocol.OP_DISCONNECT);
        request.put(BridgeProtocol.F_CONNECTION_ID, "c");
        request.put(BridgeProtocol.F_BRIDGE_SESSION_ID, "s");
        request.put(BridgeProtocol.F_REASON_CODE, "WHATEVER");
        Map<String, Object> response = await(handler.apply(request));
        assertEquals(BridgeProtocol.S_REJECTED, response.get(BridgeProtocol.F_STATUS));
        assertEquals(BridgeProtocol.R_INVALID_REQUEST, response.get(BridgeProtocol.F_REASON_CODE));
    }
}
