package dev.connectplus.geyserbridge;

import dev.connectplus.geyserbridge.adapter.GeyserViaProxyAdapter;
import dev.connectplus.geyserbridge.connectplus.ConnectPlusEndpointClient;
import dev.connectplus.geyserbridge.connectplus.ConnectPlusLocator;
import dev.connectplus.geyserbridge.handler.BridgeRequestHandler;
import dev.connectplus.geyserbridge.handler.GeyserSessionAccess;
import dev.connectplus.geyserbridge.identity.GeyserIdentityVerifier;
import dev.connectplus.geyserbridge.protocol.BridgeProtocol;
import dev.connectplus.geyserbridge.session.BridgeSession;
import dev.connectplus.geyserbridge.session.BridgeSessionIndex;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionInitializeEvent;
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.session.GeyserSession;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ConnectPlus-GeyserBridge 主类（ExtensionBootstrap 职责，协议第 5 节）：
 * extension.yml 装配、Geyser 事件接入、ConnectPlus 注册与有界重试、停用注销与版本报告。
 *
 * providerEpoch 在本实例（即本次启用）创建时生成一次，重启不复用。
 * registrationId 只保存在内存里，不写日志、不持久化、不下发客户端。
 */
public final class ConnectPlusGeyserBridge implements Extension {

    static final int REGISTER_RETRY_MAX = 30;
    static final long REGISTER_RETRY_INTERVAL_SECONDS = 1;
    static final Duration EVENT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration SESSION_CLOSED_TIMEOUT = Duration.ofSeconds(2);

    private final String providerEpoch = UUID.randomUUID().toString();

