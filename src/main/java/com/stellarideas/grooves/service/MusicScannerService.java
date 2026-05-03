package com.stellarideas.grooves.service;

import com.stellarideas.grooves.dto.ScanResult;
import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.ScanJob;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.MusicFileRepository;
import com.stellarideas.grooves.repository.ScanJobRepository;
import com.stellarideas.grooves.repository.UserRepository;
import com.stellarideas.grooves.service.scan.AudioMetadataReader;
import com.stellarideas.grooves.service.scan.CoverArtHandler;
import com.stellarideas.grooves.service.scan.FileHasher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Orchestrates directory scans: discovers audio files, delegates per-file work to
 * {@link AudioMetadataReader}/{@link CoverArtHandler}/{@link FileHasher}, batches
 * saves, emits progress, and persists scan state to {@link ScanJob}.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #startAsyncScan(User, String)} — creates a {@link ScanJob} and returns immediately;
 *       the scan runs on the {@code scanTaskExecutor} pool. Used by HTTP callers so the request
 *       thread isn't bound to the scan duration.</li>
 *   <li>{@link #scanDirectorySync(User, String, ScanJob.Type)} — blocks until the scan
 *       completes. Still creates a {@link ScanJob} for observability. Used by
 *       {@link ScheduledScanService} so the cron can update {@code lastScheduledScan}
 *       after success.</li>
 * </ul>
 */
@Service
public class MusicScannerService {

    private static final Logger logger = LoggerFactory.getLogger(MusicScannerService.class);
    private static final int PROGRESS_EMIT_EVERY_N = 10;
    private static final List<ScanJob.Status> ACTIVE_STATUSES =
            List.of(ScanJob.Status.QUEUED, ScanJob.Status.RUNNING);

    @Value("${stellar.grooves.scan.maxDepth:20}")
    private int maxDepth;

    @Value("${stellar.grooves.scan.hardMaxDepth:50}")
    private int hardMaxDepth;

    @Value("${stellar.grooves.scan.batchSize:200}")
    private int batchSize;

    @Value("${stellar.grooves.scan.timeoutMinutes:5}")
    private int scanTimeoutMinutes = 5;

    @Value("${stellar.grooves.scan.supportedExtensions:.mp3,.m4a,.flac}")
    private String supportedExtensionsConfig;

    private Set<String> supportedExtensions;

    private final ConcurrentHashMap<String, ReentrantLock> userScanLocks = new ConcurrentHashMap<>();

    private final MusicCatalogService catalogService;
    private final MusicFileRepository repository;
    private final ScanJobRepository scanJobRepository;
    private final UserRepository userRepository;
    private final ScanProgressEmitter progressEmitter;
    private final ScanPathValidator pathValidator;
    private final AudioMetadataReader metadataReader;
    private final CoverArtHandler coverArtHandler;
    private final FileHasher fileHasher;
    private final Timer scanTimer;
    private final Counter filesScannedCounter;
    private final Counter scanErrorsCounter;
    private final LibraryStatsCache statsCache;

    public MusicScannerService(MusicCatalogService catalogService,
                               MusicFileRepository repository,
                               ScanJobRepository scanJobRepository,
                               UserRepository userRepository,
                               ScanProgressEmitter progressEmitter,
                               ScanPathValidator pathValidator,
                               AudioMetadataReader metadataReader,
                               CoverArtHandler coverArtHandler,
                               FileHasher fileHasher,
                               LibraryStatsCache statsCache,
                               MeterRegistry meterRegistry) {
        this.catalogService = catalogService;
        this.repository = repository;
        this.scanJobRepository = scanJobRepository;
        this.userRepository = userRepository;
        this.progressEmitter = progressEmitter;
        this.pathValidator = pathValidator;
        this.metadataReader = metadataReader;
        this.coverArtHandler = coverArtHandler;
        this.fileHasher = fileHasher;
        this.statsCache = statsCache;
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

    @PostConstruct
    void init() {
        supportedExtensions = Arrays.stream(supportedExtensionsConfig.split(","))
                .map(String::trim)
                .map(ext -> ext.startsWith(".") ? ext.toLowerCase() : "." + ext.toLowerCase())
                .collect(Collectors.toUnmodifiableSet());
        logger.info("Scanner supported extensions: {}", supportedExtensions);
    }

    // ─── Public entry points ────────────────────────────────────

    /**
     * Queue an async scan. Returns the persisted {@link ScanJob} immediately.
     *
     * @throws IllegalArgumentException if the path fails validation
     * @throws IllegalStateException    if another scan is already active for this user
     */
    public ScanJob startAsyncScan(User user, String directoryPath) throws IOException {
        pathValidator.validate(directoryPath);
        if (scanJobRepository.countByUserIdAndStatusIn(user.getId(), ACTIVE_STATUSES) > 0) {
            throw new IllegalStateException("A scan is already in progress for this user");
        }
        ScanJob job = newJob(user, directoryPath, ScanJob.Type.MANUAL, ScanJob.Status.QUEUED);
        job = scanJobRepository.save(job);
        runAsync(job.getId());
        return job;
    }

    /**
     * Run a scan synchronously and return its {@link ScanResult} — used by the scheduled
     * scan path which needs to know when the scan finishes to update user state.
     */
    public ScanResult scanDirectorySync(User user, String directoryPath, ScanJob.Type type) throws IOException {
        Path validatedRoot = pathValidator.validate(directoryPath);
        if (scanJobRepository.countByUserIdAndStatusIn(user.getId(), ACTIVE_STATUSES) > 0) {
            throw new IllegalStateException("A scan is already in progress for this user");
        }
        ScanJob job = newJob(user, directoryPath, type, ScanJob.Status.RUNNING);
        job.setStartedAt(Instant.now());
        job = scanJobRepository.save(job);
        return executeScan(user, validatedRoot, job);
    }

    /** Return the user's most recent scan job (active or terminal), if any. */
    public Optional<ScanJob> findLatestJob(String userId) {
        return scanJobRepository.findTopByUserIdOrderByStartedAtDesc(userId);
    }

    /** Return the user's active scan job (QUEUED or RUNNING), if any. */
    public Optional<ScanJob> findActiveJob(String userId) {
        return scanJobRepository.findTopByUserIdAndStatusInOrderByStartedAtDesc(userId, ACTIVE_STATUSES);
    }

    // ─── Async worker ──────────────────────────────────────────

    /**
     * Background entry point for queued scans. Spring's self-invocation rule applies:
     * this must be called via the bean (i.e. from {@link #startAsyncScan}) to get the
     * proxy-driven async behavior.
     */
    @Async("scanTaskExecutor")
    public void runAsync(String jobId) {
        ScanJob job = scanJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            logger.warn("Async scan fired but ScanJob '{}' not found", jobId);
            return;
        }
        User user = userRepository.findById(job.getUserId()).orElse(null);
        if (user == null) {
            finishJob(job, ScanJob.Status.FAILED, "User no longer exists");
            return;
        }
        try {
            MDC.put("correlationId", UUID.randomUUID().toString());
            MDC.put("audit.user", user.getUsername());
            Path validatedRoot = pathValidator.validate(job.getPath());
            job.setStatus(ScanJob.Status.RUNNING);
            job.setStartedAt(Instant.now());
            job.setUpdatedAt(Instant.now());
            scanJobRepository.save(job);
            executeScan(user, validatedRoot, job);
        } catch (IllegalArgumentException e) {
            finishJob(job, ScanJob.Status.FAILED, e.getMessage());
            progressEmitter.sendError(user.getId(), e.getMessage());
        } catch (IllegalStateException e) {
            finishJob(job, ScanJob.Status.FAILED, e.getMessage());
            progressEmitter.sendError(user.getId(), e.getMessage());
        } catch (Throwable t) {
            logger.error("Async scan failed for job '{}': {}", jobId, t.getMessage(), t);
            finishJob(job, ScanJob.Status.FAILED, t.getMessage());
            progressEmitter.sendError(user.getId(), t.getMessage());
        } finally {
            MDC.clear();
        }
    }

    // ─── Core scan loop (shared by sync + async paths) ─────────

    private ScanResult executeScan(User user, Path root, ScanJob job) throws IOException {
        ReentrantLock lock = userScanLocks.computeIfAbsent(user.getId(), k -> new ReentrantLock());
        if (!lock.tryLock()) {
            String msg = "A scan is already in progress for this user";
            finishJob(job, ScanJob.Status.FAILED, msg);
            throw new IllegalStateException(msg);
        }
        try {
            return scanTimer.record(() -> {
                try {
                    ScanResult result = doScan(user, root, job);
                    filesScannedCounter.increment(result.getSaved());
                    scanErrorsCounter.increment(result.getErrors());
                    return result;
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        } catch (java.io.UncheckedIOException e) {
            finishJob(job, ScanJob.Status.FAILED, e.getCause().getMessage());
            throw e.getCause();
        } catch (RuntimeException e) {
            // RuntimeException from doScan would otherwise leave the ScanJob stuck in RUNNING.
            // Critical for scanDirectorySync (scheduled scans), where no caller marks the job FAILED.
            logger.error("Scan failed for user '{}' on path '{}': {}", user.getUsername(), root, e.getMessage(), e);
            finishJob(job, ScanJob.Status.FAILED, e.getMessage());
            throw e;
        } finally {
            lock.unlock();
            userScanLocks.compute(user.getId(), (k, v) -> {
                if (v != null && !v.isLocked() && !v.hasQueuedThreads()) return null;
                return v;
            });
        }
    }

    private ScanResult doScan(User user, Path root, ScanJob job) throws IOException {
        int effectiveDepth = Math.min(maxDepth, hardMaxDepth);

        List<MusicFile> allUserFiles = repository.findByUserId(user.getId());
        Set<String> existingPaths = allUserFiles.stream()
                .map(MusicFile::getFilePath)
                .collect(Collectors.toSet());
        Set<String> existingTitleArtist = allUserFiles.stream()
                .filter(f -> f.getTitle() != null && !f.getTitle().isBlank()
                        && f.getArtist() != null && !f.getArtist().isBlank())
                .map(f -> f.getTitle() + "\0" + f.getArtist())
                .collect(Collectors.toSet());

        CoverArtHandler.Budget artBudget = coverArtHandler.newBudget(user.getId());
        ScanResult result = new ScanResult();
        List<MusicFile> batch = new ArrayList<>(batchSize);
        Instant deadline = Instant.now().plus(Duration.ofMinutes(scanTimeoutMinutes));
        boolean timedOut = false;

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
                            scanTimeoutMinutes, user.getUsername(), root);
                    timedOut = true;
                    break;
                }
                Path path = it.next();
                try {
                    MusicFile file = processFile(path, user.getId(), existingPaths,
                            existingTitleArtist, artBudget, result);
                    if (file != null) {
                        batch.add(file);
                        if (batch.size() >= batchSize) {
                            repository.saveAll(batch);
                            result.addSaved(batch.size());
                            batch.clear();
                        }
                    }
                } catch (IOException e) {
                    logger.warn("Skipping file '{}': {}", path.getFileName(), e.getMessage());
                    result.addError(path.getFileName().toString(), e.getMessage());
                } catch (RuntimeException e) {
                    logger.error("Unexpected error processing '{}': {}", path.getFileName(), e.getMessage(), e);
                    result.addError(path.getFileName().toString(), e.getMessage());
                }

                int total = result.getSaved() + result.getSkipped() + result.getErrors();
                if (total % PROGRESS_EMIT_EVERY_N == 0) {
                    emitProgress(job, result, path.getFileName().toString());
                }
            }
        }

        if (!batch.isEmpty()) {
            repository.saveAll(batch);
            result.addSaved(batch.size());
        }

        if (result.getSaved() > 0) statsCache.invalidate(user.getId());

        logger.info("Scan complete for user '{}': {} saved, {} skipped, {} errors",
                user.getUsername(), result.getSaved(), result.getSkipped(), result.getErrors());

        ScanJob.Status finalStatus = timedOut ? ScanJob.Status.TIMED_OUT : ScanJob.Status.COMPLETED;
        String timeoutMessage = timedOut
                ? "Scan exceeded " + scanTimeoutMinutes + "-minute timeout"
                : null;
        finalizeJob(job, finalStatus, result, timeoutMessage);
        progressEmitter.sendComplete(user.getId(), result.getSaved(), result.getSkipped(), result.getErrors());
        return result;
    }

    private MusicFile processFile(Path path, String userId,
                                  Set<String> existingPaths, Set<String> existingTitleArtist,
                                  CoverArtHandler.Budget artBudget, ScanResult result) throws IOException {
        if (existingPaths.contains(path.toString())) {
            result.incrementSkipped();
            return null;
        }
        AudioMetadataReader.AudioMetadata meta = metadataReader.read(path);

        if (meta.hasArtistAndTitle()
                && existingTitleArtist.contains(meta.title() + "\0" + meta.artist())) {
            result.incrementSkipped();
            return null;
        }

        Set<Genre> genres = catalogService.identifyGenres(meta.artist());
        Genre primary = genres.isEmpty() ? Genre.OTHER : genres.iterator().next();
        List<Genre> additional = genres.size() > 1
                ? genres.stream().filter(g -> g != primary).collect(Collectors.toList())
                : null;

        boolean hasCover = coverArtHandler.process(meta.tag(), userId, meta.artist(), meta.album(), artBudget);
        String hash = fileHasher.sha256(path);

        MusicFile file = MusicFile.builder()
                .userId(userId)
                .filePath(path.toString())
                .fileName(path.getFileName().toString())
                .artist(meta.artist())
                .album(meta.album())
                .title(meta.title())
                .year(com.stellarideas.grooves.util.YearParser.parse(meta.year()))
                .genre(primary)
                .additionalGenres(additional)
                .fileHash(hash)
                .hasCoverArt(hasCover)
                .build();

        existingPaths.add(path.toString());
        if (meta.hasArtistAndTitle()) {
            existingTitleArtist.add(meta.title() + "\0" + meta.artist());
        }
        return file;
    }

    // ─── ScanJob lifecycle helpers ─────────────────────────────

    private ScanJob newJob(User user, String path, ScanJob.Type type, ScanJob.Status initialStatus) {
        ScanJob job = new ScanJob();
        job.setUserId(user.getId());
        job.setPath(path);
        job.setStatus(initialStatus);
        job.setType(type);
        Instant now = Instant.now();
        job.setQueuedAt(now);
        job.setUpdatedAt(now);
        return job;
    }

    private void emitProgress(ScanJob job, ScanResult result, String currentFile) {
        job.setFilesSaved(result.getSaved());
        job.setFilesSkipped(result.getSkipped());
        job.setFilesErrored(result.getErrors());
        job.setCurrentFile(currentFile);
        job.setUpdatedAt(Instant.now());
        scanJobRepository.save(job);
        progressEmitter.sendProgress(job.getUserId(), result.getSaved(), result.getSkipped(),
                result.getErrors(), currentFile);
    }

    private void finalizeJob(ScanJob job, ScanJob.Status status, ScanResult result, String errorMessage) {
        job.setStatus(status);
        job.setFilesSaved(result.getSaved());
        job.setFilesSkipped(result.getSkipped());
        job.setFilesErrored(result.getErrors());
        job.setCurrentFile(null);
        if (errorMessage != null) {
            job.setErrorMessage(errorMessage);
        }
        Instant now = Instant.now();
        job.setUpdatedAt(now);
        job.setFinishedAt(now);
        scanJobRepository.save(job);
    }

    private void finishJob(ScanJob job, ScanJob.Status status, String errorMessage) {
        job.setStatus(status);
        job.setErrorMessage(errorMessage);
        Instant now = Instant.now();
        job.setUpdatedAt(now);
        job.setFinishedAt(now);
        scanJobRepository.save(job);
    }
}
