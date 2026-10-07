package dev.connectplus.geyserbridge.connectplus;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 在进程内定位 ConnectPlus 插件实例。协议第 4 节约定：
 * 通过 ViaProxy.getPluginManager().getPlugin("ConnectPlus") 获取真实插件实例。
 * 为避免把扩展自身绑定到某个 ViaProxy 内部 API 版本，这里全部走反射，
 * 任何缺失都以 null 报告，由上层决定停用原因。
 */
public final class ConnectPlusLocator {

    private static final String VIAPROXY_CLASS = "net.raphimc.viaproxy.ViaProxy";
    private static final String CONNECTPLUS_PLUGIN_NAME = "ConnectPlus";

    private ConnectPlusLocator() {
    }

    /** @return ConnectPlus 插件实例；ViaProxy 或插件尚未加载时返回 null。 */
    public static Object locate() {
        try {
            Class<?> viaProxy = Class.forName(VIAPROXY_CLASS);
            Method getPluginManager = viaProxy.getMethod("getPluginManager");
            Object pluginManager = getPluginManager.invoke(null);
            if (pluginManager == null) {
                return null;
            }
            Method getPlugin = pluginManager.getClass().getMethod("getPlugin", String.class);
            return getPlugin.invoke(pluginManager, CONNECTPLUS_PLUGIN_NAME);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    /**
     * @return ViaProxy 版本字符串；读取失败返回 "unknown"。
     */
    public static String viaProxyVersion() {
        try {
            Class<?> viaProxy = Class.forName(VIAPROXY_CLASS);
            Field version = viaProxy.getField("VERSION");
            Object value = version.get(null);
            if (value instanceof String s && !s.isBlank() && !s.startsWith("${")) {
                return s;
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // 无法确认版本时，运行时兼容检查会拒绝启用桥接。
        }
        return "unknown";
    }

    /**
     * 在插件实例的公共方法里按名称与参数形状查找（不依赖泛型擦除前的签名）。
     */
    public static Method findPublicMethod(Object plugin, String name, Class<?>... parameterTypes) {
        for (Method method : plugin.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != parameterTypes.length) {
                continue;
            }
            Class<?>[] actual = method.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < parameterTypes.length; i++) {
                // 参数类型按可赋值匹配：Map 参数既可能是 Map 也可能是它的子接口
                if (!parameterTypes[i].isAssignableFrom(actual[i]) && !actual[i].isAssignableFrom(parameterTypes[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return method;
            }
        }
        return null;
    }
}
