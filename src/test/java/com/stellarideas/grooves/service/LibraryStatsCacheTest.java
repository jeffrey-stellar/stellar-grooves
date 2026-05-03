package com.stellarideas.grooves.service;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class LibraryStatsCacheTest {

    @Test
    void firstCallLoadsFromLoader() {
        LibraryStatsCache cache = new LibraryStatsCache();
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> stats = cache.get("u1", id -> {
            calls.incrementAndGet();
            return Map.of("totalTracks", 10L);
        });
        assertEquals(10L, stats.get("totalTracks"));
        assertEquals(1, calls.get());
    }

    @Test
    void secondCallSkipsLoaderAndReturnsCachedInstance() {
        LibraryStatsCache cache = new LibraryStatsCache();
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> first = cache.get("u1", id -> {
            calls.incrementAndGet();
            return Map.of("totalTracks", 10L);
        });
        Map<String, Object> second = cache.get("u1", id -> {
            calls.incrementAndGet();
            return Map.of("totalTracks", 999L);
        });
        assertEquals(1, calls.get(), "Second get should not invoke the loader");
        assertSame(first, second);
    }

    @Test
    void invalidateForcesReload() {
        LibraryStatsCache cache = new LibraryStatsCache();
        AtomicInteger calls = new AtomicInteger();
        cache.get("u1", id -> { calls.incrementAndGet(); return Map.of("v", 1); });
        cache.invalidate("u1");
        cache.get("u1", id -> { calls.incrementAndGet(); return Map.of("v", 2); });
        assertEquals(2, calls.get());
    }

    @Test
    void differentUsersAreIsolated() {
        LibraryStatsCache cache = new LibraryStatsCache();
        Map<String, Object> a = cache.get("a", id -> Map.of("user", "a"));
        Map<String, Object> b = cache.get("b", id -> Map.of("user", "b"));
        assertEquals("a", a.get("user"));
        assertEquals("b", b.get("user"));
    }

    @Test
    void invalidateWithNullUserIsNoOp() {
        LibraryStatsCache cache = new LibraryStatsCache();
        cache.invalidate(null); // must not throw
    }

    @Test
    void getWithNullUserBypassesCache() {
        LibraryStatsCache cache = new LibraryStatsCache();
        AtomicInteger calls = new AtomicInteger();
        cache.get(null, id -> { calls.incrementAndGet(); return Map.of(); });
        cache.get(null, id -> { calls.incrementAndGet(); return Map.of(); });
        assertEquals(2, calls.get(), "Null userId should not be cached");
    }
}
