package com.faridfaharaj.profitable.data.tables;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Thread-safe local account selections for players connected to this backend.
 *
 * <p>The registry deliberately contains no Bukkit or Redis calls, which keeps
 * session lifecycle rules deterministic and independently testable.</p>
 */
public final class AccountSessionRegistry {

    private final ConcurrentHashMap<UUID, String> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, String> readOnlySessions = Collections.unmodifiableMap(sessions);

    public String computeIfAbsent(UUID playerId, Function<UUID, String> accountFactory) {
        return sessions.computeIfAbsent(playerId, accountFactory);
    }

    public String putIfAbsent(UUID playerId, String account) {
        return sessions.putIfAbsent(playerId, account);
    }

    public String put(UUID playerId, String account) {
        return sessions.put(playerId, account);
    }

    public String remove(UUID playerId) {
        return sessions.remove(playerId);
    }

    public Map<UUID, String> view() {
        return readOnlySessions;
    }

    public void clear() {
        sessions.clear();
    }
}
