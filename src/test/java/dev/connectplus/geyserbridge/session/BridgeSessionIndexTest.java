package dev.connectplus.geyserbridge.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeSessionIndexTest {

    private static final String EPOCH = "epoch-1";

    @Test
    void registerIsIdempotentPerSessionObject() {
        BridgeSessionIndex index = new BridgeSessionIndex();
        Object session = new Object();
        BridgeSession first = index.register(EPOCH, session);
        BridgeSession second = index.register(EPOCH, session);
        assertSame(first, second);
        assertEquals(1, index.activeCount());
    }

    @Test
    void differentSessionsGetDifferentIds() {
        BridgeSessionIndex index = new BridgeSessionIndex();
        BridgeSession a = index.register(EPOCH, new Object());
        BridgeSession b = index.register(EPOCH, new Object());
        assertNotEquals(a.bridgeSessionId(), b.bridgeSessionId());
    }

    @Test
    void closeRemovesFromAllIndexesAndRecordsTombstone() {
        BridgeSessionIndex index = new BridgeSessionIndex();
        Object session = new Object();
        BridgeSession bridge = index.register(EPOCH, session);
        String id = bridge.bridgeSessionId();
        index.bindXuid(bridge, "1234");
        bridge.setConnectionIdIfAbsent("conn-1");
        bridge.bindChannel(new Object());

        index.close(bridge);

        assertNull(index.byId(id));
        assertNull(index.bySession(session));
        assertEquals(List.of(), index.activeByXuid("1234"));
        assertTrue(index.wasClosedOnce(id));
        assertFalse(bridge.isManaged() && index.byId(id) != null);
    }

    @Test
    void activeByXuidExcludesClosedSessions() {
        BridgeSessionIndex index = new BridgeSessionIndex();
        BridgeSession first = index.register(EPOCH, new Object());
        BridgeSession second = index.register(EPOCH, new Object());
        index.bindXuid(first, "42");
        index.bindXuid(second, "42");

        assertEquals(2, index.activeByXuid("42").size());
        index.close(first);
        assertEquals(1, index.activeByXuid("42").size());
        assertSame(second, index.activeByXuid("42").get(0));
    }

    @Test
    void channelBindingIsSingleShot() {
        BridgeSessionIndex index = new BridgeSessionIndex();
        BridgeSession bridge = index.register(EPOCH, new Object());
        Object channelA = new Object();
        Object channelB = new Object();

        assertTrue(bridge.bindChannel(channelA));
        assertTrue(bridge.bindChannel(channelA)); // 同一对象重复绑定 OK
        assertFalse(bridge.bindChannel(channelB)); // 拒绝转移
    }

    @Test
    void connectionIdIsSingleShot() {
        BridgeSessionIndex index = new BridgeSessionIndex();
        BridgeSession bridge = index.register(EPOCH, new Object());

        assertTrue(bridge.setConnectionIdIfAbsent("c1"));
        assertTrue(bridge.setConnectionIdIfAbsent("c1"));
        assertFalse(bridge.setConnectionIdIfAbsent("c2"));
        assertEquals("c1", bridge.connectionId());
    }

    @Test
    void clearAllEmptiesEverything() {
        BridgeSessionIndex index = new BridgeSessionIndex();
        BridgeSession bridge = index.register(EPOCH, new Object());
        index.bindXuid(bridge, "7");

        index.clearAll();

        assertEquals(0, index.activeCount());
        assertNull(index.byId(bridge.bridgeSessionId()));
        assertNotNull(bridge); // 对象本身仍在，但已退出索引
        assertTrue(bridge.isClosed());
    }
}
