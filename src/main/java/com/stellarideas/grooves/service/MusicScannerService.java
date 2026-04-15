package com.stellarideas.grooves.service;

import com.stellarideas.grooves.dto.ScanResult;
import com.stellarideas.grooves.model.CoverArt;
import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.CoverArtRepository;
import com.stellarideas.grooves.repository.MusicFileRepository;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class MusicScannerService implements DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(MusicScannerService.class);

    private static final int DEFAULT_MAX_DEPTH = 20;
    private static final int DEFAULT_PER_FILE_TIMEOUT_SECONDS = 30;

    @Value("${stellar.grooves.scan.maxDepth:" + DEFAULT_MAX_DEPTH + "}")
    private int maxDepth;

    @Value("${stellar.grooves.scan.hardMaxDepth:50}")
    private int hardMaxDepth;

    @Value("${stellar.grooves.scan.batchSize:200}")
    private int batchSize;

    @Value("${stellar.grooves.coverArt.maxBytesPerImage:10485760}")
    private int maxCoverArtBytes;

    @Value("${stellar.grooves.scan.timeoutMinutes:5}")
    private int scanTimeoutMinutes = 5;

    @Value("${stellar.grooves.coverArt.maxBytesPerUser:524288000}")
    private long coverArtQuotaBytes;

    @Value("${stellar.grooves.scan.perFileTimeoutSeconds:" + DEFAULT_PER_FILE_TIMEOUT_SECONDS + "}")
    private int perFileTimeoutSeconds;

    @Value("${stellar.grooves.scan.fileReaderThreads:2}")
    private int fileReaderThreads;

    @Value("${stellar.grooves.scan.supportedExtensions:.mp3,.m4a,.flac}")
    private String supportedExtensionsConfig;

    private Set<String> supportedExtensions;

    private volatile ExecutorService fileReadExecutor;

    /** Per-user locks to prevent concurrent scans from racing on quota checks. */
    private final ConcurrentHashMap<String, ReentrantLock> userScanLocks = new ConcurrentHashMap<>();

    private final MusicCatalogService catalogService;
    private final MusicFileRepository repository;
    private final CoverArtRepository coverArtRepository;
    private final ScanProgressEmitter progressEmitter;
    private final Timer scanTimer;
    private final Counter filesScannedCounter;
    private final Counter scanErrorsCounter;

    public MusicScannerService(MusicCatalogService catalogService, MusicFileRepository repository,
                               CoverArtRepository coverArtRepository, ScanProgressEmitter progressEmitter,
                               MeterRegistry meterRegistry) {
        this.catalogService = catalogService;
        this.repository = repository;
        this.coverArtRepository = coverArtRepository;
        this.progressEmitter = progressEmitter;
        this.scanTimer = Timer.builder("grooves.scan.duration")
                .description("Time spent scanning directories")
                .register(meterRegistry);
        this.filesScannedCounter = Counter.builder("grooves.scan.files")
                .description("Total files scanned")
                .register(meterRegistry);
        this.scanErrorsCounter = Counter.builder("grooves.scan.errors")
                .description("Total scan errors")
                .register(meterRegistry);
    }

    @jakarta.annotation.PostConstruct
    void initExecutor() {
        int threads = Math.max(1, fileReaderThreads);
        fileReadExecutor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "audio-file-reader");
            t.setDaemon(true);
            return t;
        });
        supportedExtensions = Arrays.stream(supportedExtensionsConfig.split(","))
                .map(String::trim)
                .map(ext -> ext.startsWith(".") ? ext.toLowerCase() : "." + ext.toLowerCase())
                .collect(Collectors.toUnmodifiableSet());
        logger.info("Audio file reader pool initialized with {} thread(s), extensions: {}",
                threads, supportedExtensions);
    }

    @Override
    public void destroy() {
        fileReadExecutor.shutdownNow();
        logger.info("Audio file reader executor shut down");
    }

    public ScanResult scanDirectory(User user, String directoryPath) throws IOException {
        ReentrantLock lock = userScanLocks.computeIfAbsent(user.getId(), k -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new IllegalStateException("A scan is already in progress for this user");
        }
        try {
            return scanTimer.record(() -> {
                try {
                    ScanResult result = doScanDirectory(user, directoryPath);
                    filesScannedCounter.increment(result.getSaved());
                    scanErrorsCounter.increment(result.getErrors());
                    return result;
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        } finally {
            lock.unlock();
        }
    }

    private ScanResult doScanDirectory(User user, String directoryPath) throws IOException {
        Path root = Paths.get(directoryPath).normalize();
        int effectiveDepth = Math.min(maxDepth, hardMaxDepth);

        Set<String> existingPaths = repository.findByUserId(user.getId()).stream()
                .map(MusicFile::getFilePath)
                .collect(Collectors.toSet());
        Set<String> existingTitleArtist = repository.findByUserId(user.getId()).stream()
                .filter(f -> f.getTitle() != null && !f.getTitle().isBlank()
                        && f.getArtist() != null && !f.getArtist().isBlank())
                .map(f -> f.getTitle() + "\0" + f.getArtist())
                .collect(Collectors.toSet());

        // Track which artist+album combos already have cover art
        Set<String> coverArtKeys = new HashSet<>();

        // Check current cover art usage against quota
        Long currentUsage = coverArtRepository.getTotalCoverArtSizeByUserId(user.getId());
        long coverArtUsedBytes = currentUsage != null ? currentUsage : 0;
        boolean coverArtQuotaExceeded = coverArtUsedBytes >= coverArtQuotaBytes;

        ScanResult result = new ScanResult();
        List<MusicFile> batch = new ArrayList<>(batchSize);
        Instant deadline = Instant.now().plus(Duration.ofMinutes(scanTimeoutMinutes));

        try (Stream<Path> walk = Files.walk(root, effectiveDepth)) {
            var it = walk
                    .filter(p -> !Files.isSymbolicLink(p))
                    .filter(p -> {
                        String name = p.toString().toLowerCase();
                        return supportedExtensions.stream().anyMatch(name::endsWith);
                    })
                    .iterator();

            while (it.hasNext()) {
                if (Instant.now().isAfter(deadline)) {
                    logger.warn("Scan timed out after {} minutes for user '{}' on path '{}'",
                            scanTimeoutMinutes, user.getUsername(), directoryPath);
                    break;
                }
                Path path = it.next();
                try {
                    if (existingPaths.contains(path.toString())) {
                        result.incrementSkipped();
                        continue;
                    }
                    AudioFile f;
                    try {
                        Future<AudioFile> future = fileReadExecutor.submit(() -> AudioFileIO.read(path.toFile()));
                        f = future.get(perFileTimeoutSeconds, TimeUnit.SECONDS);
                    } catch (TimeoutException te) {
                        logger.warn("Timed out reading file '{}' after {}s", path.getFileName(), perFileTimeoutSeconds);
                        result.addError(path.getFileName().toString(), "File read timed out after " + perFileTimeoutSeconds + "s");
                        continue;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Scan interrupted while reading file", ie);
                    } catch (ExecutionException ee) {
                        Throwable cause = ee.getCause();
                        if (cause instanceof IOException ioe) throw ioe;
                        if (cause instanceof RuntimeException re) throw re;
                        throw new IOException("Failed to read audio file", cause != null ? cause : ee);
                    }
                    Tag tag = f.getTag();

                    String artist = safeGet(tag, FieldKey.ARTIST);
                    String album  = safeGet(tag, FieldKey.ALBUM);
                    String title  = safeGet(tag, FieldKey.TITLE);
                    String year   = safeGet(tag, FieldKey.YEAR);

                    if (!title.isBlank() && !artist.isBlank()
                            && existingTitleArtist.contains(title + "\0" + artist)) {
                        result.incrementSkipped();
                        continue;
                    }

                    Set<Genre> genres = catalogService.identifyGenres(artist);
                    Genre genre = genres.isEmpty() ? Genre.OTHER : genres.iterator().next();
                    List<Genre> additionalGenres = genres.size() > 1
                            ? genres.stream().filter(g -> g != genre).collect(Collectors.toList())
                            : null;

                    // Extract cover art if available and within quota
                    boolean hasCover = false;
                    if (!artist.isBlank() && !album.isBlank()) {
                        String artKey = artist.toLowerCase() + "\0" + album.toLowerCase();
                        if (!coverArtKeys.contains(artKey)) {
                            if (coverArtQuotaExceeded) {
                                logger.debug("Cover art quota exceeded for user '{}', skipping art extraction",
                                        user.getUsername());
                            } else {
                                long artSize = extractCoverArt(tag, user.getId(), artist, album);
                                hasCover = artSize > 0;
                                if (hasCover) {
                                    coverArtKeys.add(artKey);
                                    coverArtUsedBytes += artSize;
                                    coverArtQuotaExceeded = coverArtUsedBytes >= coverArtQuotaBytes;
                                }
                            }
                        } else {
                            hasCover = true; // already extracted for this album
                        }
                    }

                    String fileHash = computeFileHash(path);

                    MusicFile musicFile = MusicFile.builder()
                            .userId(user.getId())
                            .filePath(path.toString())
                            .fileName(path.getFileName().toString())
                            .artist(artist)
                            .album(album)
                            .title(title)
                            .year(year)
                            .genre(genre)
                            .additionalGenres(additionalGenres)
                            .fileHash(fileHash)
                            .hasCoverArt(hasCover)
                            .build();

                    batch.add(musicFile);
                    existingPaths.add(path.toString());
                    if (!title.isBlank() && !artist.isBlank()) {
                        existingTitleArtist.add(title + "\0" + artist);
                    }

                    if (batch.size() >= batchSize) {
                        repository.saveAll(batch);
                        result.addSaved(batch.size());
                        batch.clear();
                    }
                } catch (IOException e) {
                    logger.warn("Skipping file '{}': {}", path.getFileName(), e.getMessage());
                    result.addError(path.getFileName().toString(), e.getMessage());
                } catch (RuntimeException e) {
                    logger.error("Unexpected error processing file '{}': {}", path.getFileName(), e.getMessage(), e);
                    result.addError(path.getFileName().toString(), e.getMessage());
                }
                // Emit progress every 10 files
                int total = result.getSaved() + result.getSkipped() + result.getErrors();
                if (total % 10 == 0) {
                    progressEmitter.sendProgress(user.getId(), result.getSaved(), result.getSkipped(),
                            result.getErrors(), path.getFileName().toString());
                }
            }
        }

        if (!batch.isEmpty()) {
            repository.saveAll(batch);
            result.addSaved(batch.size());
        }

        logger.info("Scan complete for user '{}': {} saved, {} skipped, {} errors",
                user.getUsername(), result.getSaved(), result.getSkipped(), result.getErrors());
        progressEmitter.sendComplete(user.getId(), result.getSaved(), result.getSkipped(), result.getErrors());
        return result;
    }

    /**
     * Extract and store cover art from the audio tag.
     * @return the size in bytes of the stored artwork, or 0 if nothing was stored
     */
    private long extractCoverArt(Tag tag, String userId, String artist, String album) {
        try {
            if (tag == null) return 0;
            Artwork artwork = tag.getFirstArtwork();
            if (artwork == null || artwork.getBinaryData() == null || artwork.getBinaryData().length == 0) {
                return 0;
            }
            int dataLength = artwork.getBinaryData().length;
            if (dataLength > maxCoverArtBytes) {
                logger.warn("Cover art for '{} - {}' exceeds max size ({} bytes), skipping",
                        artist, album, dataLength);
                return 0;
            }
            // Check if we already have art for this album
            if (coverArtRepository.findByUserIdAndArtistAndAlbum(userId, artist, album).isPresent()) {
                return dataLength;
            }
            CoverArt art = new CoverArt();
            art.setUserId(userId);
            art.setArtist(artist);
            art.setAlbum(album);
            art.setMimeType(artwork.getMimeType() != null ? artwork.getMimeType() : "image/jpeg");
            art.setData(artwork.getBinaryData());
            coverArtRepository.save(art);
            return dataLength;
        } catch (RuntimeException e) {
            logger.debug("Failed to extract cover art for '{} - {}': {}", artist, album, e.getMessage());
            return 0;
        }
    }

    private String computeFileHash(Path path) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(path)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    digest.update(buf, 0, n);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            logger.debug("Failed to compute file hash for '{}': {}", path.getFileName(), e.getMessage());
            return null;
        }
    }

    private String safeGet(Tag tag, FieldKey key) {
        try {
            if (tag == null) return "";
            String val = tag.getFirst(key);
            return val != null ? val.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
