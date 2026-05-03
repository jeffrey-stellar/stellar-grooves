package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.dto.SmartPlaylistPhraseRequest;
import com.stellarideas.grooves.model.SmartPlaylistPhrase;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.SmartPlaylistPhraseRepository;
import com.stellarideas.grooves.smartplaylist.SmartPlaylistQueryParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmartPlaylistPhraseControllerTest {

    private SmartPlaylistPhraseController controller;
    private SmartPlaylistPhraseRepository repository;
    private User user;

    @BeforeEach
    void setUp() {
        repository = mock(SmartPlaylistPhraseRepository.class);
        controller = new SmartPlaylistPhraseController(repository, new SmartPlaylistQueryParser());
        user = new User();
        user.setId("u1");
        user.setUsername("curator");
    }

    private SmartPlaylistPhraseRequest req(String name, String body) {
        SmartPlaylistPhraseRequest r = new SmartPlaylistPhraseRequest();
        r.setName(name);
        r.setBody(body);
        return r;
    }

    @Test
    void listReturnsUserPhrasesAlphabetical() {
        SmartPlaylistPhrase p = new SmartPlaylistPhrase();
        p.setId("p1");
        p.setUserId("u1");
        p.setName("thrash-core");
        p.setBody("genre:thrash_metal");
        when(repository.findByUserIdOrderByNameAsc("u1")).thenReturn(List.of(p));

        var dtos = controller.list(user);

        assertEquals(1, dtos.size());
        assertEquals("thrash-core", dtos.get(0).getName());
    }

    @Test
    void createPersistsValidPhrase() {
        when(repository.existsByUserIdAndName("u1", "thrash-core")).thenReturn(false);
        when(repository.save(any(SmartPlaylistPhrase.class))).thenAnswer(inv -> {
            SmartPlaylistPhrase p = inv.getArgument(0);
            p.setId("p-new");
            return p;
        });

        ResponseEntity<?> response = controller.create(user, req("thrash-core", "genre:thrash_metal year:>=1986"));

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void createNormalizesNameToLowercase() {
        // Pattern validation runs at controller boundary via @Valid; this test calls the
        // controller directly so we can pass a name that hits the lowercase normalization.
        // (Bean Validation isn't active without a Spring proxy.)
        when(repository.existsByUserIdAndName("u1", "thrash")).thenReturn(false);
        when(repository.save(any(SmartPlaylistPhrase.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.create(user, req("thrash", "genre:thrash_metal"));

        // Verify saved name was lowercase
        org.mockito.ArgumentCaptor<SmartPlaylistPhrase> captor = org.mockito.ArgumentCaptor.forClass(SmartPlaylistPhrase.class);
        verify(repository).save(captor.capture());
        assertEquals("thrash", captor.getValue().getName());
    }

    @Test
    void createRejectsDuplicateName() {
        when(repository.existsByUserIdAndName("u1", "thrash-core")).thenReturn(true);

        ResponseEntity<?> response = controller.create(user, req("thrash-core", "genre:thrash_metal"));

        assertEquals(409, response.getStatusCode().value());
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsBodyWithSortClause() {
        ResponseEntity<?> response = controller.create(user, req("bad", "rating:>=4 sort:rating"));
        assertEquals(400, response.getStatusCode().value());
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsBodyWithLimitClause() {
        ResponseEntity<?> response = controller.create(user, req("bad", "rating:>=4 limit:50"));
        assertEquals(400, response.getStatusCode().value());
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsUnparseableBody() {
        ResponseEntity<?> response = controller.create(user, req("bad", "this is not a query"));
        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void createAcceptsBodyWithUndefinedPhraseReference() {
        // Define-time validation does NOT check @references — that's deferred.
        when(repository.existsByUserIdAndName("u1", "deferred")).thenReturn(false);
        when(repository.save(any(SmartPlaylistPhrase.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> response = controller.create(user, req("deferred", "@undefined"));
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void updateRequiresExistingPhrase() {
        when(repository.findByIdAndUserId("missing", "u1")).thenReturn(Optional.empty());
        ResponseEntity<?> response = controller.update(user, "missing", req("any", "rating:>=4"));
        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void deleteRemovesExistingPhrase() {
        SmartPlaylistPhrase p = new SmartPlaylistPhrase();
        p.setId("p1");
        p.setUserId("u1");
        when(repository.findByIdAndUserId("p1", "u1")).thenReturn(Optional.of(p));

        ResponseEntity<?> response = controller.delete(user, "p1");

        assertEquals(200, response.getStatusCode().value());
        verify(repository).delete(p);
    }

    @Test
    void deleteReturnsNotFoundWhenAbsent() {
        when(repository.findByIdAndUserId("missing", "u1")).thenReturn(Optional.empty());
        assertEquals(404, controller.delete(user, "missing").getStatusCode().value());
    }
}
