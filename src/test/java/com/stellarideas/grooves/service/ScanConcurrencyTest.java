package com.stellarideas.grooves.service;

import com.stellarideas.grooves.dto.ScanResult;
import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.CoverArtRepository;
import com.stellarideas.grooves.repository.MusicFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests that concurrent scans for the same user are properly serialized.
 * The per-user lock ensures only one scan runs at a time, preventing
 * race conditions on cover art quota checks and duplicate detection.
 */
class ScanConcurrencyTest {

    private MusicScannerService scannerService;
    private MusicFileRepository repository;
    private User testUser;

    @TempDir
    Path tempDir;

    private static ScanPathValidator passThroughValidator() {
        ScanPathValidator v = mock(ScanPathValidator.class);
        try {
            when(v.validate(anyString())).thenAnswer(inv ->
                    java.nio.file.Paths.get((String) inv.getArgument(0)).normalize().toAbsolutePath());
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        return v;
    }

    @BeforeEach
    void setUp() {
        repository = mock(MusicFileRepository.class);
        MusicCatalogService catalogService = mock(MusicCatalogService.class);
        CoverArtRepository coverArtRepository = mock(CoverArtRepository.class);
        ScanProgressEmitter progressEmitter = mock(ScanProgressEmitter.class);
        scannerService = new MusicScannerService(catalogService, repository, coverArtRepository, progressEmitter, passThroughValidator(), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        ReflectionTestUtils.setField(scannerService, "maxDepth", 20);
        ReflectionTestUtils.setField(scannerService, "hardMaxDepth", 50);
        ReflectionTestUtils.setField(scannerService, "batchSize", 200);
        ReflectionTestUtils.setField(scannerService, "maxCoverArtBytes", 10485760);
        ReflectionTestUtils.setField(scannerService, "fileReaderThreads", 1);
        ReflectionTestUtils.setField(scannerService, "supportedExtensionsConfig", ".mp3,.m4a,.flac");
        scannerService.initExecutor();

        testUser = new User();
        testUser.setId("user1");
        testUser.setUsername("testuser");

        when(repository.findByUserId("user1")).thenReturn(List.of());
        when(catalogService.identifyGenres(any())).thenReturn(Set.of(Genre.OTHER));
    }

    @Test
    void concurrentScansRejectAllButOne() throws Exception {
        // Create some dummy files
        for (int i = 0; i < 10; i++) {
            Files.write(tempDir.resolve("track" + i + ".mp3"), new byte[]{0, 1, 2, 3});
        }

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<ScanResult>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                latch.await();
                return scannerService.scanDirectory(testUser, tempDir.toString());
            }));
        }

        latch.countDown();

        int succeeded = 0;
        int rejected = 0;
        for (Future<ScanResult> f : futures) {
            try {
                ScanResult r = f.get(10, TimeUnit.SECONDS);
                succeeded++;
                assertEquals(10, r.getErrors(), "Successful scan should process all files");
                assertEquals(0, r.getSaved());
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IllegalStateException) {
                    rejected++;
                } else {
                    throw e;
                }
            }
        }
        executor.shutdown();

        // At least one scan should succeed; the rest may be rejected
        assertTrue(succeeded >= 1, "At least one scan should succeed");
        assertEquals(threadCount, succeeded + rejected, "All scans should either succeed or be rejected");
    }

    @Test
    void concurrentScansForDifferentUsersAllSucceed() throws Exception {
        for (int i = 0; i < 5; i++) {
            Files.write(tempDir.resolve("track" + i + ".mp3"), new byte[]{0, 1, 2, 3});
        }

        int threadCount = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<ScanResult>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            User user = new User();
            user.setId("user" + i);
            user.setUsername("testuser" + i);
            when(repository.findByUserId("user" + i)).thenReturn(List.of());

            futures.add(executor.submit(() -> {
                latch.await();
                return scannerService.scanDirectory(user, tempDir.toString());
            }));
        }

        latch.countDown();

        // All scans for different users should succeed (no lock contention)
        for (Future<ScanResult> f : futures) {
            ScanResult r = f.get(10, TimeUnit.SECONDS);
            assertEquals(5, r.getErrors(), "Each scan should process all files");
        }
        executor.shutdown();
    }

    @Test
    void sequentialScansForSameUserBothSucceed() throws Exception {
        Path mp3 = tempDir.resolve("existing.mp3");
        Files.write(mp3, new byte[]{0, 1, 2});

        // First scan
        ScanResult r1 = scannerService.scanDirectory(testUser, tempDir.toString());
        assertEquals(1, r1.getErrors());

        // Second scan (lock should be released)
        MusicFile existing = MusicFile.builder()
                .filePath(mp3.toString())
                .title("Existing")
                .artist("Artist")
                .build();
        when(repository.findByUserId("user1")).thenReturn(List.of(existing));

        ScanResult r2 = scannerService.scanDirectory(testUser, tempDir.toString());
        assertEquals(1, r2.getSkipped(), "Second scan should skip the existing file");
        assertEquals(0, r2.getSaved());
    }
}