    private volatile BridgeSessionIndex index;
    private volatile GeyserIdentityVerifier verifier;
    private volatile BridgeRequestHandler handler;
    private volatile ConnectPlusEndpointClient endpointClient;
    private volatile ScheduledThreadPoolExecutor scheduler;
    private volatile String registrationId;
    private volatile List<String> capabilities = List.of();
    private volatile String geyserVersion = "unknown";
    private volatile boolean stopped;

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "ConnectPlus-GeyserBridge");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);

        GeyserViaProxyAdapter.Health health = GeyserViaProxyAdapter.selfCheck();
        geyserVersion = health.geyserVersion();
        if (!health.available()) {
            logger().severe("bridge disabled: internal Geyser API probe failed: " + health.problems());
            logger().severe("refusing to enable: a version mismatch must never guess a match result");
            return;
        }
        verifier = GeyserIdentityVerifier.snapshot();
        List<String> runtimeProblems = verifier.runtimeProblems();
        if (!runtimeProblems.isEmpty()) {
            logger().severe("bridge disabled: unsupported runtime configuration: " + runtimeProblems);
            return;
        }

        index = new BridgeSessionIndex();
        handler = new BridgeRequestHandler(new BridgeEnvironment());
        // Only ordinary official Geyser lifecycle events are subscribed.
        // Native duplicate-XUID rejection is retained; no host admission hook is installed.
        capabilities = List.of(
                BridgeProtocol.CAP_VERIFIED_XUID,
                BridgeProtocol.CAP_EXACT_CHANNEL_BINDING,
                BridgeProtocol.CAP_TARGETED_DISCONNECT);
        logger().info("official host mode: duplicate XUID logins keep native Geyser rejection");

        scheduler.scheduleWithFixedDelay(this::sweepSessions, 30, 30, TimeUnit.SECONDS);
        scheduleRegistrationAttempt(0);
    }

    @Subscribe
    public void onShutdown(GeyserShutdownEvent event) {
        stopBridge("geyser shutdown");
    }

    // ---- 注册（有界异步重试：每秒一次、最多 30 次） ----

    private void scheduleRegistrationAttempt(int attempt) {
        if (stopped || attempt >= REGISTER_RETRY_MAX) {
            if (!stopped) {
                logger().severe("ConnectPlus not found after " + REGISTER_RETRY_MAX
                        + " attempts; bridge stays disabled for this epoch (reason: PROVIDER_UNAVAILABLE)");
            }
            return;
        }
        scheduler.schedule(() -> {
            if (stopped || registrationId != null) {
                return;
            }
            try {
                Object plugin = ConnectPlusLocator.locate();
                if (plugin == null) {
                    if (attempt % 10 == 9) {
                        logger().info("ConnectPlus plugin not loaded yet (attempt " + (attempt + 1) + ")");
                    }
                    scheduleRegistrationAttempt(attempt + 1);
                    return;
                }
                ConnectPlusEndpointClient client;
                try {
                    client = ConnectPlusEndpointClient.create(plugin);
                } catch (IllegalArgumentException e) {
                    // ConnectPlus 存在但缺少协议方法：运行时不受支持，重试无意义
                    logger().severe("bridge disabled: " + e.getMessage() + " (reason: UNSUPPORTED_RUNTIME)");
                    return;
                }
                ConnectPlusEndpointClient.Registration result = client.register(descriptor(), handler);
                if (result.registered()) {
                    registrationId = result.registrationId();
                    endpointClient = client;
                    logger().info("registered with ConnectPlus (bridge capabilities: " + capabilities + ")");
                } else {
                    logger().severe("ConnectPlus rejected registration, reasonCode=" + result.reasonCode()
                            + "; bridge stays disabled for this epoch");
                }
            } catch (RuntimeException e) {
                // 瞬时异常（对端内部错误等）继续有界重试；注册被明确拒绝时才停摆
                logger().warning("registration attempt " + (attempt + 1) + " failed: " + e.getMessage());
                scheduleRegistrationAttempt(attempt + 1);
            }
        }, REGISTER_RETRY_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private Map<String, Object> descriptor() {
        Map<String, Object> descriptor = new HashMap<>();
        descriptor.put(BridgeProtocol.F_PROTOCOL_VERSION, BridgeProtocol.PROTOCOL_VERSION);
        descriptor.put(BridgeProtocol.F_PROVIDER_ID, BridgeProtocol.PROVIDER_ID);
        descriptor.put(BridgeProtocol.F_PROVIDER_EPOCH, providerEpoch);
        descriptor.put(BridgeProtocol.F_BRIDGE_VERSION, bridgeVersion());
        descriptor.put(BridgeProtocol.F_GEYSER_VERSION, geyserVersion);
        descriptor.put(BridgeProtocol.F_VIAPROXY_VERSION, ConnectPlusLocator.viaProxyVersion());
        descriptor.put(BridgeProtocol.F_CAPABILITIES, capabilities);
        return descriptor;
    }

    private String bridgeVersion() {
        try {
            return description().version();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    // ---- 停用 ----

    private void stopBridge(String reason) {
        if (stopped) {
            return;
        }
        stopped = true;
        String currentRegistrationId = registrationId;
        registrationId = null;
        if (currentRegistrationId != null && endpointClient != null) {
            Map<String, Object> event = new HashMap<>();
            event.put(BridgeProtocol.F_OP, BridgeProtocol.OP_PROVIDER_STOPPING);
            event.put(BridgeProtocol.F_PROTOCOL_VERSION, BridgeProtocol.PROTOCOL_VERSION);
            event.put(BridgeProtocol.F_PROVIDER_EPOCH, providerEpoch);
            event.put(BridgeProtocol.F_REASON_CODE, reason);
            try {
                endpointClient.fireEvent(currentRegistrationId, event, EVENT_TIMEOUT)
                        .orTimeout(EVENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .whenComplete((response, error) -> {
                            if (error != null) {
                                logger().warning("PROVIDER_STOPPING was not acknowledged: " + error);
                            }
                        });
            } catch (RuntimeException e) {
                logger().warning("failed to send PROVIDER_STOPPING: " + e.getMessage());
            }
        }
        BridgeSessionIndex currentIndex = index;
        if (currentIndex != null) {
            currentIndex.clearAll();
        }
        ScheduledThreadPoolExecutor currentScheduler = scheduler;
        if (currentScheduler != null) {
            currentScheduler.shutdown();
        }
        logger().info("bridge stopped (" + reason + ")");
    }

    // ---- 会话生命周期（Geyser 事件总线） ----

    @Subscribe
    public void onSessionInitialize(SessionInitializeEvent event) {
        BridgeSessionIndex currentIndex = index;
        if (currentIndex == null || stopped) {
            return;
        }
        if (!(event.connection() instanceof GeyserSession session)) {
            return;
        }
        BridgeSession bridge = currentIndex.register(providerEpoch, session);
        try {
            if (session.getAuthData() != null) {
                bridge.xuid(session.xuid());
                bridge.bedrockUsername(session.bedrockUsername());
                currentIndex.bindXuid(bridge, bridge.xuid());
            }
            bridge.clientVersion(session.version());
            bridge.locale(session.locale());
        } catch (RuntimeException e) {
            // 认证尚未完成的极端时序：身份字段在 RESOLVE 时仍可补齐
            logger().debug("deferred identity capture for " + bridge.bridgeSessionId() + ": " + e.getMessage());
        }
    }

    @Subscribe
    public void onSessionLogin(SessionLoginEvent event) {
        BridgeSessionIndex currentIndex = index;
        if (currentIndex == null) {
            return;
        }
        BridgeSession bridge = currentIndex.bySession(event.connection());
        if (bridge != null) {
            bridge.markLoginSeen();
        }
    }

    @Subscribe
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        BridgeSessionIndex currentIndex = index;
        if (currentIndex == null) {
            return;
        }
        BridgeSession bridge = currentIndex.bySession(event.connection());
        if (bridge == null) {
            return;
        }
        currentIndex.close(bridge);
        sendSessionClosed(bridge);
    }

    private void sendSessionClosed(BridgeSession bridge) {
        String currentRegistrationId = registrationId;
        ConnectPlusEndpointClient client = endpointClient;
        if (currentRegistrationId == null || client == null) {
            return;
        }
        Map<String, Object> event = new HashMap<>();
        event.put(BridgeProtocol.F_OP, BridgeProtocol.OP_SESSION_CLOSED);
        event.put(BridgeProtocol.F_PROTOCOL_VERSION, BridgeProtocol.PROTOCOL_VERSION);
        event.put(BridgeProtocol.F_PROVIDER_EPOCH, providerEpoch);
        event.put(BridgeProtocol.F_BRIDGE_SESSION_ID, bridge.bridgeSessionId());
        if (bridge.connectionId() != null) {
            event.put(BridgeProtocol.F_CONNECTION_ID, bridge.connectionId());
        }
        client.fireEvent(currentRegistrationId, event, SESSION_CLOSED_TIMEOUT)
                .whenComplete((response, error) -> {
                    if (error != null) {
                        // 迟到/失败通知可安全重放（协议 4.3），这里只记录诊断
                        logger().warning("SESSION_CLOSED not acknowledged for "
                                + bridge.bridgeSessionId() + ": " + rootMessage(error));
                    }
                });
    }

    private void sweepSessions() {
        BridgeSessionIndex currentIndex = index;
        if (currentIndex == null) {
            return;
        }
        for (BridgeSession bridge : currentIndex.activeSessions()) {
            if (bridge.session() instanceof GeyserSession session && session.isClosed()) {
                currentIndex.close(bridge);
                sendSessionClosed(bridge);
            }
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.toString();
    }

    /** 延迟持有 GeyserApi，避免测试环境在类加载时触碰 Geyser。 */
    static final class GeyserApiHolder {
        static org.geysermc.geyser.api.GeyserApi api() {
            return org.geysermc.geyser.api.GeyserApi.api();
        }
    }

    /** handler 环境装配。 */
    private final class BridgeEnvironment implements BridgeRequestHandler.Environment {
        @Override
        public String currentEpoch() {
            return registrationId != null ? providerEpoch : null;
        }

        @Override
        public java.util.concurrent.ScheduledExecutorService scheduler() {
            return ConnectPlusGeyserBridge.this.scheduler;
        }

        @Override
        public dev.connectplus.geyserbridge.handler.SessionAccess access() {
            return new GeyserSessionAccess(verifier);
        }

        @Override
        public BridgeSessionIndex index() {
            return index;
        }

        @Override
        public java.util.function.Consumer<String> logger() {
            return message -> ConnectPlusGeyserBridge.this.logger().warning(message);
        }
    }
}
