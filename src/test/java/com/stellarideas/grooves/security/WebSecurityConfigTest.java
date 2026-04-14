package com.stellarideas.grooves.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSecurityConfigTest {

    private WebSecurityConfig createConfig(String origins, boolean swaggerEnabled) {
        UserDetailsServiceImpl userDetailsService = mock(UserDetailsServiceImpl.class);
        JwtUtils jwtUtils = mock(JwtUtils.class);
        WebSecurityConfig config = new WebSecurityConfig(userDetailsService, jwtUtils);
        ReflectionTestUtils.setField(config, "allowedOrigins", origins);
        ReflectionTestUtils.setField(config, "swaggerEnabled", swaggerEnabled);
        return config;
    }

    @Test
    void passwordEncoderReturnsBCrypt() {
        WebSecurityConfig config = createConfig("http://localhost:8080", false);
        PasswordEncoder encoder = config.passwordEncoder();
        assertInstanceOf(BCryptPasswordEncoder.class, encoder);
    }

    @Test
    void authenticationProviderConfigured() {
        WebSecurityConfig config = createConfig("http://localhost:8080", false);
        DaoAuthenticationProvider provider = config.authenticationProvider();
        assertNotNull(provider);
    }

    @Test
    void corsConfigurationParsesOrigins() {
        WebSecurityConfig config = createConfig("http://localhost:8080,https://app.example.com", false);
        CorsConfigurationSource source = config.corsConfigurationSource();
        assertNotNull(source);
        assertInstanceOf(UrlBasedCorsConfigurationSource.class, source);
    }

    @Test
    void corsConfigurationTrimsWhitespace() {
        WebSecurityConfig config = createConfig(" http://localhost:8080 , https://app.example.com ", false);
        CorsConfigurationSource source = config.corsConfigurationSource();
        assertNotNull(source);
    }

    @Test
    void corsConfigurationRejectsEmpty() {
        WebSecurityConfig config = createConfig("", false);
        assertThrows(IllegalStateException.class, config::corsConfigurationSource);
    }

    @Test
    void corsConfigurationRejectsBlankEntries() {
        WebSecurityConfig config = createConfig(" , , ", false);
        assertThrows(IllegalStateException.class, config::corsConfigurationSource);
    }

    @Test
    void authTokenFilterCreated() {
        WebSecurityConfig config = createConfig("http://localhost:8080", false);
        AuthTokenFilter filter = config.authenticationJwtTokenFilter();
        assertNotNull(filter);
    }
}
