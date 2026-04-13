package com.stellarideas.grooves.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user rate limiter for directory scan operations.
 * Enforces a configurable cooldown between scans to prevent resource exhaustion.
 */
@Service
public class ScanRateLimiter {

    @Value("${stellar.grooves.scan.cooldownSeconds:60}")
    private long cooldownSeconds;

    private final ConcurrentHashMap<String, Long> lastScanTime = new ConcurrentHashMap<>();

    /**
     * Check if the user is allowed to start a new scan.
     *
     * @param userId the user's ID
     * @return true if enough time has elapsed since the last scan
     */
    public boolean tryAcquire(String userId) {
        long now = System.currentTimeMillis();
        Long last = lastScanTime.get(userId);
        if (last != null && (now - last) < cooldownSeconds * 1000) {
            return false;
        }
        lastScanTime.put(userId, now);
        return true;
    }

    /**
     * Return seconds remaining before the user can scan again.
     */
    public long secondsUntilAllowed(String userId) {
        Long last = lastScanTime.get(userId);
        if (last == null) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - last;
        long remaining = cooldownSeconds - (elapsed / 1000);
        return Math.max(0, remaining);
    }
}
