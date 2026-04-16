package com.stellarideas.grooves.config;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory rate-limit store using a sliding counter with gradual decay.
 *
 * <p>Unlike a fixed-window counter (which resets abruptly and allows burst
 * behavior at window boundaries), this implementation decays the counter
 * proportionally to elapsed time. A request that would have been allowed
 * at second 61 of a 60s window under fixed-window is properly counted
 * against the prior requests here.</p>
 *
 * <p>Suitable for single-instance deployments.</p>
 */
public class InMemoryRateLimitStore implements RateLimitStore {

    private final ConcurrentHashMap<String, SlidingCounter> counters = new ConcurrentHashMap<>();

    @Override
    public int incrementAndGet(String key, long windowMs) {
        long now = System.currentTimeMillis();
        evictStaleEntries(now, windowMs);

        SlidingCounter counter = counters.compute(key, (k, existing) -> {
            if (existing == null) {
                return new SlidingCounter(now);
            }
            existing.decay(now, windowMs);
            return existing;
        });

        counter.count += 1.0;
        counter.lastAccess = now;
        return (int) Math.ceil(counter.count);
    }

    @Override
    public long secondsUntilReset(String key, long windowMs) {
        SlidingCounter counter = counters.get(key);
        if (counter == null) {
            return 0;
        }
        // Estimate when the counter will decay below the threshold
        // Since we don't know the limit here, return time for one unit to decay
        long elapsed = System.currentTimeMillis() - counter.lastAccess;
        long remaining = windowMs - elapsed;
        return Math.max(1, remaining / 1000);
    }

    private void evictStaleEntries(long now, long windowMs) {
        Iterator<Map.Entry<String, SlidingCounter>> it = counters.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, SlidingCounter> entry = it.next();
            if (now - entry.getValue().lastAccess > windowMs * 2) {
                it.remove();
            }
        }
    }

    static class SlidingCounter {
        double count;
        long lastAccess;

        SlidingCounter(long now) {
            this.count = 0;
            this.lastAccess = now;
        }

        /**
         * Decay the counter based on elapsed time. If a full window has passed,
         * the counter resets to 0. For partial windows, the counter decays
         * linearly — e.g., after half a window, half the count remains.
         */
        void decay(long now, long windowMs) {
            long elapsed = now - lastAccess;
            if (elapsed <= 0) return;
            if (elapsed >= windowMs) {
                count = 0;
            } else {
                double decayFactor = 1.0 - ((double) elapsed / windowMs);
                count *= decayFactor;
            }
        }
    }
}
