package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.dto.MusicFileDTO;
import com.stellarideas.grooves.dto.ScanRequest;
import com.stellarideas.grooves.dto.UpdateGenreRequest;
import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.Playlist;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.MusicFileRepository;
import com.stellarideas.grooves.repository.PlaylistRepository;
import com.stellarideas.grooves.repository.UserRepository;
import com.stellarideas.grooves.service.MusicScannerService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/library")
public class LibraryController extends BaseController {

    private static final Logger logger = LoggerFactory.getLogger(LibraryController.class);

    private final MusicScannerService scannerService;
    private final MusicFileRepository musicFileRepository;
    private final PlaylistRepository playlistRepository;

    public LibraryController(UserRepository userRepository, MusicScannerService scannerService,
                             MusicFileRepository musicFileRepository, PlaylistRepository playlistRepository) {
        super(userRepository);
        this.scannerService = scannerService;
        this.musicFileRepository = musicFileRepository;
        this.playlistRepository = playlistRepository;
    }

    @PostMapping("/scan")
    public ResponseEntity<?> scanDirectory(@Valid @RequestBody ScanRequest request) {
        String path = request.getPath();
        User user = getCurrentUser();
        try {
            validateScanPath(path);
            logger.info("User '{}' scanning directory: {}", user.getUsername(), path);
            int count = scannerService.scanDirectory(user, path);
            return ResponseEntity.ok(Map.of("message", "Scan completed successfully", "filesFound", count));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Scan failed for user '{}' on path '{}': {}", user.getUsername(), path, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Scan failed: " + e.getMessage()));
        }
    }

    private static final int MAX_PAGE_SIZE = 200;

    @GetMapping("/files")
    public ResponseEntity<?> getFiles(
            @RequestParam(required = false) String genre,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        User user = getCurrentUser();
        size = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        Genre g = null;
        if (genre != null && !genre.isBlank()) {
            try {
                g = Genre.valueOf(genre.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown genre: " + genre +
                        ". Valid values: CLASSIC_ROCK, HARD_ROCK, HAIR_METAL, HEAVY_METAL, THRASH_METAL, OTHER");
            }
        }

        Page<MusicFile> result = (g == null)
                ? musicFileRepository.findByUser(user, PageRequest.of(page, size))
                : musicFileRepository.findByUserAndGenre(user, g, PageRequest.of(page, size));
        return ResponseEntity.ok(Map.of(
                "content", result.getContent().stream().map(MusicFileDTO::from).collect(Collectors.toList()),
                "page", result.getNumber(),
                "size", result.getSize(),
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages()
        ));
    }

    @GetMapping("/files/{id}/stream")
    public ResponseEntity<ResourceRegion> streamFile(
            @PathVariable String id,
            @RequestHeader HttpHeaders headers) throws IOException {
        User user = getCurrentUser();
        Optional<MusicFile> fileOpt = musicFileRepository.findByIdAndUser(id, user);
        if (fileOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Path path = Paths.get(fileOpt.get().getFilePath());
        if (!Files.exists(path) || !Files.isReadable(path)) {
            return ResponseEntity.notFound().build();
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
    public ResponseEntity<?> updateFileGenre(@PathVariable String id, @Valid @RequestBody UpdateGenreRequest request) {
        User user = getCurrentUser();
        Genre genre;
        try {
            genre = Genre.valueOf(request.getGenre().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown genre: " + request.getGenre()));
        }
        Optional<MusicFile> fileOpt = musicFileRepository.findByIdAndUser(id, user);
        if (fileOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        MusicFile file = fileOpt.get();
        file.setGenre(genre);
        musicFileRepository.save(file);
        logger.info("User '{}' updated genre of file '{}' to {}", user.getUsername(), id, genre);
        return ResponseEntity.ok(Map.of("message", "Genre updated", "genre", genre.name()));
    }

    @Transactional
    @DeleteMapping("/files/{id}")
    public ResponseEntity<?> deleteFile(@PathVariable String id) {
        User user = getCurrentUser();
        Optional<MusicFile> fileOpt = musicFileRepository.findByIdAndUser(id, user);
        if (fileOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        musicFileRepository.delete(fileOpt.get());
        removeFileFromPlaylists(id, user);
        logger.info("User '{}' deleted file id={}", user.getUsername(), id);
        return ResponseEntity.ok(Map.of("message", "File deleted"));
    }

    @Transactional
    @DeleteMapping("/files")
    public ResponseEntity<?> clearLibrary() {
        User user = getCurrentUser();
        List<MusicFile> files = musicFileRepository.findByUser(user);
        musicFileRepository.deleteAll(files);
        playlistRepository.deleteByUser(user);
        logger.info("User '{}' cleared their library ({} files removed, playlists deleted)", user.getUsername(), files.size());
        return ResponseEntity.ok(Map.of("message", "Library cleared", "filesRemoved", files.size()));
    }

    private void removeFileFromPlaylists(String fileId, User user) {
        List<Playlist> playlists = playlistRepository.findByUser(user);
        List<Playlist> modified = new java.util.ArrayList<>();
        for (Playlist p : playlists) {
            if (p.getTrackIds().remove(fileId)) {
                modified.add(p);
            }
        }
        if (!modified.isEmpty()) {
            playlistRepository.saveAll(modified);
        }
    }

    private void validateScanPath(String path) throws IOException {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Directory path must not be empty");
        }

        Path requested = Paths.get(path).normalize().toAbsolutePath();

        // Block any path traversal sequences that survive normalization
        if (requested.toString().contains("..")) {
            throw new IllegalArgumentException("Path traversal sequences are not allowed");
        }

        // Path must exist and be a directory before we resolve symlinks
        if (!Files.exists(requested) || !Files.isDirectory(requested)) {
            throw new IllegalArgumentException("Path does not exist or is not a directory");
        }

        // Resolve symlinks to get the true filesystem path
        Path canonical = requested.toRealPath();

        // The canonical path must match the requested path — if a symlink
        // redirects to a different location, the paths will diverge
        if (!canonical.equals(requested)) {
            throw new IllegalArgumentException("Path contains symbolic links — use the real path instead");
        }
    }

}
