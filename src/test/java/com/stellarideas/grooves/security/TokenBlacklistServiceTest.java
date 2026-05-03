package com.stellarideas.grooves.security;

import com.stellarideas.grooves.model.BlacklistedToken;
import com.stellarideas.grooves.repository.BlacklistedTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenBlacklistServiceTest {

    private BlacklistedTokenRepository repository;
    private TokenBlacklistService service;

    @BeforeEach
    void setUp() {
        repository = mock(BlacklistedTokenRepository.class);
        service = new TokenBlacklistService(repository, 900_000L, 1_000L);
    }

    @Test
    void nullJtiIsNotBlacklistedAndDoesNotHitRepo() {
        assertFalse(service.isBlacklisted(null));
        verify(repository, never()).existsByJti(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void firstLookupQueriesRepository() {
        when(repository.existsByJti("jti-1")).thenReturn(false);

        assertFalse(service.isBlacklisted("jti-1"));

        verify(repository, times(1)).existsByJti("jti-1");
    }

    @Test
    void cachedNegativeResultPreventsAdditionalQueries() {
        when(repository.existsByJti("jti-2")).thenReturn(false);

        service.isBlacklisted("jti-2");
        service.isBlacklisted("jti-2");
        service.isBlacklisted("jti-2");

        verify(repository, times(1)).existsByJti("jti-2");
    }

    @Test
    void cachedPositiveResultPreventsAdditionalQueries() {
        when(repository.existsByJti("jti-3")).thenReturn(true);

        assertTrue(service.isBlacklisted("jti-3"));
        assertTrue(service.isBlacklisted("jti-3"));

        verify(repository, times(1)).existsByJti("jti-3");
    }

    @Test
    void blacklistSavesToRepoAndPrimesCache() {
        BlacklistedToken token = new BlacklistedToken("jti-4", Instant.now().plusSeconds(60), "user1");

        service.blacklist(token);

        verify(repository, times(1)).save(token);
        // Subsequent isBlacklisted should not hit the repo again
        assertTrue(service.isBlacklisted("jti-4"));
        verify(repository, never()).existsByJti(eq("jti-4"));
    }

    @Test
    void invalidateCacheForcesRepoLookup() {
        when(repository.existsByJti("jti-5")).thenReturn(false);

        service.isBlacklisted("jti-5");
        service.invalidateCache();
        service.isBlacklisted("jti-5");

        verify(repository, times(2)).existsByJti("jti-5");
    }
}
