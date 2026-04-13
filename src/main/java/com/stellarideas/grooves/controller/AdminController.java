package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.CoverArtRepository;
import com.stellarideas.grooves.repository.MusicFileRepository;
import com.stellarideas.grooves.repository.PlaylistRepository;
import com.stellarideas.grooves.repository.UserRepository;
import com.stellarideas.grooves.security.CurrentUser;
import com.stellarideas.grooves.service.AuditService;
import com.stellarideas.grooves.service.MessageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private static final Logger logger = LoggerFactory.getLogger(AdminController.class);
    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final MusicFileRepository musicFileRepository;
    private final PlaylistRepository playlistRepository;
    private final CoverArtRepository coverArtRepository;
    private final MessageHelper msg;
    private final AuditService auditService;

    public AdminController(UserRepository userRepository, MusicFileRepository musicFileRepository,
                           PlaylistRepository playlistRepository, CoverArtRepository coverArtRepository,
                           MessageHelper msg, AuditService auditService) {
        this.userRepository = userRepository;
        this.musicFileRepository = musicFileRepository;
        this.playlistRepository = playlistRepository;
        this.coverArtRepository = coverArtRepository;
        this.msg = msg;
        this.auditService = auditService;
    }

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
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        auditService.log(admin.getUsername(), AuditService.Action.ADMIN_VIEW_USERS);
        int effectiveSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<User> result = userRepository.findAll(PageRequest.of(page, effectiveSize));

        var userList = result.getContent().stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("email", u.getEmail());
            m.put("roles", u.getRoles());
            m.put("enabled", u.isEnabled());
            m.put("fileCount", musicFileRepository.countByUserId(u.getId()));
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
    public ResponseEntity<User> getUserById(@PathVariable String id) {
        return userRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Transactional
    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> deleteUser(@CurrentUser User admin, @PathVariable String id) {
        return userRepository.findById(id)
                .map(user -> {
                    long fileCount = musicFileRepository.deleteByUserId(user.getId());
                    long playlistCount = playlistRepository.deleteByUserId(user.getId());
                    coverArtRepository.deleteByUserId(user.getId());
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
}
