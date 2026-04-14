package com.stellarideas.grooves.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequestCorrelationFilterTest {

    private RequestCorrelationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        filter = new RequestCorrelationFilter();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        filterChain = mock(FilterChain.class);
    }

    @Test
    void generatesCorrelationIdWhenNoneProvided() throws Exception {
        filter.doFilterInternal(request, response, filterChain);

        String headerValue = response.getHeader("X-Correlation-Id");
        assertNotNull(headerValue);
        assertEquals(8, headerValue.length());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void usesProvidedCorrelationIdFromRequestHeader() throws Exception {
        request.addHeader("X-Correlation-Id", "my-corr-id");

        filter.doFilterInternal(request, response, filterChain);

        assertEquals("my-corr-id", response.getHeader("X-Correlation-Id"));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void setsResponseHeader() throws Exception {
        request.addHeader("X-Correlation-Id", "test-123");

        filter.doFilterInternal(request, response, filterChain);

        assertEquals("test-123", response.getHeader("X-Correlation-Id"));
    }

    @Test
    void cleansMdcAfterFilterChainCompletes() throws Exception {
        filter.doFilterInternal(request, response, filterChain);

        assertNull(MDC.get("correlationId"));
    }

    @Test
    void cleansMdcEvenWhenFilterChainThrows() throws Exception {
        doThrow(new RuntimeException("boom")).when(filterChain).doFilter(request, response);

        assertThrows(RuntimeException.class,
                () -> filter.doFilterInternal(request, response, filterChain));

        assertNull(MDC.get("correlationId"));
    }

    @Test
    void generatesCorrelationIdWhenHeaderIsBlank() throws Exception {
        request.addHeader("X-Correlation-Id", "   ");

        filter.doFilterInternal(request, response, filterChain);

        String headerValue = response.getHeader("X-Correlation-Id");
        assertNotNull(headerValue);
        assertEquals(8, headerValue.length());
        assertNotEquals("   ", headerValue);
    }
}
