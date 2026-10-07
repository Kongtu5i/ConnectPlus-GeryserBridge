package dev.connectplus.geyserbridge.protocol;

/**
 * XUID 校验：已验证的正整数十进制字符串，范围 1 至 2^64-1，不含前导零。
 * 文档第 3 节明确不得把 XUID 解析为有符号 long，因此全部按字符串比较。
 */
public final class Xuid {

    private static final String MAX = "18446744073709551615"; // 2^64 - 1

    private Xuid() {
    }

    /**
     * @return true 当且仅当 xuid 是 1..18446744073709551615 的无前导零十进制字符串。
     */
    public static boolean isValid(String xuid) {
        if (xuid == null || xuid.isEmpty()) {
            return false;
        }
        int len = xuid.length();
        if (len > 20) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            char c = xuid.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        if (len > 1 && xuid.charAt(0) == '0') {
            return false; // 前导零
        }
        if (xuid.equals("0")) {
            return false; // 必须为正
        }
        if (len == 20 && xuid.compareTo(MAX) > 0) {
            return false;
        }
        return true;
    }

    /**
     * 校验失败时抛出 {@link IllegalArgumentException}。
     */
    public static String requireValid(String xuid) {
        if (!isValid(xuid)) {
            throw new IllegalArgumentException("invalid xuid: " + describe(xuid));
        }
        return xuid;
    }

    private static String describe(String xuid) {
        if (xuid == null) {
            return "null";
        }
        return "len=" + xuid.length() + (xuid.isEmpty() ? "" : ", head=" + xuid.charAt(0));
    }
}
