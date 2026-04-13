package com.stellarideas.grooves.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryRateLimitStoreTest {

    private InMemoryRateLimitStore store;
    private static final long WINDOW_MS = 60000;

    @BeforeEach
    void setUp() {
        store = new InMemoryRateLimitStore();
    }

    @Test
    void incrementStartsAtOne() {
        assertEquals(1, store.incrementAndGet("key1", WINDOW_MS));
    }

    @Test
    void incrementsSequentially() {
        assertEquals(1, store.incrementAndGet("key1", WINDOW_MS));
        assertEquals(2, store.incrementAndGet("key1", WINDOW_MS));
        assertEquals(3, store.incrementAndGet("key1", WINDOW_MS));
    }

    @Test
    void separateKeysTrackedIndependently() {
        assertEquals(1, store.incrementAndGet("key1", WINDOW_MS));
        assertEquals(1, store.incrementAndGet("key2", WINDOW_MS));
        assertEquals(2, store.incrementAndGet("key1", WINDOW_MS));
    }

    @Test
    void secondsUntilResetPositiveAfterIncrement() {
        store.incrementAndGet("key1", WINDOW_MS);
        long remaining = store.secondsUntilReset("key1", WINDOW_MS);
        assertTrue(remaining > 0);
        assertTrue(remaining <= 60);
    }

    @Test
    void secondsUntilResetZeroForUnknownKey() {
        assertEquals(0, store.secondsUntilReset("unknown", WINDOW_MS));
    }

    @Test
    void windowResetsAfterExpiry() {
        // Use a very short window
        assertEquals(1, store.incrementAndGet("key1", 1));
        // After sleeping past the window, count should reset
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
        assertEquals(1, store.incrementAndGet("key1", 1));
    }
}
