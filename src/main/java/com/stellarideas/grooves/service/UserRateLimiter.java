package com.stellarideas.grooves.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-user rate limiter for resource-heavy API operations (export, bulk delete, backup/restore).
 * Uses a sliding window per user+operation key.
 */
@Service
public class UserRateLimiter {

    @Value("${stellar.grooves.userRateLimit.maxRequests:10}")
    private int maxRequests;

    @Value("${stellar.grooves.userRateLimit.windowSeconds:60}")
    private long windowSeconds;

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    /**
     * Check if a user is allowed to perform the given operation.
     * @param userId the user ID
     * @param operation the operation key (e.g. "export", "bulk-delete", "backup")
     * @return true if allowed
     */
    public boolean tryAcquire(String userId, String operation) {
        String key = userId + ":" + operation;
        WindowCounter counter = counters.computeIfAbsent(key, k -> new WindowCounter());
        return counter.tryIncrement(maxRequests, windowSeconds * 1000);
    }

    public long secondsUntilAllowed(String userId, String operation) {
        String key = userId + ":" + operation;
        WindowCounter counter = counters.get(key);
        if (counter == null) return 0;
        long elapsed = System.currentTimeMillis() - counter.windowStart;
        long remaining = windowSeconds - (elapsed / 1000);
        return Math.max(0, remaining);
    }

    @Scheduled(fixedRate = 300_000) // every 5 minutes
    void evictStaleEntries() {
        long cutoff = System.currentTimeMillis() - (windowSeconds * 1000 * 2);
        counters.entrySet().removeIf(entry -> entry.getValue().windowStart < cutoff);
    }

    private static class WindowCounter {
        volatile long windowStart = System.currentTimeMillis();
        final AtomicInteger count = new AtomicInteger(0);

        boolean tryIncrement(int maxRequests, long windowMs) {
            long now = System.currentTimeMillis();
            if (now - windowStart > windowMs) {
                // Reset window
                windowStart = now;
                count.set(1);
                return true;
            }
            return count.incrementAndGet() <= maxRequests;
        }
    }
}
