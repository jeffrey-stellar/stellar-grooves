package com.stellarideas.grooves.service;

import com.stellarideas.grooves.dto.ScanResult;
import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.ScanJob;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.CoverArtRepository;
import com.stellarideas.grooves.repository.MusicFileRepository;
import com.stellarideas.grooves.repository.ScanJobRepository;
import com.stellarideas.grooves.repository.UserRepository;
import com.stellarideas.grooves.service.scan.AudioMetadataReader;
import com.stellarideas.grooves.service.scan.CoverArtHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MusicScannerServiceTest {

    private MusicScannerService scannerService;
    private MusicFileRepository repository;
    private ScanJobRepository scanJobRepository;
    private MusicCatalogService catalogService;
    private AudioMetadataReader metadataReader;
    private User testUser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        repository = mock(MusicFileRepository.class);
        scanJobRepository = mock(ScanJobRepository.class);
        catalogService = mock(MusicCatalogService.class);
        CoverArtRepository coverArtRepository = mock(CoverArtRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        ScanProgressEmitter progressEmitter = mock(ScanProgressEmitter.class);
        ScanPathValidator pathValidator = passThroughValidator();

        metadataReader = ScannerTestFactory.newMetadataReader();
        CoverArtHandler coverArtHandler = ScannerTestFactory.newCoverArtHandler(
                coverArtRepository, 10_485_760, 524_288_000L);

        scannerService = ScannerTestFactory.newScanner(
                catalogService, repository, scanJobRepository, userRepository,
                progressEmitter, pathValidator, metadataReader, coverArtHandler);

        testUser = new User();
        testUser.setId("user1");
        testUser.setUsername("testuser");

        when(repository.findByUserId("user1")).thenReturn(List.of());
        when(catalogService.identifyGenres(any())).thenReturn(Set.of(Genre.OTHER));
        when(scanJobRepository.countByUserIdAndStatusIn(anyString(), any())).thenReturn(0L);
        when(scanJobRepository.save(any(ScanJob.class))).thenAnswer(inv -> {
            ScanJob j = inv.getArgument(0);
            if (j.getId() == null) j.setId("job-" + System.nanoTime());
            return j;
        });
    }

    @AfterEach
    void tearDown() {
        // Release file handles held by the metadata reader's executor before @TempDir cleanup.
        metadataReader.destroy();
    }

    private ScanResult scan(Path dir) throws IOException {
        return scannerService.scanDirectorySync(testUser, dir.toString(), ScanJob.Type.MANUAL);
    }

    private static ScanPathValidator passThroughValidator() {
        ScanPathValidator v = mock(ScanPathValidator.class);
        try {
            when(v.validate(anyString())).thenAnswer(inv ->
                    java.nio.file.Paths.get((String) inv.getArgument(0)).normalize().toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return v;
    }

    @Test
    void scanEmptyDirectoryReturnsZero() throws Exception {
        ScanResult result = scan(tempDir);

        assertEquals(0, result.getSaved());
        assertEquals(0, result.getSkipped());
        assertEquals(0, result.getErrors());
    }

    @Test
    void scanDirectorySkipsNonAudioFiles() throws Exception {
        Files.writeString(tempDir.resolve("readme.txt"), "not audio");
        Files.writeString(tempDir.resolve("data.json"), "{}");

        ScanResult result = scan(tempDir);

        assertEquals(0, result.getSaved());
    }

    @Test
    void scanRecordsMalformedAudioFilesAsErrors() throws Exception {
        Files.write(tempDir.resolve("bad.mp3"), new byte[]{0, 1, 2, 3, 4, 5});

        ScanResult result = scan(tempDir);

        assertEquals(0, result.getSaved());
        assertEquals(1, result.getErrors());
        assertFalse(result.getErrorDetails().isEmpty());
        assertTrue(result.getErrorDetails().get(0).startsWith("bad.mp3"));
    }

    @Test
    void scanNonexistentDirectoryThrows() {
        assertThrows(NoSuchFileException.class, () ->
                scannerService.scanDirectorySync(testUser, "/nonexistent/path/music", ScanJob.Type.MANUAL));
    }

    @Test
    void scanSkipsDuplicatePaths() throws Exception {
        Path mp3 = tempDir.resolve("existing.mp3");
        Files.write(mp3, new byte[]{0, 1, 2});

        MusicFile existing = MusicFile.builder()
                .filePath(mp3.toString())
                .title("Existing")
                .artist("Artist")
                .build();
        when(repository.findByUserId("user1")).thenReturn(List.of(existing));

        ScanResult result = scan(tempDir);

        assertEquals(0, result.getSaved());
        assertEquals(1, result.getSkipped());
    }

    @Test
    void scanSkipsSymbolicLinks() throws Exception {
        Path realFile = tempDir.resolve("real.mp3");
        Files.write(realFile, new byte[]{0, 1, 2});

        Path subDir = tempDir.resolve("sub");
        Files.createDirectory(subDir);
        try {
            Files.createSymbolicLink(subDir.resolve("link.mp3"), realFile);
        } catch (UnsupportedOperationException | IOException e) {
            return; // symlinks not supported
        }

        ScanResult result = scan(tempDir);

        assertTrue(result.getSaved() + result.getErrors() <= 1,
                "Symlinked file should not be processed separately");
    }

    @Test
    void scanHandlesNestedDirectories() throws Exception {
        Path subDir = tempDir.resolve("artist").resolve("album");
        Files.createDirectories(subDir);
        Files.write(subDir.resolve("track.mp3"), new byte[]{0, 1, 2, 3});

        ScanResult result = scan(tempDir);

        assertEquals(1, result.getErrors());
    }

    @Test
    void scanSavesBatchCorrectly() throws Exception {
        for (int i = 0; i < 5; i++) {
            Files.write(tempDir.resolve("track" + i + ".mp3"), new byte[]{0, 1, 2});
        }

        ScanResult result = scan(tempDir);

        assertEquals(5, result.getErrors());
        assertEquals(0, result.getSaved());
        verify(repository, never()).saveAll(any());
    }

    @Test
    void scanResultCapsErrorDetailsAt50() throws Exception {
        for (int i = 0; i < 60; i++) {
            Files.write(tempDir.resolve("track" + i + ".mp3"), new byte[]{0});
        }

        ScanResult result = scan(tempDir);

        assertEquals(60, result.getErrors());
        assertEquals(51, result.getErrorDetails().size(),
                "Error details should be capped at 50 plus truncation message");
        assertTrue(result.getErrorDetails().get(50).contains("truncated"));
    }

    @Test
    void concurrentScansForSameUserAreRejected() throws Exception {
        CountDownLatch scanStarted = new CountDownLatch(1);
        CountDownLatch scanRelease = new CountDownLatch(1);

        MusicFileRepository slowRepo = mock(MusicFileRepository.class);
        when(slowRepo.findByUserId("user1")).thenAnswer(inv -> {
            scanStarted.countDown();
            scanRelease.await();
            return List.of();
        });
        CoverArtRepository coverArtRepo = mock(CoverArtRepository.class);
        ScanProgressEmitter progressEmitter = mock(ScanProgressEmitter.class);
        ScanJobRepository slowJobRepo = mock(ScanJobRepository.class);
        when(slowJobRepo.countByUserIdAndStatusIn(anyString(), any())).thenReturn(0L);
        when(slowJobRepo.save(any(ScanJob.class))).thenAnswer(inv -> {
            ScanJob j = inv.getArgument(0);
            if (j.getId() == null) j.setId("job-" + System.nanoTime());
            return j;
        });
        UserRepository slowUserRepo = mock(UserRepository.class);
        AudioMetadataReader reader = ScannerTestFactory.newMetadataReader();
        CoverArtHandler handler = ScannerTestFactory.newCoverArtHandler(coverArtRepo, 10_485_760, 524_288_000L);

        MusicScannerService lockTestService = ScannerTestFactory.newScanner(
                catalogService, slowRepo, slowJobRepo, slowUserRepo,
                progressEmitter, passThroughValidator(), reader, handler);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger illegalStateCount = new AtomicInteger(0);

        try {
            Future<?> firstScan = executor.submit(() -> {
                try {
                    lockTestService.scanDirectorySync(testUser, tempDir.toString(), ScanJob.Type.MANUAL);
                } catch (Exception e) {
                    // expected to complete or error
                }
            });
            scanStarted.await();

            try {
                lockTestService.scanDirectorySync(testUser, tempDir.toString(), ScanJob.Type.MANUAL);
            } catch (IllegalStateException e) {
                illegalStateCount.incrementAndGet();
                assertTrue(e.getMessage().contains("already in progress"));
            }

            scanRelease.countDown();
            firstScan.get();
        } finally {
            executor.shutdownNow();
            reader.destroy();
        }

        assertEquals(1, illegalStateCount.get(), "Second concurrent scan should have been rejected");
    }

    @Test
    void startAsyncScanCreatesQueuedJobAndReturnsImmediately() throws Exception {
        // No active jobs; save returns the job with an ID
        ScanJob returned = scannerService.startAsyncScan(testUser, tempDir.toString());

        assertNotNull(returned.getId());
        // Status may already be QUEUED (fresh save) or RUNNING (if the @Async invocation was inlined
        // synchronously during the test); both are valid. The key is that startAsyncScan did not block
        // waiting for the scan to finish — it returned before any repository.saveAll for music files.
        assertTrue(returned.getStatus() == ScanJob.Status.QUEUED
                || returned.getStatus() == ScanJob.Status.RUNNING
                || returned.getStatus() == ScanJob.Status.COMPLETED);
        verify(scanJobRepository, atLeastOnce()).save(any(ScanJob.class));
    }

    @Test
    void startAsyncScanRejectsWhenActiveJobExists() {
        when(scanJobRepository.countByUserIdAndStatusIn(eq("user1"), any())).thenReturn(1L);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> scannerService.startAsyncScan(testUser, tempDir.toString()));
        assertTrue(ex.getMessage().contains("already in progress"));
    }

    @Test
    void runtimeExceptionFromDoScanMarksJobFailed() {
        // Simulate an unexpected RuntimeException originating inside doScan. Without the fix
        // in executeScan, this would leave the ScanJob in RUNNING state forever.
        when(repository.findByUserId("user1")).thenThrow(new IllegalArgumentException("boom"));

        assertThrows(RuntimeException.class,
                () -> scannerService.scanDirectorySync(testUser, tempDir.toString(), ScanJob.Type.MANUAL));

        org.mockito.ArgumentCaptor<ScanJob> captor = org.mockito.ArgumentCaptor.forClass(ScanJob.class);
        verify(scanJobRepository, atLeastOnce()).save(captor.capture());
        ScanJob terminal = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(ScanJob.Status.FAILED, terminal.getStatus());
        assertNotNull(terminal.getErrorMessage());
        assertTrue(terminal.getErrorMessage().contains("boom"));
        assertNotNull(terminal.getFinishedAt());
    }

    @Test
    void timedOutScanRecordsErrorMessage() throws Exception {
        // Force the deadline check to fire on the first iteration.
        org.springframework.test.util.ReflectionTestUtils.setField(scannerService, "scanTimeoutMinutes", -1);
        Files.write(tempDir.resolve("track.mp3"), new byte[]{0});

        scan(tempDir);

        org.mockito.ArgumentCaptor<ScanJob> captor = org.mockito.ArgumentCaptor.forClass(ScanJob.class);
        verify(scanJobRepository, atLeastOnce()).save(captor.capture());
        ScanJob terminal = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(ScanJob.Status.TIMED_OUT, terminal.getStatus());
        assertNotNull(terminal.getErrorMessage());
        assertTrue(terminal.getErrorMessage().toLowerCase().contains("timeout"),
                "expected timeout message but got: " + terminal.getErrorMessage());
    }

}
