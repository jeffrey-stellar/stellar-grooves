package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.CoverArtRepository;
import com.stellarideas.grooves.repository.MusicFileRepository;
import com.stellarideas.grooves.repository.PlayEventRepository;
import com.stellarideas.grooves.repository.PlaylistRepository;
import com.stellarideas.grooves.repository.UserRepository;
import com.stellarideas.grooves.security.CurrentUser;
import com.stellarideas.grooves.service.AdminRateLimiter;
import com.stellarideas.grooves.service.AuditService;
import com.stellarideas.grooves.service.MessageHelper;
import com.stellarideas.grooves.service.MusicCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import com.stellarideas.grooves.config.PaginationDefaults;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "Admin-only user management, system stats, and genre catalog administration")
@org.springframework.validation.annotation.Validated
public class AdminController {

    private static final Logger logger = LoggerFactory.getLogger(AdminController.class);

    private final UserRepository userRepository;
    private final MusicFileRepository musicFileRepository;
    private final PlaylistRepository playlistRepository;
    private final CoverArtRepository coverArtRepository;
    private final PlayEventRepository playEventRepository;
    private final MessageHelper msg;
    private final AuditService auditService;
    private final MusicCatalogService catalogService;
    private final AdminRateLimiter adminRateLimiter;

    public AdminController(UserRepository userRepository, MusicFileRepository musicFileRepository,
                           PlaylistRepository playlistRepository, CoverArtRepository coverArtRepository,
                           PlayEventRepository playEventRepository,
                           MessageHelper msg, AuditService auditService, MusicCatalogService catalogService,
                           AdminRateLimiter adminRateLimiter) {
        this.userRepository = userRepository;
        this.musicFileRepository = musicFileRepository;
        this.playlistRepository = playlistRepository;
        this.coverArtRepository = coverArtRepository;
        this.playEventRepository = playEventRepository;
        this.msg = msg;
        this.auditService = auditService;
        this.catalogService = catalogService;
        this.adminRateLimiter = adminRateLimiter;
    }

