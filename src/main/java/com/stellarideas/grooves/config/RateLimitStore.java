package com.stellarideas.grooves.config;

/**
 * Abstraction for rate-limit counters.
 * Default implementation is in-memory; switch to Redis for multi-instance deployments.
 */
public interface RateLimitStore {

    /**
     * Increment the request count for the given key within the current time window.
     *
     * @param key       identifier (e.g. client IP)
     * @param windowMs  sliding window duration in milliseconds
     * @return current request count after increment
     */
    int incrementAndGet(String key, long windowMs);

    /**
     * Return the number of seconds remaining in the current window for the given key.
     * Used for Retry-After header.
     */
    long secondsUntilReset(String key, long windowMs);
}
