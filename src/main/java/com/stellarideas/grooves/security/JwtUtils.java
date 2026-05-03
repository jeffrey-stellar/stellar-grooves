package com.stellarideas.grooves.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtUtils {

    private static final Logger logger = LoggerFactory.getLogger(JwtUtils.class);

    private final TokenBlacklistService tokenBlacklistService;

    @Value("${stellar.grooves.jwtSecret:}")
    private String jwtSecret;

    @Value("${stellar.grooves.jwtExpirationMs:900000}")
    private int jwtExpirationMs;

    @Value("${stellar.grooves.refreshTokenExpirationMs:604800000}")
    private long refreshTokenExpirationMs;

    public JwtUtils(TokenBlacklistService tokenBlacklistService) {
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @PostConstruct
    void validateJwtSecret() {
        if (jwtSecret != null) {
            jwtSecret = jwtSecret.replaceAll("\\s+", "");
        }
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "JWT secret is not configured. "
                    + "Set the JWT_SECRET environment variable or stellar.grooves.jwtSecret property.");
        }
        try {
            Decoders.BASE64.decode(jwtSecret);
        } catch (Exception e) {
            throw new IllegalStateException("JWT secret must be a valid Base64-encoded string.", e);
        }
        if (Decoders.BASE64.decode(jwtSecret).length < 32) {
            throw new IllegalStateException("JWT secret must be at least 256 bits (32 bytes) when decoded.");
        }
        logger.info("JWT secret configured successfully.");
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
    }

    public String generateJwtToken(Authentication authentication) {
        UserDetails userPrincipal = (UserDetails) authentication.getPrincipal();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer("stellar-grooves")
                .subject(userPrincipal.getUsername())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtExpirationMs))
                .signWith(key())
                .compact();
    }

    public String getUserNameFromJwtToken(String token) {
        return Jwts.parser().verifyWith(key()).build()
                .parseSignedClaims(token).getPayload().getSubject();
    }

    public boolean validateJwtToken(String authToken) {
        try {
            Claims claims = Jwts.parser().verifyWith(key()).build()
                    .parseSignedClaims(authToken).getPayload();
            String jti = claims.getId();
            if (jti != null && tokenBlacklistService.isBlacklisted(jti)) {
                logger.warn("JWT token has been revoked (jti={})", jti);
                return false;
            }
            return true;
        } catch (MalformedJwtException e) {
            logger.warn("Invalid JWT token: {}", e.getMessage());
        } catch (ExpiredJwtException e) {
            logger.debug("JWT token is expired: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            logger.warn("JWT token is unsupported: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.warn("JWT claims string is empty: {}", e.getMessage());
        }
        return false;
    }

    public long getRefreshTokenExpirationMs() {
        return refreshTokenExpirationMs;
    }

    /**
     * Extract the JTI (JWT ID) from a token. Returns null if the token is invalid.
     */
    public String getJtiFromToken(String token) {
        try {
            return Jwts.parser().verifyWith(key()).build()
                    .parseSignedClaims(token).getPayload().getId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extract the expiration instant from a token. Returns null if the token is invalid.
     */
    public Instant getExpirationFromToken(String token) {
        try {
            Date exp = Jwts.parser().verifyWith(key()).build()
                    .parseSignedClaims(token).getPayload().getExpiration();
            return exp != null ? exp.toInstant() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
