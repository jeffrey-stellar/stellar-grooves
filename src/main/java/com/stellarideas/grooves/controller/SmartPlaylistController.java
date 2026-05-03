package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.dto.MusicFileDTO;
import com.stellarideas.grooves.dto.SmartPlaylistDTO;
import com.stellarideas.grooves.dto.SmartPlaylistPreviewRequest;
import com.stellarideas.grooves.dto.SmartPlaylistRequest;
import com.stellarideas.grooves.model.SmartPlaylist;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.security.CurrentUser;
import com.stellarideas.grooves.service.AuditService;
import com.stellarideas.grooves.service.SmartPlaylistService;
import com.stellarideas.grooves.config.PaginationDefaults;
import com.stellarideas.grooves.smartplaylist.QueryParseException;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/smart-playlists")
@Tag(name = "Smart Playlists", description = "Saved queries over the user's library")
@org.springframework.validation.annotation.Validated
public class SmartPlaylistController {

    private final SmartPlaylistService service;
    private final AuditService auditService;

    public SmartPlaylistController(SmartPlaylistService service, AuditService auditService) {
        this.service = service;
        this.auditService = auditService;
    }

    @GetMapping
    public List<SmartPlaylistDTO> list(@CurrentUser User user) {
        return service.list(user.getId()).stream()
                .map(this::dtoFor)
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@CurrentUser User user, @PathVariable String id) {
        return service.findByIdAndUserId(id, user.getId())
                .<ResponseEntity<?>>map(sp -> ResponseEntity.ok(dtoFor(sp)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@CurrentUser User user, @Valid @RequestBody SmartPlaylistRequest body) {
        try {
            SmartPlaylist saved = service.create(user.getId(), body.getName(), body.getQueryString(), body.getDescription());
            auditService.log(user.getUsername(), AuditService.Action.SMART_PLAYLIST_CREATE, saved.getId(), saved.getName());
            return ResponseEntity.ok(dtoFor(saved));
        } catch (QueryParseException | IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST, e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@CurrentUser User user, @PathVariable String id,
                                    @Valid @RequestBody SmartPlaylistRequest body) {
        Optional<SmartPlaylist> existing = service.findByIdAndUserId(id, user.getId());
        if (existing.isEmpty()) return ResponseEntity.notFound().build();
        try {
            SmartPlaylist saved = existing.get().isSubscription()
                    ? service.renameSubscription(existing.get(), body.getName())
                    : service.update(existing.get(), body.getName(), body.getQueryString(), body.getDescription());
            auditService.log(user.getUsername(), AuditService.Action.SMART_PLAYLIST_UPDATE, saved.getId(), saved.getName());
            return ResponseEntity.ok(dtoFor(saved));
        } catch (QueryParseException | IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST, e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(GlobalExceptionHandler.problem(HttpStatus.CONFLICT, e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@CurrentUser User user, @PathVariable String id) {
        Optional<SmartPlaylist> existing = service.findByIdAndUserId(id, user.getId());
        if (existing.isEmpty()) return ResponseEntity.notFound().build();
        service.delete(existing.get());
        auditService.log(user.getUsername(), AuditService.Action.SMART_PLAYLIST_DELETE, id, existing.get().getName());
        return ResponseEntity.ok(Map.of("message", "Smart playlist deleted"));
    }

    // ─── Sharing / subscriptions ────────────────────────────────────────

    @PostMapping("/{id}/share")
    public ResponseEntity<?> share(@CurrentUser User user, @PathVariable String id) {
        Optional<SmartPlaylist> existing = service.findByIdAndUserId(id, user.getId());
        if (existing.isEmpty()) return ResponseEntity.notFound().build();
        try {
            SmartPlaylist shared = service.share(existing.get());
            auditService.log(user.getUsername(), AuditService.Action.SMART_PLAYLIST_SHARE, shared.getId(), shared.getName());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("shareToken", shared.getShareToken());
            body.put("shareUrl", "/api/v1/shared/smart-playlists/" + shared.getShareToken());
            return ResponseEntity.ok(body);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(GlobalExceptionHandler.problem(HttpStatus.CONFLICT, e.getMessage()));
        }
    }

    @DeleteMapping("/{id}/share")
    public ResponseEntity<?> revokeShare(@CurrentUser User user, @PathVariable String id) {
        Optional<SmartPlaylist> existing = service.findByIdAndUserId(id, user.getId());
        if (existing.isEmpty()) return ResponseEntity.notFound().build();
        SmartPlaylist saved = service.revokeShare(existing.get());
        auditService.log(user.getUsername(), AuditService.Action.SMART_PLAYLIST_REVOKE_SHARE, saved.getId(), saved.getName());
        return ResponseEntity.ok(Map.of("message", "Share link revoked"));
    }

    @PostMapping("/subscribe")
    public ResponseEntity<?> subscribe(@CurrentUser User user, @RequestBody Map<String, String> body) {
        String token = body == null ? null : body.get("shareToken");
        if (token == null || token.isBlank() || token.length() > 256) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST, "shareToken is required"));
        }
        Optional<SmartPlaylist> source = service.findByShareToken(token);
        if (source.isEmpty() || source.get().isShareTokenExpired()) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(GlobalExceptionHandler.problem(HttpStatus.GONE, "Share link not found or expired"));
        }
        try {
            SmartPlaylist sub = service.subscribe(user.getId(), source.get());
            auditService.log(user.getUsername(), AuditService.Action.SMART_PLAYLIST_SUBSCRIBE, sub.getId(), source.get().getName());
            return ResponseEntity.ok(dtoFor(sub));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST, e.getMessage()));
        }
    }

