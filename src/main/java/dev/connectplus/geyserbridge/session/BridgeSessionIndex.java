package dev.connectplus.geyserbridge.session;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 bridgeSessionId 管理活跃桥接会话（协议第 5 节）：
 * 关闭即清理，不按 XUID 永久缓存 Channel，不把已关闭对象留作重连依据。
 * 线程安全性：注册/查询来自 Geyser 事件与 handler 两个线程族，全部走并发容器。
 */
public final class BridgeSessionIndex {

    private final Map<String, BridgeSession> byId = new ConcurrentHashMap<>();
    private final Map<String, List<BridgeSession>> byXuid = new ConcurrentHashMap<>();
    private final Map<Object, BridgeSession> bySession = java.util.Collections.synchronizedMap(new IdentityHashMap<>());
    /** 最近关闭过的 bridgeSessionId，用于 DISCONNECT 区分 ALREADY_CLOSED 与未知 id。 */
    private final java.util.Set<String> closedTombstones = ConcurrentHashMap.newKeySet();

    /** 注册一个尚未认证完成的会话；session 对象必须此前未注册过。 */
    public BridgeSession register(String providerEpoch, Object session) {
        BridgeSession created = new BridgeSession(providerEpoch, session);
        BridgeSession previous = bySession.putIfAbsent(session, created);
        if (previous != null) {
            return previous;
        }
        byId.put(created.bridgeSessionId(), created);
        return created;
    }

    public BridgeSession byId(String bridgeSessionId) {
        if (bridgeSessionId == null) {
            return null;
        }
        BridgeSession session = byId.get(bridgeSessionId);
        return session == null || session.isClosed() ? null : session;
    }

    /** @return 活跃的、与该 Geyser 会话对象对应的档案；无则 null。 */
    public BridgeSession bySession(Object session) {
        if (session == null) {
            return null;
        }
        BridgeSession found = bySession.get(session);
        return found == null || found.isClosed() ? null : found;
    }

    /** @return 该 XUID 下所有活跃会话（含尚未 RESOLVE 的）。 */
    public List<BridgeSession> activeByXuid(String xuid) {
        if (xuid == null) {
            return List.of();
        }
        List<BridgeSession> sessions = byXuid.get(xuid);
        if (sessions == null) {
            return List.of();
        }
        List<BridgeSession> active = new ArrayList<>();
        for (BridgeSession session : List.copyOf(sessions)) {
            if (!session.isClosed()) {
                active.add(session);
            }
        }
        return active;
    }

    public void bindXuid(BridgeSession session, String xuid) {
        byXuid.compute(xuid, (key, current) -> {
            List<BridgeSession> list = current == null ? new ArrayList<>() : current;
            if (!list.contains(session)) {
                list.add(session);
            }
            return list;
        });
    }

    public void close(BridgeSession session) {
        if (session == null || session.isClosed()) {
            return;
        }
        session.markClosed();
        byId.remove(session.bridgeSessionId(), session);
        bySession.remove(session.session(), session);
        String xuid = session.xuid();
        if (xuid != null) {
            byXuid.computeIfPresent(xuid, (key, list) -> {
                list.remove(session);
                return list.isEmpty() ? null : list;
            });
        }
        if (closedTombstones.size() > 8192) {
            closedTombstones.clear();
        }
        closedTombstones.add(session.bridgeSessionId());
    }

    /** @return true 表示该 id 属于一个确实存在过、现已关闭的会话。 */
    public boolean wasClosedOnce(String bridgeSessionId) {
        return bridgeSessionId != null && closedTombstones.contains(bridgeSessionId);
    }

    /** 收尾清理：移除所有会话（停用/换代时调用）。 */
    public void clearAll() {
        for (BridgeSession session : List.copyOf(byId.values())) {
            close(session);
        }
        byXuid.clear();
        closedTombstones.clear();
        synchronized (bySession) {
            bySession.clear();
        }
    }

    public int activeCount() {
        int count = 0;
        for (BridgeSession session : byId.values()) {
            if (!session.isClosed()) {
                count++;
            }
        }
        return count;
    }

    /** 诊断用活跃会话遍历。 */
    public Iterable<BridgeSession> activeSessions() {
        List<BridgeSession> active = new ArrayList<>();
        for (Map.Entry<String, BridgeSession> e : byId.entrySet()) {
            if (!e.getValue().isClosed()) {
                active.add(e.getValue());
            }
        }
        return active;
    }

    /** 清理已关闭但仍在 byXuid 中残留的空列表。 */
    public void sweep() {
        Iterator<Map.Entry<String, List<BridgeSession>>> it = byXuid.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, List<BridgeSession>> entry = it.next();
            entry.getValue().removeIf(BridgeSession::isClosed);
            if (entry.getValue().isEmpty()) {
                it.remove();
            }
        }
    }
}
