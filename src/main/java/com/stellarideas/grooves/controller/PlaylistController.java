package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.dto.*;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.Playlist;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.security.CurrentUser;
import com.stellarideas.grooves.service.AuditService;
import com.stellarideas.grooves.service.MessageHelper;
import com.stellarideas.grooves.service.PlaylistService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/playlists")
@Tag(name = "Playlists", description = "Playlist creation, management, sharing, and export")
public class PlaylistController {

    private static final Logger logger = LoggerFactory.getLogger(PlaylistController.class);

    private final PlaylistService playlistService;
    private final MessageHelper msg;
    private final AuditService auditService;

    public PlaylistController(PlaylistService playlistService,
                              MessageHelper msg,
                              AuditService auditService) {
        this.playlistService = playlistService;
        this.msg = msg;
        this.auditService = auditService;
    }

    @Operation(summary = "List playlists", description = "Get all playlists for the current user")
    @GetMapping
    public List<PlaylistDTO> getPlaylists(@CurrentUser User user) {
        return playlistService.getPlaylists(user.getId()).stream()
                .map(PlaylistDTO::from)
                .collect(Collectors.toList());
    }

    @Operation(summary = "Create playlist")
    @PostMapping
    public ResponseEntity<?> createPlaylist(@CurrentUser User user, @Valid @RequestBody CreatePlaylistRequest body) {
        Playlist saved = playlistService.createPlaylist(body.getName(), user.getId());
        auditService.log(user.getUsername(), AuditService.Action.PLAYLIST_CREATE, saved.getName());
        return ResponseEntity.ok(PlaylistDTO.from(saved));
    }

    @Operation(summary = "Delete playlist")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deletePlaylist(@CurrentUser User user, @PathVariable String id) {
        Optional<Playlist> opt = playlistService.findByIdAndUserId(id, user.getId());
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        playlistService.deletePlaylist(opt.get());
        auditService.log(user.getUsername(), AuditService.Action.PLAYLIST_DELETE, opt.get().getName());
        return ResponseEntity.ok(Map.of("message", msg.msg("playlist.deleted")));
    }

    @Operation(summary = "Add track to playlist")
    @PostMapping("/{id}/tracks")
    public ResponseEntity<?> addTrack(@CurrentUser User user, @PathVariable String id,
                                      @Valid @RequestBody AddTrackRequest body) {
        Optional<Playlist> opt = playlistService.findByIdAndUserId(id, user.getId());
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        Playlist playlist = opt.get();
        if (!playlistService.addTrack(playlist, body.getFileId(), user.getId())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("message", msg.msg("playlist.track.added"), "trackCount", playlist.getTrackIds().size()));
    }

    @Operation(summary = "Remove track from playlist")
    @DeleteMapping("/{id}/tracks/{fileId}")
    public ResponseEntity<?> removeTrack(@CurrentUser User user, @PathVariable String id, @PathVariable String fileId) {
        Optional<Playlist> opt = playlistService.findByIdAndUserId(id, user.getId());
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        Playlist playlist = opt.get();
        playlistService.removeTrack(playlist, fileId);
        return ResponseEntity.ok(Map.of("message", msg.msg("playlist.track.removed"), "trackCount", playlist.getTrackIds().size()));
    }

    @Operation(summary = "Get playlist tracks", description = "Get tracks in a playlist in order. Pass page+size for paginated hydration on large playlists; omit both for the full list (legacy behavior).")
    @GetMapping("/{id}/tracks")
    public ResponseEntity<?> getPlaylistTracks(@CurrentUser User user, @PathVariable String id,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size) {
        Optional<Playlist> opt = playlistService.findByIdAndUserId(id, user.getId());
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        if (page != null || size != null) {
            int p = page != null ? page : 0;
            int s = size != null ? size : 50;
            return ResponseEntity.ok(playlistService.getPlaylistTracks(opt.get(), user.getId(), p, s));
        }
        return ResponseEntity.ok(playlistService.getPlaylistTracks(opt.get(), user.getId()));
    }

