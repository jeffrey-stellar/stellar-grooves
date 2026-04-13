package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.dto.SignupRequest;
import com.stellarideas.grooves.repository.UserRepository;
import com.stellarideas.grooves.security.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests that concurrent signup requests for the same username
 * are handled correctly (at most one succeeds at the service layer).
 */
class AuthConcurrencyTest {

    private AuthController controller;
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        JwtUtils jwtUtils = mock(JwtUtils.class);
        controller = new AuthController(authenticationManager, userRepository, passwordEncoder, jwtUtils);

        when(passwordEncoder.encode(any())).thenReturn("encoded");
    }

    @Test
    void concurrentSignupsForSameUsernameShouldNotAllSucceed() throws Exception {
        // Simulate a race: first call sees no duplicate, subsequent calls see the username taken.
        // Use an atomic counter to flip the mock after the first check.
        AtomicInteger checkCount = new AtomicInteger(0);
        when(userRepository.existsByUsername("raceuser")).thenAnswer(invocation -> {
            // First caller sees false (no duplicate), all others see true
            return checkCount.getAndIncrement() > 0;
        });
        when(userRepository.existsByEmail("race@test.com")).thenReturn(false);

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<ResponseEntity<?>>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                latch.await(); // all threads start together
                SignupRequest req = new SignupRequest();
                req.setUsername("raceuser");
                req.setEmail("race@test.com");
                req.setPassword("password123");
                return controller.registerUser(req);
            }));
        }

        latch.countDown(); // release all threads

        int successCount = 0;
        int failCount = 0;
        for (Future<ResponseEntity<?>> future : futures) {
            ResponseEntity<?> response = future.get();
            if (response.getStatusCode().value() == 200) {
                successCount++;
            } else {
                failCount++;
            }
        }
        executor.shutdown();

        // At most one should succeed (the first one past the check)
        assertEquals(1, successCount, "Exactly one concurrent signup should succeed");
        assertEquals(threadCount - 1, failCount, "All other concurrent signups should be rejected");
    }
}
