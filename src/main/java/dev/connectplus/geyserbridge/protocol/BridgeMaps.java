package dev.connectplus.geyserbridge.protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * 边界 Map 的类型化读取。接收方不假定外部 Map 一定合法（协议第 4 节）：
 * 所有取值都带类型与必填校验，错误统一归入 {@link BridgeRequestException}。
 * 返回的快照 Map 一律不可变。
 */
public final class BridgeMaps {

    private BridgeMaps() {
    }

    /** 协议字段错误；handler 与事件两侧统一映射为 REJECTED/INVALID_REQUEST。 */
    public static final class BridgeRequestException extends IllegalArgumentException {
        public BridgeRequestException(String message) {
            super(message);
        }
    }

    public static Map<String, Object> requireMap(Object value, String field) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new BridgeRequestException("missing or malformed field: " + field);
        }
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (e.getKey() instanceof String key) {
                out.put(key, e.getValue());
            }
        }
        return out;
    }

    public static String requireString(Map<String, Object> map, String field) {
        Object v = map.get(field);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new BridgeRequestException("missing or malformed field: " + field);
        }
        return s;
    }

    /** 可省略的字符串字段；存在但类型错误时报错，而不是静默丢弃。 */
    public static String optionalString(Map<String, Object> map, String field) {
        Object v = map.get(field);
        if (v == null) {
            return null;
        }
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new BridgeRequestException("malformed field: " + field);
        }
        return s;
    }

    public static int requireInt(Map<String, Object> map, String field) {
        Object v = map.get(field);
        if (v instanceof Integer i) {
            return i;
        }
        if (v instanceof Number n && n.intValue() == n.doubleValue()) {
            return n.intValue();
        }
        throw new BridgeRequestException("missing or malformed field: " + field);
    }

    public static Object requireValue(Map<String, Object> map, String field) {
        Object v = map.get(field);
        if (v == null) {
            throw new BridgeRequestException("missing field: " + field);
        }
        return v;
    }

    /** 回传请求中存在的 connectionId / bridgeSessionId（协议 4.2 开头要求）。 */
    public static void echoIfPresent(Map<String, Object> request, Map<String, Object> response, String field) {
        Object v = request.get(field);
        if (v != null) {
            response.put(field, v);
        }
    }

    public static Map<String, Object> snapshot(Map<String, Object> map) {
        return java.util.Collections.unmodifiableMap(new HashMap<>(map));
    }
}
