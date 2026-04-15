package com.stellarideas.grooves.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RateLimitFilterTest {

    private RateLimitFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter(new InMemoryRateLimitStore(), new SimpleMeterRegistry());
        ReflectionTestUtils.setField(filter, "maxRequests", 3);
        ReflectionTestUtils.setField(filter, "windowMs", 60000L);
        ReflectionTestUtils.setField(filter, "sharedMaxRequests", 2);
        ReflectionTestUtils.setField(filter, "sharedWindowMs", 60000L);
        ReflectionTestUtils.setField(filter, "trustProxy", false);
        ReflectionTestUtils.setField(filter, "trustedProxies", List.of());
        chain = mock(FilterChain.class);
    }

    @Test
    void allowsRequestsUnderLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest req = authRequest("192.168.1.1");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilterInternal(req, resp, chain);
            assertNotEquals(429, resp.getStatus());
        }
        verify(chain, times(3)).doFilter(any(), any());
    }

    @Test
    void blocksRequestsOverLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            filter.doFilterInternal(authRequest("10.0.0.1"), new MockHttpServletResponse(), chain);
        }

        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilterInternal(authRequest("10.0.0.1"), resp, chain);
        assertEquals(429, resp.getStatus());
    }

    @Test
    void ignoresForwardedForWhenProxyNotTrusted() throws Exception {
        MockHttpServletRequest req = authRequest("10.0.0.1");
        req.addHeader("X-Forwarded-For", "1.2.3.4");

        for (int i = 0; i < 3; i++) {
            filter.doFilterInternal(authRequest("10.0.0.1"), new MockHttpServletResponse(), chain);
        }

        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilterInternal(req, resp, chain);
        assertEquals(429, resp.getStatus());
    }

    @Test
    void respectsForwardedForWhenProxyTrusted() throws Exception {
        ReflectionTestUtils.setField(filter, "trustProxy", true);
        ReflectionTestUtils.setField(filter, "trustedProxies", List.of("10.0.0.1"));

        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest req = authRequest("10.0.0.1");
            req.addHeader("X-Forwarded-For", "1.2.3.4");
            filter.doFilterInternal(req, new MockHttpServletResponse(), chain);
        }

        MockHttpServletRequest req = authRequest("10.0.0.1");
        req.addHeader("X-Forwarded-For", "5.6.7.8");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilterInternal(req, resp, chain);
        assertNotEquals(429, resp.getStatus());
    }

    @Test
    void skipsNonAuthAndNonSharedEndpoints() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/library/files");
        req.setRemoteAddr("10.0.0.1");
        assertTrue(filter.shouldNotFilter(req));
    }

    @Test
    void filtersAuthEndpoints() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/signin");
        req.setRemoteAddr("10.0.0.1");
        assertFalse(filter.shouldNotFilter(req));
    }

    @Test
    void filtersSharedEndpoints() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/shared/playlists/abc123");
        req.setRemoteAddr("10.0.0.1");
        assertFalse(filter.shouldNotFilter(req));
    }

    @Test
    void blocksSharedRequestsOverLimit() throws Exception {
        // sharedMaxRequests is 2
        for (int i = 0; i < 2; i++) {
            filter.doFilterInternal(sharedRequest("10.0.0.1"), new MockHttpServletResponse(), chain);
        }

        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilterInternal(sharedRequest("10.0.0.1"), resp, chain);
        assertEquals(429, resp.getStatus());
    }

    @Test
    void sharedAndAuthUseSeparateBuckets() throws Exception {
        // Exhaust the shared limit (2 requests)
        for (int i = 0; i < 2; i++) {
            filter.doFilterInternal(sharedRequest("10.0.0.1"), new MockHttpServletResponse(), chain);
        }
        MockHttpServletResponse sharedResp = new MockHttpServletResponse();
        filter.doFilterInternal(sharedRequest("10.0.0.1"), sharedResp, chain);
        assertEquals(429, sharedResp.getStatus());

        // Auth requests from same IP should still be allowed (separate bucket)
        MockHttpServletResponse authResp = new MockHttpServletResponse();
        filter.doFilterInternal(authRequest("10.0.0.1"), authResp, chain);
        assertNotEquals(429, authResp.getStatus());
    }

    @Test
    void separateCountersPerIp() throws Exception {
        for (int i = 0; i < 3; i++) {
            filter.doFilterInternal(authRequest("10.0.0.1"), new MockHttpServletResponse(), chain);
        }

        // Different IP should still be allowed
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilterInternal(authRequest("10.0.0.2"), resp, chain);
        assertNotEquals(429, resp.getStatus());
    }

    @Test
    void retryAfterHeaderPresent() throws Exception {
        for (int i = 0; i < 3; i++) {
            filter.doFilterInternal(authRequest("10.0.0.1"), new MockHttpServletResponse(), chain);
        }

        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilterInternal(authRequest("10.0.0.1"), resp, chain);
        assertEquals(429, resp.getStatus());
        assertNotNull(resp.getHeader("Retry-After"));
    }

    @Test
    void rejectsInvalidIpInForwardedFor() throws Exception {
        ReflectionTestUtils.setField(filter, "trustProxy", true);
        ReflectionTestUtils.setField(filter, "trustedProxies", List.of("10.0.0.1"));

        // Malformed IP should be skipped — falls back to remoteAddr
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest req = authRequest("10.0.0.1");
            req.addHeader("X-Forwarded-For", "not-an-ip, 10.0.0.1");
            filter.doFilterInternal(req, new MockHttpServletResponse(), chain);
        }

        // Should be rate-limited on remoteAddr (10.0.0.1) since forwarded IPs were invalid/trusted
        MockHttpServletRequest req = authRequest("10.0.0.1");
        req.addHeader("X-Forwarded-For", ":::invalid:::, 10.0.0.1");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilterInternal(req, resp, chain);
        assertEquals(429, resp.getStatus());
    }

    @Test
    void acceptsValidIpv6InForwardedFor() throws Exception {
        ReflectionTestUtils.setField(filter, "trustProxy", true);
        ReflectionTestUtils.setField(filter, "trustedProxies", List.of("10.0.0.1"));

        MockHttpServletRequest req = authRequest("10.0.0.1");
        req.addHeader("X-Forwarded-For", "::1, 10.0.0.1");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilterInternal(req, resp, chain);
        assertNotEquals(429, resp.getStatus());
    }

    private MockHttpServletRequest authRequest(String remoteAddr) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/signin");
        req.setRemoteAddr(remoteAddr);
        return req;
    }

    private MockHttpServletRequest sharedRequest(String remoteAddr) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/shared/playlists/token123");
        req.setRemoteAddr(remoteAddr);
        return req;
    }
}
