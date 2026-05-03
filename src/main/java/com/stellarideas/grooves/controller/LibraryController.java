package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.config.PaginationDefaults;
import com.stellarideas.grooves.dto.*;
import com.stellarideas.grooves.model.*;
import com.stellarideas.grooves.repository.PlaybackQueueRepository;
import com.stellarideas.grooves.security.CurrentUser;
import com.stellarideas.grooves.repository.UserRepository;
import com.stellarideas.grooves.service.AuditService;
import com.stellarideas.grooves.service.FfmpegAvailability;
import com.stellarideas.grooves.service.LibraryService;
import com.stellarideas.grooves.service.MessageHelper;
import com.stellarideas.grooves.service.MusicScannerService;
import com.stellarideas.grooves.service.PlayHistoryService;
import com.stellarideas.grooves.service.ScanPathValidator;
import com.stellarideas.grooves.service.ScanProgressEmitter;
import com.stellarideas.grooves.service.ScanRateLimiter;
import com.stellarideas.grooves.service.UserRateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/library")
@Tag(name = "Library", description = "Music library scanning, file management, search, streaming, export, and playback queue")
@org.springframework.validation.annotation.Validated
public class LibraryController {

    private static final Logger logger = LoggerFactory.getLogger(LibraryController.class);

    @Value("${stellar.grooves.transcode.timeoutSeconds:300}")
    private int transcodeTimeoutSeconds;

    @Value("${stellar.grooves.transcode.maxFileSize:524288000}")
    private long maxTranscodeFileSize;

    @Value("${stellar.grooves.export.maxSize:50000}")
    private int maxExportSize;

    @Value("${stellar.grooves.queue.maxTracks:5000}")
    private int maxQueueTracks;

    private final MusicScannerService scannerService;
    private final LibraryService libraryService;
    private final MessageHelper msg;
    private final AuditService auditService;
    private final UserRepository userRepository;
    private final ScanRateLimiter scanRateLimiter;
    private final PlaybackQueueRepository playbackQueueRepository;
    private final ScanProgressEmitter scanProgressEmitter;
    private final UserRateLimiter userRateLimiter;
    private final ScanPathValidator scanPathValidator;
    private final PlayHistoryService playHistoryService;
    private final FfmpegAvailability ffmpeg;

    public LibraryController(MusicScannerService scannerService,
                             LibraryService libraryService,
                             MessageHelper msg,
                             AuditService auditService,
                             UserRepository userRepository,
                             ScanRateLimiter scanRateLimiter,
                             PlaybackQueueRepository playbackQueueRepository,
                             ScanProgressEmitter scanProgressEmitter,
                             UserRateLimiter userRateLimiter,
                             ScanPathValidator scanPathValidator,
                             PlayHistoryService playHistoryService,
                             FfmpegAvailability ffmpeg) {
        this.scannerService = scannerService;
        this.libraryService = libraryService;
        this.msg = msg;
        this.auditService = auditService;
        this.userRepository = userRepository;
        this.scanRateLimiter = scanRateLimiter;
        this.playbackQueueRepository = playbackQueueRepository;
        this.scanProgressEmitter = scanProgressEmitter;
        this.userRateLimiter = userRateLimiter;
        this.scanPathValidator = scanPathValidator;
        this.playHistoryService = playHistoryService;
        this.ffmpeg = ffmpeg;
    }

