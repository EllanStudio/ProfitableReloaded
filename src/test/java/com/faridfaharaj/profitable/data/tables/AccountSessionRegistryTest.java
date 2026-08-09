package com.faridfaharaj.profitable.data.tables;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccountSessionRegistryTest {

    @Test
    void defaultActivationIsIdempotent() {
        AccountSessionRegistry registry = new AccountSessionRegistry();
        UUID playerId = UUID.randomUUID();
        AtomicInteger factoryCalls = new AtomicInteger();

        String first = registry.computeIfAbsent(playerId, ignored -> {
            factoryCalls.incrementAndGet();
            return "default";
        });
        String second = registry.computeIfAbsent(playerId, ignored -> {
            factoryCalls.incrementAndGet();
            return "other";
        });

        assertEquals("default", first);
        assertEquals("default", second);
        assertEquals(1, factoryCalls.get());
    }

    @Test
    void logoutRemovesExactlyTheActiveSession() {
        AccountSessionRegistry registry = new AccountSessionRegistry();
        UUID playerId = UUID.randomUUID();

        assertNull(registry.put(playerId, "trading"));
        assertEquals("trading", registry.remove(playerId));
        assertNull(registry.remove(playerId));
        assertEquals(0, registry.view().size());
    }

    @Test
    void exposedSessionViewCannotBeMutated() {
        AccountSessionRegistry registry = new AccountSessionRegistry();
        assertThrows(UnsupportedOperationException.class,
                () -> registry.view().put(UUID.randomUUID(), "unexpected"));
    }
}
