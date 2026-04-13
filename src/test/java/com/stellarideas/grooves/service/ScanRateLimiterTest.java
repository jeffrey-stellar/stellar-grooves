package com.stellarideas.grooves.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class ScanRateLimiterTest {

    private ScanRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new ScanRateLimiter();
        ReflectionTestUtils.setField(limiter, "cooldownSeconds", 60L);
    }

    @Test
    void firstScanAllowed() {
        assertTrue(limiter.tryAcquire("user1"));
    }

    @Test
    void secondImmediateScanBlocked() {
        assertTrue(limiter.tryAcquire("user1"));
        assertFalse(limiter.tryAcquire("user1"));
    }

    @Test
    void differentUsersIndependent() {
        assertTrue(limiter.tryAcquire("user1"));
        assertTrue(limiter.tryAcquire("user2"));
    }

    @Test
    void secondsUntilAllowedReturnsPositive() {
        limiter.tryAcquire("user1");
        long remaining = limiter.secondsUntilAllowed("user1");
        assertTrue(remaining > 0);
        assertTrue(remaining <= 60);
    }

    @Test
    void secondsUntilAllowedZeroForNewUser() {
        assertEquals(0, limiter.secondsUntilAllowed("unknown"));
    }

    @Test
    void allowedAfterCooldown() {
        ReflectionTestUtils.setField(limiter, "cooldownSeconds", 0L);
        assertTrue(limiter.tryAcquire("user1"));
        assertTrue(limiter.tryAcquire("user1"));
    }
}
