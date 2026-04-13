package com.stellarideas.grooves.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RateLimitFilterTest {

    private RateLimitFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        ReflectionTestUtils.setField(filter, "maxRequests", 3);
        ReflectionTestUtils.setField(filter, "windowMs", 60000L);
        ReflectionTestUtils.setField(filter, "trustProxy", false);
        ReflectionTestUtils.setField(filter, "trustedProxies", java.util.List.of());
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
        // Exhaust the limit
        for (int i = 0; i < 3; i++) {
            filter.doFilterInternal(authRequest("10.0.0.1"), new MockHttpServletResponse(), chain);
        }

        // This one should be blocked
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilterInternal(authRequest("10.0.0.1"), resp, chain);
        assertEquals(429, resp.getStatus());
    }

    @Test
    void ignoresForwardedForWhenProxyNotTrusted() throws Exception {
        // trustProxy is false, so X-Forwarded-For should be ignored
        MockHttpServletRequest req = authRequest("10.0.0.1");
        req.addHeader("X-Forwarded-For", "1.2.3.4");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        // Exhaust limit for remote addr 10.0.0.1
        for (int i = 0; i < 3; i++) {
            filter.doFilterInternal(authRequest("10.0.0.1"), new MockHttpServletResponse(), chain);
        }

        // Even with different X-Forwarded-For, still blocked because trustProxy=false
        filter.doFilterInternal(req, resp, chain);
        assertEquals(429, resp.getStatus());
    }

    @Test
    void respectsForwardedForWhenProxyTrusted() throws Exception {
        ReflectionTestUtils.setField(filter, "trustProxy", true);

        // Exhaust limit for forwarded IP 1.2.3.4
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest req = authRequest("10.0.0.1");
            req.addHeader("X-Forwarded-For", "1.2.3.4");
            filter.doFilterInternal(req, new MockHttpServletResponse(), chain);
        }

        // Different forwarded IP should still be allowed
        MockHttpServletRequest req = authRequest("10.0.0.1");
        req.addHeader("X-Forwarded-For", "5.6.7.8");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilterInternal(req, resp, chain);
        assertNotEquals(429, resp.getStatus());
    }

    @Test
    void skipsNonAuthEndpoints() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/library/files");
        req.setRemoteAddr("10.0.0.1");
        assertTrue(filter.shouldNotFilter(req));
    }

    @Test
    void filtersAuthEndpoints() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/signin");
        req.setRemoteAddr("10.0.0.1");
        assertFalse(filter.shouldNotFilter(req));
    }

    private MockHttpServletRequest authRequest(String remoteAddr) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/signin");
        req.setRemoteAddr(remoteAddr);
        return req;
    }
}
