package com.stellarideas.grooves.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Stores JWT token IDs (jti) that have been revoked via logout.
 * Documents are automatically removed by MongoDB TTL index after the token's original expiry.
 */
@Document(collection = "blacklisted_tokens")
public class BlacklistedToken {

    @Id
    private String id;

    @Indexed(unique = true)
    private String jti;

    @Indexed(expireAfter = "0s")
    private Instant expiresAt;

    private Instant blacklistedAt;

    private String username;

    public BlacklistedToken() {}

    public BlacklistedToken(String jti, Instant expiresAt, String username) {
        this.jti = jti;
        this.expiresAt = expiresAt;
        this.blacklistedAt = Instant.now();
        this.username = username;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getJti() { return jti; }
    public void setJti(String jti) { this.jti = jti; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getBlacklistedAt() { return blacklistedAt; }
    public void setBlacklistedAt(Instant blacklistedAt) { this.blacklistedAt = blacklistedAt; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
}
