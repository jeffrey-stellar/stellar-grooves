package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.model.SmartPlaylist;
import com.stellarideas.grooves.service.SmartPlaylistService;
import com.stellarideas.grooves.smartplaylist.QueryParseException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Public, unauthenticated viewer for a shared smart playlist. Returns the curator's
 * query, name, description, and a match-count against the curator's own library
 * (social proof — "this query matches N tracks in the curator's library").
 *
 * <p>The actual track list is intentionally <em>not</em> exposed: the keystone of
 * the Curator's Library sharing model is sharing taste rules, not recordings.
 *
 * <p>Authenticated subscribe/fork actions live on {@code SmartPlaylistController}
 * since they require the caller's identity.
 */
@RestController
@RequestMapping("/api/v1/shared/smart-playlists")
@Tag(name = "Shared Smart Playlists",
        description = "Public access to shared smart-playlist queries via share tokens")
public class SharedSmartPlaylistController {

    private final SmartPlaylistService service;

    public SharedSmartPlaylistController(SmartPlaylistService service) {
        this.service = service;
    }

    @Operation(summary = "View shared smart playlist",
            description = "Returns the curator's query, description, name, username, and match count "
                    + "against the curator's own library. Returns 410 if the link has expired.")
    @GetMapping("/{token}")
    public ResponseEntity<?> get(@PathVariable String token) {
        if (token == null || token.length() > 256) {
            return ResponseEntity.notFound().build();
        }
        Optional<SmartPlaylist> opt = service.findByShareToken(token);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        SmartPlaylist sp = opt.get();
        if (sp.isShareTokenExpired()) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(GlobalExceptionHandler.problem(HttpStatus.GONE,
                            "This shared smart playlist link has expired"));
        }

        long matchCount;
        try {
            matchCount = service.count(sp);
        } catch (QueryParseException e) {
            // The curator's saved query somehow no longer parses. Surface zero matches
            // rather than failing the public view; the curator can fix and re-share.
            matchCount = 0;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", sp.getName());
        body.put("description", sp.getDescription());
        body.put("queryString", sp.getQueryString());
        body.put("curatorUsername", service.findOwnerUsername(sp).orElse(null));
        body.put("matchCount", matchCount);
        body.put("subscriberCount", service.subscriberCount(sp));
        body.put("createdAt", sp.getCreatedAt());
        body.put("updatedAt", sp.getUpdatedAt());
        return ResponseEntity.ok(body);
    }
}
