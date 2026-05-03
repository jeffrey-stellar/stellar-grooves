package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.model.SmartPlaylist;
import com.stellarideas.grooves.service.SmartPlaylistService;
import com.stellarideas.grooves.smartplaylist.QueryParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SharedSmartPlaylistControllerTest {

    private SharedSmartPlaylistController controller;
    private SmartPlaylistService service;

    @BeforeEach
    void setUp() {
        service = mock(SmartPlaylistService.class);
        controller = new SharedSmartPlaylistController(service);
    }

    private SmartPlaylist sharedPlaylist(String token) {
        SmartPlaylist sp = new SmartPlaylist();
        sp.setId("sp-1");
        sp.setUserId("curator-1");
        sp.setName("90s Thrash Foundations");
        sp.setQueryString("genre:thrash_metal year:1986..1995");
        sp.setDescription("The records that built the genre");
        sp.setShareToken(token);
        return sp;
    }

    @Test
    void returnsCuratorViewWithMatchCount() {
        SmartPlaylist sp = sharedPlaylist("tok-1");
        when(service.findByShareToken("tok-1")).thenReturn(Optional.of(sp));
        when(service.count(sp)).thenReturn(247L);
        when(service.findOwnerUsername(sp)).thenReturn(Optional.of("metalcurator"));

        ResponseEntity<?> response = controller.get("tok-1");

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("90s Thrash Foundations", body.get("name"));
        assertEquals("The records that built the genre", body.get("description"));
        assertEquals("genre:thrash_metal year:1986..1995", body.get("queryString"));
        assertEquals("metalcurator", body.get("curatorUsername"));
        assertEquals(247L, body.get("matchCount"));
    }

    @Test
    void returns404ForUnknownToken() {
        when(service.findByShareToken("nope")).thenReturn(Optional.empty());
        assertEquals(404, controller.get("nope").getStatusCode().value());
    }

    @Test
    void returns410ForExpiredToken() {
        SmartPlaylist sp = sharedPlaylist("expired");
        sp.setShareTokenExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        when(service.findByShareToken("expired")).thenReturn(Optional.of(sp));

        ResponseEntity<?> response = controller.get("expired");

        assertEquals(410, response.getStatusCode().value());
    }

    @Test
    void zeroMatchCountWhenQueryFailsToParse() {
        // Curator's saved query somehow no longer parses. View should still render.
        SmartPlaylist sp = sharedPlaylist("tok-2");
        when(service.findByShareToken("tok-2")).thenReturn(Optional.of(sp));
        when(service.count(sp)).thenThrow(new QueryParseException("invalid"));
        when(service.findOwnerUsername(sp)).thenReturn(Optional.of("metalcurator"));

        ResponseEntity<?> response = controller.get("tok-2");

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(0L, body.get("matchCount"));
    }

    @Test
    void rejectsAbsurdlyLongToken() {
        String tooLong = "a".repeat(257);
        ResponseEntity<?> response = controller.get(tooLong);
        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void exposesSubscriberCount() {
        SmartPlaylist sp = sharedPlaylist("tok-3");
        when(service.findByShareToken("tok-3")).thenReturn(Optional.of(sp));
        when(service.count(sp)).thenReturn(50L);
        when(service.findOwnerUsername(sp)).thenReturn(Optional.of("metalcurator"));
        when(service.subscriberCount(sp)).thenReturn(12L);

        ResponseEntity<?> response = controller.get("tok-3");

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(12L, body.get("subscriberCount"));
    }
}
