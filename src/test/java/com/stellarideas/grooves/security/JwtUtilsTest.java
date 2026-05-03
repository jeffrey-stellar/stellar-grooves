package com.stellarideas.grooves.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtUtilsTest {

    private JwtUtils jwtUtils;
    private TokenBlacklistService tokenBlacklistService;

    private static final String TEST_SECRET =
            "c3RlbGxhci1ncm9vdmVzLXNlY3JldC1rZXktZm9yLWp3dC1wbGVhc2UtY2hhbmdlLWluLXByb2R1Y3Rpb24tZW52";

    @BeforeEach
    void setUp() {
        tokenBlacklistService = mock(TokenBlacklistService.class);
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
        jwtUtils = new JwtUtils(tokenBlacklistService);
        ReflectionTestUtils.setField(jwtUtils, "jwtSecret", TEST_SECRET);
        ReflectionTestUtils.setField(jwtUtils, "jwtExpirationMs", 86400000);
    }

    private Authentication buildAuthentication(String username) {
        var userDetails = org.springframework.security.core.userdetails.User
                .withUsername(username)
                .password("irrelevant")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();
        return new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
    }

    @Test
    void generateAndValidateToken() {
        Authentication auth = buildAuthentication("testuser");
        String token = jwtUtils.generateJwtToken(auth);
        assertNotNull(token);
        assertTrue(jwtUtils.validateJwtToken(token));
    }

    @Test
    void extractUsernameFromToken() {
        Authentication auth = buildAuthentication("groovesuser");
        String token = jwtUtils.generateJwtToken(auth);
        assertEquals("groovesuser", jwtUtils.getUserNameFromJwtToken(token));
    }

    @Test
    void invalidTokenReturnsFalse() {
        assertFalse(jwtUtils.validateJwtToken("not.a.valid.token"));
    }

    @Test
    void emptyTokenReturnsFalse() {
        assertFalse(jwtUtils.validateJwtToken(""));
    }

    @Test
    void nullTokenReturnsFalse() {
        assertFalse(jwtUtils.validateJwtToken(null));
    }

    @Test
    void expiredTokenReturnsFalse() {
        JwtUtils expiredUtils = new JwtUtils(tokenBlacklistService);
        ReflectionTestUtils.setField(expiredUtils, "jwtSecret", TEST_SECRET);
        ReflectionTestUtils.setField(expiredUtils, "jwtExpirationMs", -1000); // already expired
        Authentication auth = buildAuthentication("expireduser");
        String token = expiredUtils.generateJwtToken(auth);
        assertFalse(jwtUtils.validateJwtToken(token));
    }

    @Test
    void blacklistedTokenReturnsFalse() {
        Authentication auth = buildAuthentication("testuser");
        String token = jwtUtils.generateJwtToken(auth);

        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(true);

        assertFalse(jwtUtils.validateJwtToken(token));
    }

    @Test
    void getJtiFromTokenReturnsJti() {
        Authentication auth = buildAuthentication("testuser");
        String token = jwtUtils.generateJwtToken(auth);

        String jti = jwtUtils.getJtiFromToken(token);
        assertNotNull(jti);
        assertFalse(jti.isBlank());
    }

    @Test
    void getJtiFromInvalidTokenReturnsNull() {
        assertNull(jwtUtils.getJtiFromToken("not.valid.token"));
    }

    @Test
    void getExpirationFromTokenReturnsInstant() {
        Authentication auth = buildAuthentication("testuser");
        String token = jwtUtils.generateJwtToken(auth);

        java.time.Instant expiration = jwtUtils.getExpirationFromToken(token);
        assertNotNull(expiration);
        assertTrue(expiration.isAfter(java.time.Instant.now()));
    }

    @Test
    void getExpirationFromInvalidTokenReturnsNull() {
        assertNull(jwtUtils.getExpirationFromToken("not.valid.token"));
    }

    @Test
    void getRefreshTokenExpirationMsReturnsConfiguredValue() {
        ReflectionTestUtils.setField(jwtUtils, "refreshTokenExpirationMs", 604800000L);
        assertEquals(604800000L, jwtUtils.getRefreshTokenExpirationMs());
    }
}
