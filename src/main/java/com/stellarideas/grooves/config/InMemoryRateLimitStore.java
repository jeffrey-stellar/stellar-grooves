package com.stellarideas.grooves.config;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory rate-limit store using a sliding time window per key.
 * Suitable for single-instance deployments.
 */
public class InMemoryRateLimitStore implements RateLimitStore {

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    @Override
    public int incrementAndGet(String key, long windowMs) {
        long now = System.currentTimeMillis();
        evictStaleEntries(now, windowMs);

        WindowCounter counter = counters.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStart > windowMs) {
                return new WindowCounter(now);
            }
            return existing;
        });
        return counter.count.incrementAndGet();
    }

    @Override
    public long secondsUntilReset(String key, long windowMs) {
        WindowCounter counter = counters.get(key);
        if (counter == null) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - counter.windowStart;
        return Math.max(1, (windowMs - elapsed) / 1000);
    }

    private void evictStaleEntries(long now, long windowMs) {
        Iterator<Map.Entry<String, WindowCounter>> it = counters.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, WindowCounter> entry = it.next();
            if (now - entry.getValue().windowStart > windowMs * 2) {
                it.remove();
            }
        }
    }

    private static class WindowCounter {
        final long windowStart;
        final AtomicInteger count = new AtomicInteger(0);

        WindowCounter(long windowStart) {
            this.windowStart = windowStart;
        }
    }
}