    @PostMapping("/{id}/fork")
    public ResponseEntity<?> fork(@CurrentUser User user, @PathVariable String id) {
        Optional<SmartPlaylist> existing = service.findByIdAndUserId(id, user.getId());
        if (existing.isEmpty()) return ResponseEntity.notFound().build();
        try {
            SmartPlaylist forked = service.fork(existing.get());
            auditService.log(user.getUsername(), AuditService.Action.SMART_PLAYLIST_FORK, forked.getId(), forked.getName());
            return ResponseEntity.ok(dtoFor(forked));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(GlobalExceptionHandler.problem(HttpStatus.CONFLICT, e.getMessage()));
        }
    }

    private SmartPlaylistDTO dtoFor(SmartPlaylist sp) {
        SmartPlaylistDTO.View view = new SmartPlaylistDTO.View();
        if (sp.isSubscription()) {
            // Resolve live values from the source. If the source is gone, fall back to the
            // last-snapshotted query so the row still renders something coherent.
            Optional<SmartPlaylist> source = service.findSubscriptionSource(sp);
            view.sourceAvailable = source.isPresent();
            view.resolvedQuery = source.map(SmartPlaylist::getQueryString).orElse(sp.getQueryString());
            view.resolvedDescription = source.map(SmartPlaylist::getDescription).orElse(null);
            view.curatorUsername = source.flatMap(service::findOwnerUsername).orElse(null);
        } else if (sp.getShareToken() != null) {
            // Owner of a published query — surface the subscriber count for social proof.
            view.subscriberCount = service.subscriberCount(sp);
        }
        return SmartPlaylistDTO.from(sp, view);
    }

    @GetMapping("/{id}/preview")
    public ResponseEntity<?> preview(@CurrentUser User user, @PathVariable String id,
                                     @RequestParam(defaultValue = "0") @Min(0) int page,
                                     @RequestParam(defaultValue = "50") @Min(1) @Max(PaginationDefaults.MAX_PAGE_SIZE) int size) {
        Optional<SmartPlaylist> existing = service.findByIdAndUserId(id, user.getId());
        if (existing.isEmpty()) return ResponseEntity.notFound().build();
        try {
            SmartPlaylistService.PreviewResult result = service.preview(existing.get(), page, size);
            return ResponseEntity.ok(pageBody(result));
        } catch (QueryParseException e) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST, e.getMessage()));
        }
    }

    @PostMapping("/preview")
    public ResponseEntity<?> dryRun(@CurrentUser User user,
                                    @Valid @RequestBody SmartPlaylistPreviewRequest body,
                                    @RequestParam(defaultValue = "0") @Min(0) int page,
                                    @RequestParam(defaultValue = "50") @Min(1) @Max(PaginationDefaults.MAX_PAGE_SIZE) int size) {
        try {
            SmartPlaylistService.PreviewResult result = service.execute(user.getId(), body.getQueryString(), page, size);
            return ResponseEntity.ok(pageBody(result));
        } catch (QueryParseException e) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST, e.getMessage()));
        }
    }

    @GetMapping("/{id}/count")
    public ResponseEntity<?> count(@CurrentUser User user, @PathVariable String id) {
        Optional<com.stellarideas.grooves.model.SmartPlaylist> existing = service.findByIdAndUserId(id, user.getId());
        if (existing.isEmpty()) return ResponseEntity.notFound().build();
        try {
            return ResponseEntity.ok(Map.of("count", service.count(existing.get())));
        } catch (QueryParseException e) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST, e.getMessage()));
        }
    }

    @PostMapping("/count")
    public ResponseEntity<?> dryRunCount(@CurrentUser User user,
                                         @Valid @RequestBody SmartPlaylistPreviewRequest body) {
        try {
            return ResponseEntity.ok(Map.of("count", service.count(user.getId(), body.getQueryString())));
        } catch (QueryParseException e) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST, e.getMessage()));
        }
    }

    @PostMapping("/{id}/materialize")
    public ResponseEntity<?> materialize(@CurrentUser User user, @PathVariable String id,
                                         @RequestParam(required = false) String name) {
        Optional<SmartPlaylist> existing = service.findByIdAndUserId(id, user.getId());
        if (existing.isEmpty()) return ResponseEntity.notFound().build();
        SmartPlaylist source = existing.get();
        String targetName = (name != null && !name.isBlank()) ? name : source.getName();
        try {
            SmartPlaylistService.MaterializeResult result = service.materialize(source, targetName);
            auditService.log(user.getUsername(), AuditService.Action.SMART_PLAYLIST_MATERIALIZE,
                    source.getId(), result.trackCount() + " tracks");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("playlistId", result.playlist().getId());
            body.put("name", result.playlist().getName());
            body.put("trackCount", result.trackCount());
            body.put("truncated", result.truncated());
            return ResponseEntity.ok(body);
        } catch (QueryParseException e) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST, e.getMessage()));
        }
    }

    private static Map<String, Object> pageBody(SmartPlaylistService.PreviewResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", result.page().getContent().stream().map(MusicFileDTO::from).collect(Collectors.toList()));
        body.put("page", result.page().getNumber());
        body.put("size", result.page().getSize());
        body.put("totalElements", result.page().getTotalElements());
        body.put("totalPages", result.page().getTotalPages());
        body.put("truncated", result.truncated());
        return body;
    }
}
