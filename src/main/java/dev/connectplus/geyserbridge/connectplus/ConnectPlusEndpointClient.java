package dev.connectplus.geyserbridge.connectplus;

import dev.connectplus.geyserbridge.protocol.BridgeMaps;
import dev.connectplus.geyserbridge.protocol.BridgeProtocol;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * ConnectPlus 端点客户端：协议第 4 节两个方法的反射调用封装。
 * 只使用 JDK 类型作为边界对象；不缓存跨调用的外部对象；
 * 事件调用带超时包装，迟到的响应结果由调用方按"不得改变已失效状态"处理。
 */
public final class ConnectPlusEndpointClient {

    private final Object plugin;
    private final Method registerMethod;
    private final Method eventMethod;

    private ConnectPlusEndpointClient(Object plugin, Method registerMethod, Method eventMethod) {
        this.plugin = plugin;
        this.registerMethod = registerMethod;
        this.eventMethod = eventMethod;
    }

    /**
     * 校验插件实例确实暴露了协议约定的两个公共方法；缺失即视为 UNSUPPORTED_RUNTIME。
     */
    public static ConnectPlusEndpointClient create(Object plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("plugin instance is null");
        }
        Method register = ConnectPlusLocator.findPublicMethod(
                plugin, "registerBedrockBridgeV1", Map.class, Function.class);
        Method event = ConnectPlusLocator.findPublicMethod(
                plugin, "bedrockBridgeEventV1", String.class, Map.class);
        if (register == null || event == null) {
            throw new IllegalArgumentException(
                    "ConnectPlus plugin does not expose the bedrockBridge V1 methods (unsupported runtime)");
        }
        return new ConnectPlusEndpointClient(plugin, register, event);
    }

    public Object plugin() {
        return plugin;
    }

    /** @return ConnectPlus 期望的 handler 形状：Map 请求 → CompletionStage&lt;Map&gt; 响应。 */
    public static Class<?> handlerType() {
        return Function.class;
    }

    public Registration register(Map<String, Object> descriptor,
                                 Function<Map<String, Object>, CompletionStage<Map<String, Object>>> handler) {
        try {
            Object result = registerMethod.invoke(plugin, descriptor, handler);
            Map<String, Object> response = BridgeMaps.requireMap(result, "registerBedrockBridgeV1 result");
            String status = BridgeMaps.requireString(response, BridgeProtocol.F_STATUS);
            if (BridgeProtocol.S_REGISTERED.equals(status)) {
                int protocolVersion = BridgeMaps.requireInt(response, BridgeProtocol.F_PROTOCOL_VERSION);
                if (protocolVersion != BridgeProtocol.PROTOCOL_VERSION) {
                    throw new IllegalStateException("ConnectPlus replied with protocolVersion " + protocolVersion);
                }
                String registrationId = BridgeMaps.requireString(response, BridgeProtocol.F_REGISTRATION_ID);
                return Registration.registered(registrationId);
            }
            String reasonCode = BridgeMaps.optionalString(response, BridgeProtocol.F_REASON_CODE);
            return Registration.rejected(reasonCode == null ? "UNKNOWN" : reasonCode);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("registerBedrockBridgeV1 threw: " + rootMessage(e), e);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("registerBedrockBridgeV1 failed: " + e.getMessage(), e);
        }
    }

    /**
     * 发送事件（SESSION_CLOSED / PROVIDER_STOPPING / VERIFIED_DUPLICATE_CANDIDATE）。
     * 非阻塞：返回的 future 在 ConnectPlus 完成响应或超时后完成。
     * 超时或异常的 future 结果只用于诊断，不得据此改变已失效的注册/候选状态。
     */
    public CompletableFuture<Map<String, Object>> fireEvent(String registrationId,
                                                            Map<String, Object> event,
                                                            Duration timeout) {
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        try {
            Object raw = eventMethod.invoke(plugin, registrationId, event);
            if (!(raw instanceof CompletionStage<?> stage)) {
                future.completeExceptionally(new IllegalStateException(
                        "bedrockBridgeEventV1 returned " + (raw == null ? "null" : raw.getClass().getName())));
                return future;
            }
            stage.toCompletableFuture().orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            future.completeExceptionally(error);
                        } else {
                            try {
                                future.complete(BridgeMaps.requireMap(result, "event response"));
                            } catch (RuntimeException e) {
                                future.completeExceptionally(e);
                            }
                        }
                    });
        } catch (InvocationTargetException e) {
            future.completeExceptionally(new IllegalStateException("bedrockBridgeEventV1 threw: " + rootMessage(e), e));
        } catch (ReflectiveOperationException | RuntimeException e) {
            future.completeExceptionally(new IllegalStateException("bedrockBridgeEventV1 failed: " + e.getMessage(), e));
        }
        return future;
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }

    /** 注册结果。registrationId 只保存在内存中，不写日志、不持久化（协议 4.1）。 */
    public record Registration(String registrationId, String reasonCode) {

        static Registration registered(String registrationId) {
            return new Registration(registrationId, null);
        }

        static Registration rejected(String reasonCode) {
            return new Registration(null, reasonCode);
        }

        public boolean registered() {
            return registrationId != null;
        }
    }
}
