package dev.connectplus.geyserbridge.util;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddressUtilTest {

    @Test
    void matchesExactIpAndPort() {
        InetSocketAddress a = new InetSocketAddress("127.0.0.1", 50001);
        InetSocketAddress b = new InetSocketAddress("127.0.0.1", 50001);
        assertTrue(AddressUtil.equalsAddress(a, b));
    }

    @Test
    void portMismatchFails() {
        InetSocketAddress a = new InetSocketAddress("127.0.0.1", 50001);
        InetSocketAddress b = new InetSocketAddress("127.0.0.1", 50002);
        assertFalse(AddressUtil.equalsAddress(a, b));
    }

    @Test
    void differentHostsFail() {
        InetSocketAddress a = new InetSocketAddress("127.0.0.1", 50001);
        InetSocketAddress b = new InetSocketAddress("10.0.0.5", 50001);
        assertFalse(AddressUtil.equalsAddress(a, b));
    }

    @Test
    void loopbackToleratesIpv4VsIpv6() {
        // Geyser 下游连 "localhost" 时可能解析为 ::1，而 c2p 端看到 127.0.0.1
        InetSocketAddress v4 = new InetSocketAddress("127.0.0.1", 1234);
        InetSocketAddress v6 = new InetSocketAddress("::1", 1234);
        assertTrue(AddressUtil.equalsAddress(v4, v6));
    }

    @Test
    void nonLoopbackIpv4VsIpv6Fail() {
        InetSocketAddress v4 = new InetSocketAddress("192.168.1.2", 1234);
        InetSocketAddress v6 = new InetSocketAddress("fe80::1", 1234);
        assertFalse(AddressUtil.equalsAddress(v4, v6));
    }

    @Test
    void unresolvedHostnamesCompareByName() {
        InetSocketAddress a = InetSocketAddress.createUnresolved("ViaProxy.local", 1234);
        InetSocketAddress b = InetSocketAddress.createUnresolved("viaproxy.LOCAL", 1234);
        assertTrue(AddressUtil.equalsAddress(a, b));
        InetSocketAddress c = InetSocketAddress.createUnresolved("other.local", 1234);
        assertFalse(AddressUtil.equalsAddress(a, c));
    }

    @Test
    void nullIsNeverAMatch() {
        InetSocketAddress a = new InetSocketAddress("127.0.0.1", 1234);
        assertFalse(AddressUtil.equalsAddress(null, a));
        assertFalse(AddressUtil.equalsAddress(a, null));
    }

    @Test
    void connectionRequiresBothOriginalTcpEndpoints() {
        InetSocketAddress proxy = new InetSocketAddress("127.0.0.1", 25571);
        InetSocketAddress client = new InetSocketAddress("127.0.0.1", 50123);
        assertTrue(AddressUtil.matchesConnection(proxy, client, client, proxy));
        assertFalse(AddressUtil.matchesConnection(proxy, client, client, null));
        assertFalse(AddressUtil.matchesConnection(null, client, client, proxy));
        assertFalse(AddressUtil.matchesConnection(proxy, null, client, proxy));
        assertFalse(AddressUtil.matchesConnection(proxy, client, null, proxy));
        assertFalse(AddressUtil.matchesConnection(proxy, client, client,
                new InetSocketAddress("127.0.0.1", 25572)));
        assertFalse(AddressUtil.matchesConnection(proxy, client,
                new InetSocketAddress("127.0.0.1", 50124), proxy));
    }
}
