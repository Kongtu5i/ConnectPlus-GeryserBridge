package dev.connectplus.mockconnectplus;

import io.netty.channel.Channel;
import net.lenni0451.lambdaevents.EventHandler;
import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.plugins.ViaProxyPlugin;
import net.raphimc.viaproxy.plugins.events.Client2ProxyChannelInitializeEvent;
import net.raphimc.viaproxy.plugins.events.types.ITyped;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * 模拟 ConnectPlus：只用于扩展侧开发的联调替身，绝不随扩展发布。
 *
 * 与真实 ConnectPlus 相同的接入方式：
 *  - 监听 Client2ProxyChannelInitializeEvent 的 PRE 阶段，保存原始地址并发起 RESOLVE
 *    （真实 ConnectPlus 在 PRE 阶段保存原始地址，正是协议 2.1 的关键时序）；
 *  - VERIFIED_DUPLICATE_CANDIDATE：先向 handler 发 DISCONNECT 关闭旧会话，再按配置决定
 *    READY / DENIED / 超时。
 *
 * 属性：-Dconnectplus.mock.candidate=ready|denied|timeout|save_failed（默认 ready）
 *      -Dconnectplus.mock.reason=SAVE_FAILED 等（denied/save_failed 的 reasonCode）
 *      -Dconnectplus.mock.rejectRegistration=DISABLED 等（注册阶段直接拒绝）
 */
public final class MockConnectPlusPlugin extends ViaProxyPlugin {

    private static final Logger LOGGER = Logger.getLogger("MockConnectPlus");

    private final Map<String, String> connectionIdByBridgeSessionId = new ConcurrentHashMap<>();
    private volatile String registrationId;
    private volatile String providerEpoch;
    private volatile Function<Map<String, Object>, CompletionStage<Map<String, Object>>> handler;

    @Override
    public void onEnable() {
        ViaProxy.EVENT_MANAGER.register(this);
        LOGGER.info("Mock ConnectPlus enabled (a bridge provider may now register)");
    }

    @Override
    public void onDisable() {
        LOGGER.info("Mock ConnectPlus disabled; registration dropped");
        registrationId = null;
    }

    /** PRE 阶段捕获原始连接信息并发起 RESOLVE——协议 2.1 的关键时序。 */
    @EventHandler
    private void onClient2ProxyChannelInitialize(final Client2ProxyChannelInitializeEvent event) {
        if (event.getType() != ITyped.Type.PRE || event.isLegacyPassthrough()) {
            return;
        }
        Function<Map<String, Object>, CompletionStage<Map<String, Object>>> currentHandler = handler;
        String currentEpoch = providerEpoch;
        String currentRegistrationId = registrationId;
        if (currentHandler == null || currentEpoch == null || currentRegistrationId == null) {
            return;
        }
        Channel channel = event.getChannel();
        Map<String, Object> request = new HashMap<>();
        request.put("op", "RESOLVE");
        request.put("protocolVersion", 1);
        request.put("providerEpoch", currentEpoch);
        request.put("connectionId", UUID.randomUUID().toString());
        request.put("channel", channel);
        request.put("rawLocalAddress", channel.localAddress());
        request.put("rawRemoteAddress", channel.remoteAddress());
        currentHandler.apply(request).toCompletableFuture().orTimeout(3, TimeUnit.SECONDS)
                .whenComplete((response, error) -> {
                    if (error != null) {
                        LOGGER.warning("RESOLVE failed: " + error);
                        return;
                    }
                    String status = String.valueOf(response.get("status"));
                    if ("VERIFIED".equals(status)) {
                        String bridgeSessionId = String.valueOf(response.get("bridgeSessionId"));
                        String connectionId = String.valueOf(request.get("connectionId"));
                        connectionIdByBridgeSessionId.put(bridgeSessionId, connectionId);
                        LOGGER.info("RESOLVE VERIFIED: bridgeSessionId=" + bridgeSessionId
                                + ", xuid=<len " + String.valueOf(response.get("xuid")).length() + ">");
                    } else {
                        LOGGER.info("RESOLVE " + status
                                + (response.get("reasonCode") == null ? "" : " (" + response.get("reasonCode") + ")"));
                    }
                });
    }

    // ---- 协议 4：ConnectPlus 侧的两个方法（由扩展反射调用） ----