    private ResponseEntity<?> rateLimit(User admin, String operation) {
        if (!adminRateLimiter.tryAcquire(admin.getId(), operation)) {
            long retryAfter = adminRateLimiter.secondsUntilAllowed(admin.getId(), operation);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(retryAfter))
                    .body(GlobalExceptionHandler.problem(HttpStatus.TOO_MANY_REQUESTS,
                            "Admin rate limit exceeded for " + operation + ". Please try again later."));
        }
        return null;
    }

    @Operation(summary = "System stats", description = "Get total users, files, and playlists counts")
    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalUsers", userRepository.count());
        stats.put("totalFiles", musicFileRepository.count());
        stats.put("totalPlaylists", playlistRepository.count());
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/users")
    public ResponseEntity<?> getAllUsers(
            @CurrentUser User admin,
            @RequestParam(defaultValue = "0") @jakarta.validation.constraints.Min(0) int page,
            @RequestParam(defaultValue = "" + PaginationDefaults.DEFAULT_PAGE_SIZE)
            @jakarta.validation.constraints.Min(1)
            @jakarta.validation.constraints.Max(PaginationDefaults.ADMIN_MAX_PAGE_SIZE) int size) {
        ResponseEntity<?> throttle = rateLimit(admin, "list-users");
        if (throttle != null) return throttle;
        auditService.log(admin.getUsername(), AuditService.Action.ADMIN_VIEW_USERS);
        int effectiveSize = PaginationDefaults.clamp(size, PaginationDefaults.ADMIN_MAX_PAGE_SIZE);
        Page<User> result = userRepository.findAll(PageRequest.of(page, effectiveSize));

        // Batch-fetch file counts in a single aggregation instead of N+1 queries
        List<String> userIds = result.getContent().stream().map(User::getId).toList();
        Map<String, Long> fileCounts = new HashMap<>();
        if (!userIds.isEmpty()) {
            musicFileRepository.countByUserIdIn(userIds).forEach(doc ->
                    fileCounts.put(doc.getString("_id"), doc.get("count", Number.class).longValue()));
        }

        var userList = result.getContent().stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("email", u.getEmail());
            m.put("roles", u.getRoles());
            m.put("enabled", u.isEnabled());
            m.put("fileCount", fileCounts.getOrDefault(u.getId(), 0L));
            return m;
        }).toList();

        return ResponseEntity.ok(Map.of(
                "content", userList,
                "page", result.getNumber(),
                "size", result.getSize(),
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages()
        ));
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<?> getUserById(@PathVariable String id) {
        return userRepository.findById(id)
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", u.getId());
                    m.put("username", u.getUsername());
                    m.put("email", u.getEmail());
                    m.put("roles", u.getRoles());
                    m.put("enabled", u.isEnabled());
                    m.put("musicDirectory", u.getMusicDirectory());
                    m.put("scanSchedule", u.getScanSchedule());
                    m.put("fileCount", musicFileRepository.countByUserId(u.getId()));
                    return ResponseEntity.ok(m);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @Transactional
    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> deleteUser(@CurrentUser User admin, @PathVariable String id) {
        ResponseEntity<?> throttle = rateLimit(admin, "delete-user");
        if (throttle != null) return throttle;
        return userRepository.findById(id)
                .map(user -> {
                    long fileCount = musicFileRepository.deleteByUserId(user.getId());
                    long playlistCount = playlistRepository.deleteByUserId(user.getId());
                    coverArtRepository.deleteByUserId(user.getId());
                    playEventRepository.deleteByUserId(user.getId());
                    userRepository.deleteById(id);
                    auditService.log(admin.getUsername(), AuditService.Action.ADMIN_DELETE_USER,
                            user.getUsername(), fileCount + " files, " + playlistCount + " playlists removed");
                    return ResponseEntity.ok(Map.of(
                            "message", msg.msg("admin.user.deleted"),
                            "filesRemoved", fileCount,
                            "playlistsRemoved", playlistCount
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // --- Genre Catalog Management ---

    @GetMapping("/catalog")
    public ResponseEntity<?> getCatalog() {
        Map<String, Set<Genre>> catalog = catalogService.getCatalog();
        // Convert to a sorted, human-friendly representation
        Map<String, List<String>> result = new TreeMap<>();
        catalog.forEach((artist, genres) ->
                result.put(artist, genres.stream().map(Genre::name).collect(Collectors.toList())));
        return ResponseEntity.ok(Map.of("artists", result, "count", result.size()));
    }

    @PutMapping("/catalog/{artist}")
    public ResponseEntity<?> putCatalogEntry(
            @CurrentUser User admin,
            @PathVariable String artist,
            @RequestBody Map<String, List<String>> body) {
        ResponseEntity<?> throttle = rateLimit(admin, "catalog-edit");
        if (throttle != null) return throttle;
        List<String> genreNames = body.get("genres");
        if (genreNames == null || genreNames.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(
                            org.springframework.http.HttpStatus.BAD_REQUEST, "genres list is required"));
        }
        Set<Genre> genres = new LinkedHashSet<>();
        for (String name : genreNames) {
            try {
                genres.add(Genre.valueOf(name));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest()
                        .body(GlobalExceptionHandler.problem(
                                org.springframework.http.HttpStatus.BAD_REQUEST, "Unknown genre: " + name));
            }
        }
        catalogService.putCatalogEntry(artist, genres);
        auditService.log(admin.getUsername(), AuditService.Action.ADMIN_UPDATE_CATALOG,
                "artist=" + artist + " genres=" + genres);
        return ResponseEntity.ok(Map.of("message", "Catalog entry updated", "artist", artist));
    }

    @DeleteMapping("/catalog/{artist}")
    public ResponseEntity<?> deleteCatalogEntry(@CurrentUser User admin, @PathVariable String artist) {
        ResponseEntity<?> throttle = rateLimit(admin, "catalog-edit");
        if (throttle != null) return throttle;
        boolean removed = catalogService.removeCatalogEntry(artist);
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        auditService.log(admin.getUsername(), AuditService.Action.ADMIN_UPDATE_CATALOG,
                "removed artist=" + artist);
        return ResponseEntity.ok(Map.of("message", "Catalog entry removed", "artist", artist));
    }

    @PostMapping("/catalog/reload")
    public ResponseEntity<?> reloadCatalog(@CurrentUser User admin) {
        ResponseEntity<?> throttle = rateLimit(admin, "catalog-reload");
        if (throttle != null) return throttle;
        catalogService.reloadCatalog();
        auditService.log(admin.getUsername(), AuditService.Action.ADMIN_UPDATE_CATALOG, "catalog reloaded");
        return ResponseEntity.ok(Map.of("message", "Catalog reloaded", "count", catalogService.catalogSize()));
    }
}