    @Operation(summary = "Reorder playlist tracks")
    @PutMapping("/{id}/tracks/reorder")
    public ResponseEntity<?> reorderTracks(@CurrentUser User user, @PathVariable String id,
                                           @Valid @RequestBody ReorderTracksRequest request) {
        Optional<Playlist> opt = playlistService.findByIdAndUserId(id, user.getId());
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        Playlist playlist = opt.get();
        if (!playlistService.reorderTracks(playlist, request.getTrackIds())) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(org.springframework.http.HttpStatus.BAD_REQUEST, "Track IDs must match existing playlist tracks"));
        }
        return ResponseEntity.ok(Map.of("message", "Tracks reordered", "trackCount", playlist.getTrackIds().size()));
    }

    @Operation(summary = "Generate share link", description = "Create a shareable token for the playlist with optional expiration (1-365 days)")
    @PostMapping("/{id}/share")
    public ResponseEntity<?> generateShareToken(@CurrentUser User user, @PathVariable String id,
                                                @RequestParam(required = false) Integer expirationDays) {
        Optional<Playlist> opt = playlistService.findByIdAndUserId(id, user.getId());
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        if (expirationDays != null && (expirationDays < 1 || expirationDays > 365)) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(
                            org.springframework.http.HttpStatus.BAD_REQUEST,
                            "expirationDays must be between 1 and 365"));
        }
        String token = playlistService.generateShareToken(opt.get(), expirationDays);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shareToken", token);
        body.put("shareUrl", "/api/v1/shared/playlists/" + token);
        body.put("expiresAt", opt.get().getShareTokenExpiresAt());
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Revoke share link")
    @DeleteMapping("/{id}/share")
    public ResponseEntity<?> revokeShareToken(@CurrentUser User user, @PathVariable String id) {
        Optional<Playlist> opt = playlistService.findByIdAndUserId(id, user.getId());
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        playlistService.revokeShareToken(opt.get());
        return ResponseEntity.ok(Map.of("message", "Share token revoked"));
    }

    @Operation(summary = "Export playlist", description = "Export playlist as JSON or M3U format")
    @GetMapping("/{id}/export")
    public ResponseEntity<?> exportPlaylist(@CurrentUser User user, @PathVariable String id,
                                            @RequestParam(defaultValue = "json") String format) {
        Optional<Playlist> opt = playlistService.findByIdAndUserId(id, user.getId());
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        Playlist playlist = opt.get();
        List<MusicFile> orderedFiles = playlistService.getOrderedFiles(playlist, user.getId());

        if ("m3u".equalsIgnoreCase(format)) {
            return exportAsM3u(playlist, orderedFiles);
        }
        return exportAsJson(playlist, orderedFiles);
    }

    private ResponseEntity<?> exportAsM3u(Playlist playlist, List<MusicFile> files) {
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        sb.append("#PLAYLIST:").append(playlist.getName()).append("\n");
        for (MusicFile f : files) {
            sb.append("#EXTINF:-1,");
            if (f.getArtist() != null && !f.getArtist().isBlank()) {
                sb.append(f.getArtist()).append(" - ");
            }
            sb.append(f.getTitle() != null ? f.getTitle() : f.getFileName());
            sb.append("\n");
            sb.append(f.getFileName()).append("\n");
        }
        String filename = playlist.getName().replaceAll("[^a-zA-Z0-9._-]", "_") + ".m3u";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("audio/x-mpegurl"))
                .body(sb.toString());
    }

    private ResponseEntity<?> exportAsJson(Playlist playlist, List<MusicFile> files) {
        List<Map<String, String>> tracks = files.stream().map(f -> {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("title", f.getTitle());
            m.put("artist", f.getArtist());
            m.put("album", f.getAlbum());
            m.put("year", f.getYear());
            m.put("fileName", f.getFileName());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", playlist.getName());
        body.put("trackCount", tracks.size());
        body.put("tracks", tracks);

        String filename = playlist.getName().replaceAll("[^a-zA-Z0-9._-]", "_") + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
