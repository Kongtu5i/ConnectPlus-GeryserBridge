package dev.connectplus.geyserbridge.util;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * 端点地址比较。协议 2.1 要求区分 IPv4/IPv6、地址规范化与端口：
 * 一律比较解析后的 InetAddress 字节与端口，而不是 toString 文本。
 * 未解析的地址退化为小写主机名比较（Geyser 下游本地地址在连接建立后总是已解析的）。
 */
public final class AddressUtil {

    private AddressUtil() {
    }

    /** Match both original c2p endpoints against the reverse downstream connection. */
    public static boolean matchesConnection(SocketAddress rawLocal, SocketAddress rawRemote,
                                            SocketAddress downstreamLocal, SocketAddress downstreamRemote) {
        return equalsAddress(downstreamLocal, rawRemote)
                && equalsAddress(downstreamRemote, rawLocal);
    }

    public static boolean equalsAddress(SocketAddress a, SocketAddress b) {
        if (a == null || b == null) {
            return false;
        }
        if (!(a instanceof InetSocketAddress ia) || !(b instanceof InetSocketAddress ib)) {
            // 非 inet 地址（例如 LocalSession 的本地通道）只能按对象相等判断
            return a.equals(b);
        }
        InetAddress addrA = ia.getAddress();
        InetAddress addrB = ib.getAddress();
        if (addrA != null && addrB != null) {
            if (!addrA.equals(addrB)) {
                // 容忍 127.0.0.1 与 0:0:0:0:0:0:0:1 同时表示回环
                if (!(addrA.isLoopbackAddress() && addrB.isLoopbackAddress())) {
                    return false;
                }
            }
        } else if (addrA == null && addrB == null) {
            if (!ia.getHostString().equalsIgnoreCase(ib.getHostString())) {
                return false;
            }
        } else {
            return false;
        }
        return ia.getPort() == ib.getPort();
    }

    /** 诊断用：只输出地址与端口，不带主机名以外的信息。 */
    public static String describe(SocketAddress address) {
        if (address == null) {
            return "null";
        }
        if (address instanceof InetSocketAddress inet) {
            InetAddress addr = inet.getAddress();
            return (addr == null ? inet.getHostString() : addr.getHostAddress()) + ":" + inet.getPort();
        }
        return address.toString();
    }
}
