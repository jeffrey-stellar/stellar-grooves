package com.stellarideas.grooves.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/**
 * Per-user cache for the library stats payload. Stats run a single (but
 * non-trivial) {@code $facet} aggregation across the user's whole library, and
 * the dashboard hits this on every load — caching it avoids redundant work for
 * common short-burst navigation.
 *
 * <p>Entries are invalidated explicitly on write paths (genre/rating edits,
 * deletes, restores, scan completion) and as a backstop expire 60 seconds
 * after write so any miss in the invalidation map self-heals.
 */
@Component
public class LibraryStatsCache {

    private final Cache<String, Map<String, Object>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(10_000)
            .build();

    public Map<String, Object> get(String userId, Function<String, Map<String, Object>> loader) {
        if (userId == null) return loader.apply(null);
        return cache.get(userId, loader);
    }

    public void invalidate(String userId) {
        if (userId == null) return;
        cache.invalidate(userId);
    }
}
