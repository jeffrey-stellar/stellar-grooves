package com.stellarideas.grooves.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CorsConfigTest {

    private WebSecurityConfig createConfig(String allowedOrigins) {
        UserDetailsServiceImpl uds = mock(UserDetailsServiceImpl.class);
        JwtUtils jwtUtils = mock(JwtUtils.class);
        WebSecurityConfig config = new WebSecurityConfig(uds, jwtUtils);
        ReflectionTestUtils.setField(config, "allowedOrigins", allowedOrigins);
        ReflectionTestUtils.setField(config, "swaggerEnabled", false);
        return config;
    }

    @Test
    void allowsConfiguredOrigins() {
        WebSecurityConfig config = createConfig("https://example.com,https://www.example.com");
        CorsConfigurationSource source = config.corsConfigurationSource();

        CorsConfiguration corsConfig = ((UrlBasedCorsConfigurationSource) source)
                .getCorsConfigurations().get("/**");

        assertNotNull(corsConfig);
        List<String> origins = corsConfig.getAllowedOrigins();
        assertEquals(2, origins.size());
        assertTrue(origins.contains("https://example.com"));
        assertTrue(origins.contains("https://www.example.com"));
    }

    @Test
    void rejectsEmptyOrigins() {
        WebSecurityConfig config = createConfig("");

        assertThrows(IllegalStateException.class, () -> config.corsConfigurationSource());
    }

    @Test
    void allowsCredentials() {
        WebSecurityConfig config = createConfig("http://localhost:8080");
        CorsConfigurationSource source = config.corsConfigurationSource();

        CorsConfiguration corsConfig = ((UrlBasedCorsConfigurationSource) source)
                .getCorsConfigurations().get("/**");

        assertTrue(corsConfig.getAllowCredentials());
    }

    @Test
    void allowsRequiredMethods() {
        WebSecurityConfig config = createConfig("http://localhost:8080");
        CorsConfigurationSource source = config.corsConfigurationSource();

        CorsConfiguration corsConfig = ((UrlBasedCorsConfigurationSource) source)
                .getCorsConfigurations().get("/**");

        List<String> methods = corsConfig.getAllowedMethods();
        assertTrue(methods.contains("GET"));
        assertTrue(methods.contains("POST"));
        assertTrue(methods.contains("PUT"));
        assertTrue(methods.contains("PATCH"));
        assertTrue(methods.contains("DELETE"));
        assertTrue(methods.contains("OPTIONS"));
    }

    @Test
    void allowsRequiredHeaders() {
        WebSecurityConfig config = createConfig("http://localhost:8080");
        CorsConfigurationSource source = config.corsConfigurationSource();

        CorsConfiguration corsConfig = ((UrlBasedCorsConfigurationSource) source)
                .getCorsConfigurations().get("/**");

        List<String> headers = corsConfig.getAllowedHeaders();
        assertTrue(headers.contains("Authorization"));
        assertTrue(headers.contains("Content-Type"));
        assertTrue(headers.contains("X-XSRF-TOKEN"));
    }

    @Test
    void wildcardOriginNotAllowed() {
        // Verify no wildcard — explicit origins only
        WebSecurityConfig config = createConfig("http://localhost:8080");
        CorsConfigurationSource source = config.corsConfigurationSource();

        CorsConfiguration corsConfig = ((UrlBasedCorsConfigurationSource) source)
                .getCorsConfigurations().get("/**");

        assertFalse(corsConfig.getAllowedOrigins().contains("*"));
    }

    @Test
    void trimsWhitespaceFromOrigins() {
        WebSecurityConfig config = createConfig("  https://a.com , https://b.com  ");
        CorsConfigurationSource source = config.corsConfigurationSource();

        CorsConfiguration corsConfig = ((UrlBasedCorsConfigurationSource) source)
                .getCorsConfigurations().get("/**");

        List<String> origins = corsConfig.getAllowedOrigins();
        assertTrue(origins.contains("https://a.com"));
        assertTrue(origins.contains("https://b.com"));
    }
}
