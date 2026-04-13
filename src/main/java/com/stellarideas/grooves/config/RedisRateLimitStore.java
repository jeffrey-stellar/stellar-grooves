package com.stellarideas.grooves.config;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;

/**
 * Redis-backed rate-limit store for distributed deployments.
 * Uses a Lua script for atomic increment + TTL to avoid race conditions.
 */
public class RedisRateLimitStore implements RateLimitStore {

    private static final String KEY_PREFIX = "ratelimit:";

    /**
     * Lua script: atomically increment a counter and set TTL on first creation.
     * Returns the current count after increment.
     */
    private static final String INCREMENT_SCRIPT =
            "local key = KEYS[1] " +
            "local window = tonumber(ARGV[1]) " +
            "local current = redis.call('incr', key) " +
            "if current == 1 then " +
            "  redis.call('pexpire', key, window) " +
            "end " +
            "return current";

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> script;

    public RedisRateLimitStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.script = new DefaultRedisScript<>(INCREMENT_SCRIPT, Long.class);
    }

    @Override
    public int incrementAndGet(String key, long windowMs) {
        String redisKey = KEY_PREFIX + key;
        Long count = redisTemplate.execute(script,
                Collections.singletonList(redisKey),
                String.valueOf(windowMs));
        return count != null ? count.intValue() : 1;
    }

    @Override
    public long secondsUntilReset(String key, long windowMs) {
        String redisKey = KEY_PREFIX + key;
        Long ttl = redisTemplate.getExpire(redisKey);
        if (ttl == null || ttl <= 0) {
            return 1;
        }
        return ttl;
    }
}