    public Map<String, Object> registerBedrockBridgeV1(Map<String, Object> descriptor,
                                                       Function<Map<String, Object>, CompletionStage<Map<String, Object>>> handler) {
        String reject = System.getProperty("connectplus.mock.rejectRegistration");
        if (reject != null) {
            return Map.of("status", "REJECTED", "reasonCode", reject);
        }
        if (descriptor == null || handler == null) {
            return Map.of("status", "REJECTED", "reasonCode", "INVALID_REQUEST");
        }
        if (!Integer.valueOf(1).equals(descriptor.get("protocolVersion"))) {
            return Map.of("status", "REJECTED", "reasonCode", "UNSUPPORTED_PROTOCOL");
        }
        if (!"connectplus-geyser-bridge".equals(String.valueOf(descriptor.get("providerId")))) {
            return Map.of("status", "REJECTED", "reasonCode", "INVALID_REQUEST");
        }
        if (registrationId != null) {
            return Map.of("status", "REJECTED", "reasonCode", "PROVIDER_ALREADY_REGISTERED");
        }
        this.providerEpoch = String.valueOf(descriptor.get("providerEpoch"));
        this.registrationId = UUID.randomUUID().toString();
        this.handler = handler;
        LOGGER.info("bridge registered: epoch=" + this.providerEpoch
                + ", capabilities=" + descriptor.get("capabilities"));
        return Map.of("status", "REGISTERED", "protocolVersion", 1, "registrationId", this.registrationId);
    }

    public CompletionStage<Map<String, Object>> bedrockBridgeEventV1(String registrationId, Map<String, Object> event) {
        Map<String, Object> response = new HashMap<>();
        if (this.registrationId == null || !this.registrationId.equals(registrationId)) {
            response.put("status", "STALE");
            return CompletableFuture.completedFuture(response);
        }
        String op = String.valueOf(event.get("op"));
        response.put("op", op);
        response.put("protocolVersion", 1);
        response.put("providerEpoch", providerEpoch);
        switch (op) {
            case "SESSION_CLOSED" -> {
                response.put("status", "ACK");
                LOGGER.info("SESSION_CLOSED bridgeSessionId=" + event.get("bridgeSessionId"));
            }
            case "PROVIDER_STOPPING" -> {
                response.put("status", "ACK");
                LOGGER.info("PROVIDER_STOPPING reasonCode=" + event.get("reasonCode"));
                this.registrationId = null;
            }
            case "VERIFIED_DUPLICATE_CANDIDATE" -> handleCandidate(event, response);
            default -> {
                response.put("status", "REJECTED");
                response.put("reasonCode", "INVALID_REQUEST");
            }
        }
        return CompletableFuture.completedFuture(response);
    }

    // ---- 顶号协调：先 DISCONNECT 旧会话，再按配置决定 READY/DENIED ----

    private void handleCandidate(Map<String, Object> event, Map<String, Object> response) {
        String xuid = String.valueOf(event.get("xuid"));
        String oldBridgeSessionId = String.valueOf(event.get("oldBridgeSessionId"));
        String newBridgeSessionId = String.valueOf(event.get("newBridgeSessionId"));
        LOGGER.info("candidate: xuid=<len " + xuid.length() + ">, old=" + oldBridgeSessionId + ", new=" + newBridgeSessionId);

        String behavior = System.getProperty("connectplus.mock.candidate", "ready");
        if ("timeout".equals(behavior)) {
            return; // 不完成 future，模拟协调超时
        }
        if ("denied".equals(behavior) || "save_failed".equals(behavior)) {
            response.put("status", "DENIED");
            response.put("reasonCode", System.getProperty("connectplus.mock.reason",
                    "save_failed".equals(behavior) ? "SAVE_FAILED" : "OLD_SESSION_NOT_MANAGED"));
            response.put("newBridgeSessionId", newBridgeSessionId);
            return;
        }

        // ready：模拟真实 ConnectPlus——冻结并关闭旧会话后再放行
        String oldConnectionId = connectionIdByBridgeSessionId.get(oldBridgeSessionId);
        Function<Map<String, Object>, CompletionStage<Map<String, Object>>> currentHandler = handler;
        if (currentHandler == null || oldConnectionId == null) {
            LOGGER.warning("no tracked connectionId for old session " + oldBridgeSessionId
                    + "; completing READY without targeted disconnect");
            response.put("status", "READY");
            response.put("newBridgeSessionId", newBridgeSessionId);
            return;
        }
        Map<String, Object> disconnectRequest = new HashMap<>();
        disconnectRequest.put("op", "DISCONNECT");
        disconnectRequest.put("protocolVersion", 1);
        disconnectRequest.put("providerEpoch", providerEpoch);
        disconnectRequest.put("connectionId", oldConnectionId);
        disconnectRequest.put("bridgeSessionId", oldBridgeSessionId);
        disconnectRequest.put("reasonCode", "REPLACED");
        disconnectRequest.put("message", "检测到异地登录，你的账号已在其他客户端登录，当前连接已断开。");
        currentHandler.apply(disconnectRequest)
                .toCompletableFuture()
                .orTimeout(5, TimeUnit.SECONDS)
                .whenComplete((disconnectResponse, error) -> {
                    String status = error != null || disconnectResponse == null
                            ? "ERROR" : String.valueOf(disconnectResponse.get("status"));
                    LOGGER.info("targeted disconnect of old session -> " + status);
                    if ("CLOSED".equals(status) || "ALREADY_CLOSED".equals(status)) {
                        response.put("status", "READY");
                    } else {
                        response.put("status", "DENIED");
                        response.put("reasonCode", "SAVE_FAILED");
                    }
                    response.put("newBridgeSessionId", newBridgeSessionId);
                });
    }
}
