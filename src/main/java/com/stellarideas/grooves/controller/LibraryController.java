package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.dto.*;
import com.stellarideas.grooves.model.*;
import com.stellarideas.grooves.security.CurrentUser;
import com.stellarideas.grooves.repository.UserRepository;
import com.stellarideas.grooves.service.AuditService;
import com.stellarideas.grooves.service.LibraryService;
import com.stellarideas.grooves.service.MessageHelper;
import com.stellarideas.grooves.service.MusicScannerService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/library")
public class LibraryController {

    private static final Logger logger = LoggerFactory.getLogger(LibraryController.class);

    private final MusicScannerService scannerService;
    private final LibraryService libraryService;
    private final MessageHelper msg;
    private final AuditService auditService;
    private final UserRepository userRepository;

    public LibraryController(MusicScannerService scannerService,
                             LibraryService libraryService,
                             MessageHelper msg,
                             AuditService auditService,
                             UserRepository userRepository) {
        this.scannerService = scannerService;
        this.libraryService = libraryService;
        this.msg = msg;
        this.auditService = auditService;
        this.userRepository = userRepository;
    }

    @PostMapping("/scan")
    public ResponseEntity<?> scanDirectory(@CurrentUser User user, @Valid @RequestBody ScanRequest request) {
        String path = request.getPath();
        try {
            validateScanPath(path);
            auditService.log(user.getUsername(), AuditService.Action.SCAN_DIRECTORY, path);
            ScanResult result = scannerService.scanDirectory(user, path);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", msg.msg("scan.success"));
            body.put("filesFound", result.getSaved());
            body.put("skipped", result.getSkipped());
            body.put("errors", result.getErrors());
            if (!result.getErrorDetails().isEmpty()) {
                body.put("errorDetails", result.getErrorDetails());
            }
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Scan failed for user '{}' on path '{}': {}", user.getUsername(), path, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", msg.msg("scan.failed", e.getMessage())));
        }
    }

    @GetMapping("/files")
    public ResponseEntity<?> getFiles(
            @CurrentUser User user,
            @RequestParam(required = false) String genre,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Genre g = null;
        if (genre != null && !genre.isBlank()) {
            try {
                g = Genre.valueOf(genre.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(msg.msg("genre.unknown.hint", genre));
            }
        }
        Page<MusicFile> result = libraryService.getFiles(user.getId(), g, page, size);
        return ResponseEntity.ok(Map.of(
                "content", result.getContent().stream().map(MusicFileDTO::from).collect(Collectors.toList()),
                "page", result.getNumber(),
                "size", result.getSize(),
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages()
        ));
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchFiles(
            @CurrentUser User user,
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (q == null || q.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Search query must not be empty"));
        }
        Page<MusicFile> result = libraryService.searchFiles(user.getId(), q, page, size);
        return ResponseEntity.ok(Map.of(
                "content", result.getContent().stream().map(MusicFileDTO::from).collect(Collectors.toList()),
                "page", result.getNumber(),
                "size", result.getSize(),
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages(),
                "query", q
        ));
    }

    @GetMapping("/files/{id}/stream")
    public ResponseEntity<ResourceRegion> streamFile(
            @CurrentUser User user,
            @PathVariable String id,
            @RequestHeader HttpHeaders headers) throws IOException {
        Optional<MusicFile> fileOpt = libraryService.findFileByIdAndUserId(id, user.getId());
        if (fileOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Path path = Paths.get(fileOpt.get().getFilePath()).normalize();
        if (!Files.exists(path) || !Files.isReadable(path)) {
            return ResponseEntity.notFound().build();
        }
        if (user.getMusicDirectory() == null || user.getMusicDirectory().isBlank()) {
            logger.warn("Streaming blocked: user '{}' has no music directory configured", user.getUsername());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Path musicDir = Paths.get(user.getMusicDirectory()).normalize();
        if (!path.startsWith(musicDir)) {
            logger.warn("Path traversal blocked: user '{}' attempted to stream '{}' outside music directory '{}'",
                    user.getUsername(), path, musicDir);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Resource resource = new FileSystemResource(path);
        long contentLength = resource.contentLength();
        MediaType mediaType = resolveAudioMediaType(fileOpt.get().getFileName());

        List<HttpRange> ranges = headers.getRange();
        ResourceRegion region;
        HttpStatus status;
        if (ranges.isEmpty()) {
            region = new ResourceRegion(resource, 0, contentLength);
            status = HttpStatus.OK;
        } else {
            HttpRange range = ranges.get(0);
            long start = range.getRangeStart(contentLength);
            long end = range.getRangeEnd(contentLength);
            region = new ResourceRegion(resource, start, end - start + 1);
            status = HttpStatus.PARTIAL_CONTENT;
        }
        return ResponseEntity.status(status)
                .header("Accept-Ranges", "bytes")
                .contentType(mediaType)
                .body(region);
    }

    private MediaType resolveAudioMediaType(String fileName) {
        if (fileName == null) return MediaType.APPLICATION_OCTET_STREAM;
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mp3"))  return MediaType.parseMediaType("audio/mpeg");
        if (lower.endsWith(".flac")) return MediaType.parseMediaType("audio/flac");
        if (lower.endsWith(".m4a"))  return MediaType.parseMediaType("audio/mp4");
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    @PatchMapping("/files/{id}/genre")
    public ResponseEntity<?> updateFileGenre(@CurrentUser User user, @PathVariable String id,
                                             @Valid @RequestBody UpdateGenreRequest request) {
        Genre genre;
        try {
            genre = Genre.valueOf(request.getGenre().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", msg.msg("genre.unknown", request.getGenre())));
        }
        Optional<MusicFile> fileOpt = libraryService.findFileByIdAndUserId(id, user.getId());
        if (fileOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        libraryService.updateGenre(fileOpt.get(), genre, user.getId());
        auditService.log(user.getUsername(), AuditService.Action.GENRE_UPDATE, id, genre.name());
        return ResponseEntity.ok(Map.of("message", msg.msg("genre.updated"), "genre", genre.name()));
    }

    @PatchMapping("/files/{id}/rating")
    public ResponseEntity<?> updateFileRating(@CurrentUser User user, @PathVariable String id,
                                              @Valid @RequestBody UpdateRatingRequest request) {
        Optional<MusicFile> fileOpt = libraryService.findFileByIdAndUserId(id, user.getId());
        if (fileOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        MusicFile file = libraryService.updateRating(fileOpt.get(), request.getRating());
        auditService.log(user.getUsername(), AuditService.Action.RATING_UPDATE, id, String.valueOf(request.getRating()));
        return ResponseEntity.ok(Map.of("rating", file.getRating()));
    }

    @PostMapping("/files/bulk-delete")
    public ResponseEntity<?> bulkDelete(@CurrentUser User user, @Valid @RequestBody BulkDeleteRequest request) {
        int deleted = libraryService.bulkDelete(request.getFileIds(), user.getId());
        auditService.log(user.getUsername(), AuditService.Action.BULK_DELETE, null, deleted + " files");
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    @GetMapping("/files/{id}/cover")
    public ResponseEntity<byte[]> getCoverArt(@CurrentUser User user, @PathVariable String id) {
        Optional<MusicFile> fileOpt = libraryService.findFileByIdAndUserId(id, user.getId());
        if (fileOpt.isEmpty() || !fileOpt.get().isHasCoverArt()) {
            return ResponseEntity.notFound().build();
        }
        MusicFile file = fileOpt.get();
        Optional<CoverArt> artOpt = libraryService.getCoverArt(user.getId(), file.getArtist(), file.getAlbum());
        if (artOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        CoverArt art = artOpt.get();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(art.getMimeType()))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(30)))
                .body(art.getData());
    }

    @GetMapping("/duplicates")
    public ResponseEntity<?> getDuplicates(@CurrentUser User user) {
        return ResponseEntity.ok(libraryService.findDuplicates(user.getId()));
    }

    @DeleteMapping("/files/{id}")
    public ResponseEntity<?> deleteFile(@CurrentUser User user, @PathVariable String id) {
        Optional<MusicFile> fileOpt = libraryService.findFileByIdAndUserId(id, user.getId());
        if (fileOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        libraryService.deleteFile(fileOpt.get(), user.getId());
        auditService.log(user.getUsername(), AuditService.Action.FILE_DELETE, id);
        return ResponseEntity.ok(Map.of("message", msg.msg("library.file.deleted")));
    }

    @DeleteMapping("/files")
    public ResponseEntity<?> clearLibrary(@CurrentUser User user) {
        long fileCount = libraryService.clearLibrary(user.getId());
        auditService.log(user.getUsername(), AuditService.Action.LIBRARY_CLEAR, null, fileCount + " files removed");
        return ResponseEntity.ok(Map.of("message", msg.msg("library.cleared"), "filesRemoved", fileCount));
    }

    // --- Trash endpoints ---

    @GetMapping("/trash")
    public ResponseEntity<?> getTrash(@CurrentUser User user) {
        return ResponseEntity.ok(libraryService.getTrash(user.getId()));
    }

    @PostMapping("/trash/{id}/restore")
    public ResponseEntity<?> restoreFile(@CurrentUser User user, @PathVariable String id) {
        try {
            libraryService.restoreFile(id, user.getId());
            auditService.log(user.getUsername(), AuditService.Action.FILE_RESTORE, id);
            return ResponseEntity.ok(Map.of("message", "File restored"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/trash/{id}")
    public ResponseEntity<?> permanentlyDeleteFile(@CurrentUser User user, @PathVariable String id) {
        try {
            libraryService.permanentlyDeleteFile(id, user.getId());
            auditService.log(user.getUsername(), AuditService.Action.FILE_PERMANENT_DELETE, id);
            return ResponseEntity.ok(Map.of("message", "File permanently deleted"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/trash")
    public ResponseEntity<?> emptyTrash(@CurrentUser User user) {
        libraryService.emptyTrash(user.getId());
        auditService.log(user.getUsername(), AuditService.Action.TRASH_EMPTY);
        return ResponseEntity.ok(Map.of("message", "Trash emptied"));
    }

    // --- Export endpoints ---

    @GetMapping("/export")
    public ResponseEntity<?> exportLibrary(@CurrentUser User user,
                                           @RequestParam(defaultValue = "json") String format) {
        List<MusicFile> files = libraryService.getAllFiles(user.getId());
        if ("csv".equalsIgnoreCase(format)) {
            return exportAsCsv(files);
        }
        List<MusicFileDTO> dtos = files.stream().map(MusicFileDTO::from).collect(Collectors.toList());
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"library.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(dtos);
    }

    private ResponseEntity<?> exportAsCsv(List<MusicFile> files) {
        StringBuilder sb = new StringBuilder();
        sb.append("title,artist,album,year,genre,rating,fileName\n");
        for (MusicFile f : files) {
            sb.append(escapeCsv(f.getTitle())).append(",");
            sb.append(escapeCsv(f.getArtist())).append(",");
            sb.append(escapeCsv(f.getAlbum())).append(",");
            sb.append(escapeCsv(f.getYear())).append(",");
            sb.append(f.getGenre() != null ? f.getGenre().name() : "").append(",");
            sb.append(f.getRating()).append(",");
            sb.append(escapeCsv(f.getFileName())).append("\n");
        }
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"library.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(sb.toString());
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // --- Scan schedule endpoints ---

    @PutMapping("/scan/schedule")
    public ResponseEntity<?> setScanSchedule(@CurrentUser User user,
                                             @Valid @RequestBody com.stellarideas.grooves.dto.ScanScheduleRequest request) {
        user.setScanSchedule(request.getCronExpression());
        user.setScanPath(request.getPath());
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("message", "Scan schedule saved",
                "cronExpression", request.getCronExpression() != null ? request.getCronExpression() : "",
                "path", request.getPath() != null ? request.getPath() : ""));
    }

    @GetMapping("/scan/schedule")
    public ResponseEntity<?> getScanSchedule(@CurrentUser User user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cronExpression", user.getScanSchedule());
        result.put("path", user.getScanPath());
        result.put("lastScheduledScan", user.getLastScheduledScan());
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/scan/schedule")
    public ResponseEntity<?> clearScanSchedule(@CurrentUser User user) {
        user.setScanSchedule(null);
        user.setScanPath(null);
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("message", "Scan schedule cleared"));
    }

    // --- Stats endpoint ---

    @GetMapping("/stats")
    public ResponseEntity<?> getStatistics(@CurrentUser User user) {
        return ResponseEntity.ok(libraryService.getStatistics(user.getId()));
    }

    private void validateScanPath(String path) throws IOException {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException(msg.msg("scan.path.empty"));
        }
        Path requested = Paths.get(path).normalize().toAbsolutePath();
        if (requested.toString().contains("..")) {
            throw new IllegalArgumentException(msg.msg("scan.path.traversal"));
        }
        if (!Files.exists(requested) || !Files.isDirectory(requested)) {
            throw new IllegalArgumentException(msg.msg("scan.path.notfound"));
        }
        Path canonical = requested.toRealPath();
        if (!canonical.equals(requested)) {
            throw new IllegalArgumentException(msg.msg("scan.path.symlink"));
        }
    }
}