    @PostMapping("/scan")
    public ResponseEntity<?> scanDirectory(@CurrentUser User user, @Valid @RequestBody ScanRequest request) {
        if (!scanRateLimiter.tryAcquire(user.getId())) {
            long retryAfter = scanRateLimiter.secondsUntilAllowed(user.getId());
            ProblemDetail pd = GlobalExceptionHandler.problem(HttpStatus.TOO_MANY_REQUESTS,
                    "Scan rate limit exceeded. Please wait before scanning again.",
                    Map.of("retryAfter", retryAfter));
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(retryAfter))
                    .body(pd);
        }
        String path = request.getPath();
        try {
            validateScanPath(path);
            auditService.log(user.getUsername(), AuditService.Action.SCAN_DIRECTORY, path);
            user.setMusicDirectory(path);
            userRepository.save(user);
            com.stellarideas.grooves.model.ScanJob job = scannerService.startAsyncScan(user, path);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", "Scan started");
            body.put("jobId", job.getId());
            body.put("status", job.getStatus().name());
            body.put("queuedAt", job.getQueuedAt());
            return ResponseEntity.accepted().body(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST, e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(GlobalExceptionHandler.problem(HttpStatus.CONFLICT, e.getMessage()));
        } catch (Exception e) {
            logger.error("Scan failed to start for user '{}' on path '{}': {}", user.getUsername(), path, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(GlobalExceptionHandler.problem(HttpStatus.INTERNAL_SERVER_ERROR, msg.msg("scan.failed", "An unexpected error occurred. Please try again.")));
        }
    }

    @GetMapping("/scan/status")
    public ResponseEntity<?> scanStatus(@CurrentUser User user) {
        java.util.Optional<com.stellarideas.grooves.model.ScanJob> active =
                scannerService.findActiveJob(user.getId());
        java.util.Optional<com.stellarideas.grooves.model.ScanJob> latest = active.isPresent()
                ? active
                : scannerService.findLatestJob(user.getId());
        if (latest.isEmpty()) {
            return ResponseEntity.ok(Map.of("status", "NONE"));
        }
        com.stellarideas.grooves.model.ScanJob job = latest.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", job.getId());
        body.put("status", job.getStatus().name());
        body.put("type", job.getType() != null ? job.getType().name() : null);
        body.put("path", job.getPath());
        body.put("filesSaved", job.getFilesSaved());
        body.put("filesSkipped", job.getFilesSkipped());
        body.put("filesErrored", job.getFilesErrored());
        body.put("currentFile", job.getCurrentFile());
        body.put("queuedAt", job.getQueuedAt());
        body.put("startedAt", job.getStartedAt());
        body.put("updatedAt", job.getUpdatedAt());
        body.put("finishedAt", job.getFinishedAt());
        if (job.getErrorMessage() != null) body.put("errorMessage", job.getErrorMessage());
        return ResponseEntity.ok(body);
    }

    @GetMapping(value = "/scan/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter scanProgress(@CurrentUser User user) {
        SseEmitter emitter = scanProgressEmitter.createEmitter(user.getId());
        scannerService.findActiveJob(user.getId()).ifPresent(job ->
                scanProgressEmitter.sendSnapshot(user.getId(),
                        job.getFilesSaved(), job.getFilesSkipped(), job.getFilesErrored(),
                        job.getCurrentFile(), job.getStatus().name()));
        return emitter;
    }

    @GetMapping("/files")
    public ResponseEntity<?> getFiles(
            @CurrentUser User user,
            @RequestParam(required = false) String genre,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(PaginationDefaults.MAX_PAGE_SIZE) int size) {
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
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(required = false) @Size(max = 50) String genre,
            @RequestParam(required = false) @Size(max = 100) String artist,
            @RequestParam(required = false) @Size(max = 4) String year,
            @RequestParam(required = false) @Size(max = 10) String format,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(PaginationDefaults.MAX_PAGE_SIZE) int size) {
        boolean hasQuery = q != null && !q.isBlank();
        boolean hasFilters = (genre != null && !genre.isBlank())
                || (artist != null && !artist.isBlank())
                || (year != null && !year.isBlank())
                || (format != null && !format.isBlank());

        if (!hasQuery && !hasFilters) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST,
                            "Search query or at least one filter (genre, artist, year, format) is required"));
        }

        Genre g = null;
        if (genre != null && !genre.isBlank()) {
            try {
                g = Genre.valueOf(genre.toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest()
                        .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST, msg.msg("genre.unknown.hint", genre)));
            }
        }

        Page<MusicFile> result = libraryService.searchFiles(user.getId(), q, g, artist, year, format, page, size);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", result.getContent().stream().map(MusicFileDTO::from).collect(Collectors.toList()));
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("totalElements", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        if (hasQuery) body.put("query", q);
        if (g != null) body.put("genre", g.name());
        if (artist != null && !artist.isBlank()) body.put("artist", artist);
        if (year != null && !year.isBlank()) body.put("year", year);
        if (format != null && !format.isBlank()) body.put("format", format);
        return ResponseEntity.ok(body);
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
        Path musicDir = Paths.get(user.getMusicDirectory()).toRealPath();
        path = path.toRealPath();
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

    private static final Map<String, TranscodeFormat> TRANSCODE_FORMATS = Map.of(
            "mp3",  new TranscodeFormat("mp3",  "audio/mpeg",      ".mp3",  new String[]{"-f", "mp3", "-ab", "128k"}),
            "ogg",  new TranscodeFormat("ogg",  "audio/ogg",       ".ogg",  new String[]{"-f", "ogg", "-c:a", "libvorbis", "-q:a", "4"}),
            "opus", new TranscodeFormat("opus", "audio/ogg",       ".opus", new String[]{"-f", "opus", "-c:a", "libopus", "-b:a", "96k"})
    );

    @GetMapping("/files/{id}/transcode")
    public ResponseEntity<?> transcodeFile(
            @CurrentUser User user,
            @PathVariable String id,
            @RequestParam(defaultValue = "mp3") String format) throws IOException {
        String formatLower = format.toLowerCase();
        TranscodeFormat tf = TRANSCODE_FORMATS.get(formatLower);
        if (tf == null) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST,
                            "Unsupported format '" + format + "'. Supported: " + String.join(", ", TRANSCODE_FORMATS.keySet())));
        }
        Optional<MusicFile> fileOpt = libraryService.findFileByIdAndUserId(id, user.getId());
        if (fileOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String fileName = fileOpt.get().getFileName().toLowerCase();
        if (fileName.endsWith(tf.extension())) {
            // Already in the requested format — redirect to normal stream
            return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                    .header("Location", "/api/v1/library/files/" + id + "/stream")
                    .build();
        }
        Path path = Paths.get(fileOpt.get().getFilePath()).normalize();
        if (!Files.exists(path) || !Files.isReadable(path)) {
            return ResponseEntity.notFound().build();
        }
        if (user.getMusicDirectory() == null || user.getMusicDirectory().isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Path musicDir = Paths.get(user.getMusicDirectory()).toRealPath();
        path = path.toRealPath();
        if (!path.startsWith(musicDir)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // Reject files that are too large for transcoding
        long fileSize = Files.size(path);
        if (fileSize > maxTranscodeFileSize) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(GlobalExceptionHandler.problem(HttpStatus.PAYLOAD_TOO_LARGE,
                            "File too large for transcoding (max " + (maxTranscodeFileSize / 1024 / 1024) + " MB)"));
        }

        if (!ffmpeg.isAvailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(GlobalExceptionHandler.problem(HttpStatus.SERVICE_UNAVAILABLE,
                            "Transcoding is not available — ffmpeg is not installed on the server."));
        }

        Path sourcePath = path;
        String outputName = sanitizeFilename(fileOpt.get().getFileName().replaceAll("\\.[^.]+$", tf.extension()));

        StreamingResponseBody stream = outputStream -> {
            Process process = null;
            try {
                List<String> cmd = new ArrayList<>();
                cmd.addAll(List.of("ffmpeg", "-i", sourcePath.toString()));
                cmd.addAll(List.of(tf.ffmpegArgs()));
                cmd.add("-");

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(false);
                process = pb.start();

                // Drain stderr in a separate thread to prevent blocking
                Process finalProcess = process;
                Thread stderrDrainer = new Thread(() -> {
                    try (InputStream err = finalProcess.getErrorStream()) {
                        err.readAllBytes();
                    } catch (IOException ignored) {
                        // stderr drain failure is non-fatal
                    }
                }, "ffmpeg-stderr-drain");
                stderrDrainer.setDaemon(true);
                stderrDrainer.start();

                // Stream ffmpeg stdout directly to the HTTP response
                try (InputStream ffmpegOut = process.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = ffmpegOut.read(buf)) != -1) {
                        outputStream.write(buf, 0, n);
                        outputStream.flush();
                    }
                }

                boolean finished = process.waitFor(transcodeTimeoutSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    logger.error("ffmpeg transcoding timed out after {}s for '{}'",
                            transcodeTimeoutSeconds, sourcePath.getFileName());
                    process.destroyForcibly();
                } else if (process.exitValue() != 0) {
                    logger.error("ffmpeg transcoding failed for '{}' with exit code {}",
                            sourcePath.getFileName(), process.exitValue());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Transcoding interrupted for '{}'", sourcePath.getFileName());
            } finally {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(tf.mimeType()))
                .header("Content-Disposition", "inline; filename=\"" + outputName + "\"")
                .body(stream);
    }

    record TranscodeFormat(String name, String mimeType, String extension, String[] ffmpegArgs) {}

    /**
     * Sanitize a filename for use in Content-Disposition headers.
     * Removes characters that could break HTTP header parsing.
     */
    static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) return "download.mp3";
        // Remove or replace characters unsafe in Content-Disposition headers
        String sanitized = filename.replaceAll("[\"\\\\;/\r\n\t]", "_");
        // Collapse multiple underscores
        sanitized = sanitized.replaceAll("_+", "_");
        return sanitized.isBlank() ? "download.mp3" : sanitized;
    }

    @PatchMapping("/files/{id}/genre")
    public ResponseEntity<?> updateFileGenre(@CurrentUser User user, @PathVariable String id,
                                             @Valid @RequestBody UpdateGenreRequest request) {
        Genre genre;
        try {
            genre = Genre.valueOf(request.getGenre().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST, msg.msg("genre.unknown", request.getGenre())));
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

    @PutMapping("/files/{id}/tags")
    public ResponseEntity<?> updateFileTags(@CurrentUser User user, @PathVariable String id,
                                            @Valid @RequestBody UpdateTagsRequest request) {
        Optional<MusicFile> fileOpt = libraryService.findFileByIdAndUserId(id, user.getId());
        if (fileOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            MusicFile file = libraryService.updateTags(fileOpt.get(), request.getTags());
            List<String> tags = file.getCustomTags() != null ? file.getCustomTags() : List.of();
            auditService.log(user.getUsername(), AuditService.Action.TAGS_UPDATE, id, String.valueOf(tags.size()));
            return ResponseEntity.ok(Map.of("tags", tags));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST, e.getMessage()));
        }
    }

    @GetMapping("/tags")
    public ResponseEntity<?> listTags(@CurrentUser User user) {
        List<LibraryService.TagCount> tags = libraryService.listTagsWithCounts(user.getId());
        return ResponseEntity.ok(Map.of("tags", tags));
    }

    @PostMapping("/files/tags/bulk")
    public ResponseEntity<?> bulkUpdateTags(@CurrentUser User user,
                                            @Valid @RequestBody BulkTagsRequest request) {
        if ((request.getAdd() == null || request.getAdd().isEmpty())
                && (request.getRemove() == null || request.getRemove().isEmpty())) {
            return ResponseEntity.badRequest().body(GlobalExceptionHandler.problem(
                    HttpStatus.BAD_REQUEST, "At least one of 'add' or 'remove' must be non-empty"));
        }
        try {
            LibraryService.BulkTagResult result = libraryService.bulkUpdateTags(
                    user.getId(), request.getFileIds(), request.getAdd(), request.getRemove());
            auditService.log(user.getUsername(), AuditService.Action.TAGS_UPDATE,
                    "bulk:" + request.getFileIds().size(),
                    "modified=" + result.modified() + ",notFound=" + result.notFound());
            return ResponseEntity.ok(Map.of(
                    "modified", result.modified(),
                    "notFound", result.notFound()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST, e.getMessage()));
        }
    }

    @PostMapping("/files/{id}/plays")
    public ResponseEntity<?> recordPlay(@CurrentUser User user, @PathVariable String id,
                                        @Valid @RequestBody RecordPlayRequest request) {
        boolean recorded = playHistoryService.recordPlay(user.getId(), id,
                request.getListenedMs(), request.isCompleted());
        if (!recorded) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("recorded", true));
    }

    @GetMapping("/history/recent")
    public ResponseEntity<?> historyRecent(@CurrentUser User user,
                                           @RequestParam(required = false) String window,
                                           @RequestParam(required = false) @Min(0) Integer page,
                                           @RequestParam(required = false) @Min(1) @Max(PaginationDefaults.MAX_PAGE_SIZE) Integer size) {
        com.stellarideas.grooves.service.PlayHistoryService.Window w =
                com.stellarideas.grooves.service.PlayHistoryService.Window.parse(window);
        int p = page != null ? page : 0;
        int s = size != null ? size : 50;
        org.springframework.data.domain.Page<com.stellarideas.grooves.service.PlayHistoryService.RecentPlay> result =
                playHistoryService.getRecentPlays(user.getId(), w, p, s);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("window", w.name());
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("total", result.getTotalElements());
        body.put("items", result.getContent());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/history/top-tracks")
    public ResponseEntity<?> historyTopTracks(@CurrentUser User user,
                                              @RequestParam(required = false) String window,
                                              @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        com.stellarideas.grooves.service.PlayHistoryService.Window w =
                com.stellarideas.grooves.service.PlayHistoryService.Window.parse(window);
        int l = limit != null ? limit : 25;
        return ResponseEntity.ok(Map.of(
                "window", w.name(),
                "items", playHistoryService.getTopTracks(user.getId(), w, l)));
    }

    @GetMapping("/history/top-artists")
    public ResponseEntity<?> historyTopArtists(@CurrentUser User user,
                                               @RequestParam(required = false) String window,
                                               @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        com.stellarideas.grooves.service.PlayHistoryService.Window w =
                com.stellarideas.grooves.service.PlayHistoryService.Window.parse(window);
        int l = limit != null ? limit : 25;
        return ResponseEntity.ok(Map.of(
                "window", w.name(),
                "items", playHistoryService.getTopArtists(user.getId(), w, l)));
    }

    @PostMapping("/files/bulk-delete")
    public ResponseEntity<?> bulkDelete(@CurrentUser User user, @Valid @RequestBody BulkDeleteRequest request) {
        ResponseEntity<?> rateLimited = rateLimitResponse(user.getId(), "bulk-delete");
        if (rateLimited != null) return rateLimited;
        long deleted = libraryService.bulkDelete(request.getFileIds(), user.getId());
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
    public ResponseEntity<?> getDuplicates(
            @CurrentUser User user,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(PaginationDefaults.MAX_PAGE_SIZE) int size) {
        return ResponseEntity.ok(libraryService.findDuplicates(user.getId(), page, size));
    }

    @GetMapping("/duplicates/by-hash")
    public ResponseEntity<?> getHashDuplicates(
            @CurrentUser User user,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(PaginationDefaults.MAX_PAGE_SIZE) int size) {
        return ResponseEntity.ok(libraryService.findHashDuplicates(user.getId(), page, size));
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
    public ResponseEntity<?> getTrash(@CurrentUser User user,
                                      @RequestParam(defaultValue = "0") @Min(0) int page,
                                      @RequestParam(defaultValue = "50") @Min(1) @Max(PaginationDefaults.MAX_PAGE_SIZE) int size) {
        Page<MusicFileDTO> result = libraryService.getTrash(user.getId(), page, size);
        return ResponseEntity.ok(Map.of(
                "content", result.getContent(),
                "page", result.getNumber(),
                "size", result.getSize(),
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages()
        ));
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

    // maxExportSize configured via @Value above

    @GetMapping("/export")
    public ResponseEntity<?> exportLibrary(@CurrentUser User user,
                                           @RequestParam(defaultValue = "json") String format) {
        ResponseEntity<?> rateLimited = rateLimitResponse(user.getId(), "export");
        if (rateLimited != null) return rateLimited;

        if ("csv".equalsIgnoreCase(format)) {
            List<MusicFile> files = libraryService.getAllFiles(user.getId());
            if (files.size() > maxExportSize) {
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                        .body(GlobalExceptionHandler.problem(HttpStatus.PAYLOAD_TOO_LARGE,
                                "Library too large to export (" + files.size() + " tracks). Maximum is " + maxExportSize + "."));
            }
            return exportAsCsv(files);
        }

        // Stream JSON export to avoid loading entire library into memory
        String userId = user.getId();
        StreamingResponseBody stream = outputStream -> {
            com.fasterxml.jackson.core.JsonGenerator gen =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .getFactory().createGenerator(outputStream);
            gen.writeStartArray();
            int count = 0;
            for (MusicFile file : libraryService.getAllFiles(userId)) {
                if (count >= maxExportSize) break;
                gen.writeObject(MusicFileDTO.from(file));
                count++;
                if (count % 500 == 0) {
                    gen.flush();
                }
            }
            gen.writeEndArray();
            gen.flush();
        };

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"library.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(stream);
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

    // --- Backup/Restore endpoints ---

    @GetMapping("/backup")
    public ResponseEntity<?> backupLibrary(@CurrentUser User user) {
        com.stellarideas.grooves.dto.LibraryBackup backup = libraryService.createBackup(user.getId(), user.getUsername());
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"stellar-grooves-backup.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(backup);
    }

    @PostMapping("/restore")
    public ResponseEntity<?> restoreLibrary(@CurrentUser User user,
                                            @RequestBody com.stellarideas.grooves.dto.LibraryBackup backup) {
        if (backup.getTracks() == null || backup.getTracks().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST, "Backup contains no tracks"));
        }
        Map<String, Object> result = libraryService.restoreBackup(backup, user.getId());
        auditService.log(user.getUsername(), AuditService.Action.LIBRARY_RESTORE,
                result.get("tracksRestored") + " tracks, " + result.get("playlistsRestored") + " playlists");
        return ResponseEntity.ok(result);
    }

    // --- Scan schedule endpoints ---

    @PutMapping("/scan/schedule")
    public ResponseEntity<?> setScanSchedule(@CurrentUser User user,
                                             @Valid @RequestBody com.stellarideas.grooves.dto.ScanScheduleRequest request) {
        // Validate cron expression
        try {
            org.springframework.scheduling.support.CronExpression.parse(request.getCronExpression());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST,
                            "Invalid cron expression: " + e.getMessage()));
        }

        // Validate scan path before saving
        try {
            validateScanPath(request.getPath());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST, e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(GlobalExceptionHandler.problem(HttpStatus.INTERNAL_SERVER_ERROR,
                            "Failed to validate scan path"));
        }

        user.setScanSchedule(request.getCronExpression());
        user.setScanPath(request.getPath());
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("message", "Scan schedule saved",
                "cronExpression", request.getCronExpression(),
                "path", request.getPath()));
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

    // --- Playback queue endpoints ---

    @GetMapping("/queue")
    public ResponseEntity<?> getQueue(@CurrentUser User user) {
        return playbackQueueRepository.findByUserId(user.getId())
                .map(q -> ResponseEntity.ok((Object) PlaybackQueueDTO.from(q)))
                .orElse(ResponseEntity.ok(Map.of("trackIds", List.of(), "currentTrackId", "", "shuffle", false)));
    }

    @PutMapping("/queue")
    public ResponseEntity<?> saveQueue(@CurrentUser User user, @Valid @RequestBody PlaybackQueueDTO dto) {
        List<String> trackIds = dto.getTrackIds() != null ? dto.getTrackIds() : List.of();
        if (trackIds.size() > maxQueueTracks) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST,
                            "Queue cannot exceed " + maxQueueTracks + " tracks"));
        }
        // Validate that all track IDs belong to the requesting user
        if (!trackIds.isEmpty()) {
            Set<String> ownedIds = libraryService.findOwnedTrackIds(trackIds, user.getId());
            trackIds = trackIds.stream().filter(ownedIds::contains).collect(Collectors.toList());
        }
        PlaybackQueue queue = playbackQueueRepository.findByUserId(user.getId())
                .orElseGet(() -> {
                    PlaybackQueue q = new PlaybackQueue();
                    q.setUserId(user.getId());
                    return q;
                });
        queue.setTrackIds(trackIds);
        String currentTrackId = dto.getCurrentTrackId();
        if (currentTrackId != null && !trackIds.contains(currentTrackId)) {
            currentTrackId = null;
        }
        queue.setCurrentTrackId(currentTrackId);
        queue.setShuffle(dto.isShuffle());
        playbackQueueRepository.save(queue);
        return ResponseEntity.ok(Map.of("message", "Queue saved"));
    }

    @DeleteMapping("/queue")
    public ResponseEntity<?> clearQueue(@CurrentUser User user) {
        playbackQueueRepository.deleteByUserId(user.getId());
        return ResponseEntity.ok(Map.of("message", "Queue cleared"));
    }

    private ResponseEntity<?> rateLimitResponse(String userId, String operation) {
        if (!userRateLimiter.tryAcquire(userId, operation)) {
            long retryAfter = userRateLimiter.secondsUntilAllowed(userId, operation);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(retryAfter))
                    .body(GlobalExceptionHandler.problem(HttpStatus.TOO_MANY_REQUESTS,
                            "Rate limit exceeded for " + operation + ". Please try again later."));
        }
        return null;
    }

    private void validateScanPath(String path) throws IOException {
        scanPathValidator.validate(path);
    }
}
