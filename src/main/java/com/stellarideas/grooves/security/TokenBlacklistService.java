package com.stellarideas.grooves.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stellarideas.grooves.model.BlacklistedToken;
import com.stellarideas.grooves.repository.BlacklistedTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class TokenBlacklistService {

    private final BlacklistedTokenRepository repository;
    private final Cache<String, Boolean> cache;

    public TokenBlacklistService(
            BlacklistedTokenRepository repository,
            @Value("${stellar.grooves.jwtExpirationMs:900000}") long jwtExpirationMs,
            @Value("${stellar.grooves.tokenBlacklistCache.maxSize:100000}") long maxSize) {
        this.repository = repository;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMillis(jwtExpirationMs))
                .maximumSize(maxSize)
                .build();
    }

    public boolean isBlacklisted(String jti) {
        if (jti == null) {
            return false;
        }
        Boolean cached = cache.get(jti, repository::existsByJti);
        return Boolean.TRUE.equals(cached);
    }

    public void blacklist(BlacklistedToken token) {
        repository.save(token);
        cache.put(token.getJti(), Boolean.TRUE);
    }

    void invalidateCache() {
        cache.invalidateAll();
    }
}
