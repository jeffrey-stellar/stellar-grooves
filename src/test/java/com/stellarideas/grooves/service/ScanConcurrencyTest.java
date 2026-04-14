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
import static org.mockito.Mockito.*;

/**
 * Tests that concurrent scans for the same user don't cause unexpected behavior.
 * Since jAudioTagger can't parse our dummy files, we verify the error-handling
 * and deduplication paths under concurrency.
 */
class ScanConcurrencyTest {

    private MusicScannerService scannerService;
    private MusicFileRepository repository;
    private User testUser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        repository = mock(MusicFileRepository.class);
        MusicCatalogService catalogService = mock(MusicCatalogService.class);
        CoverArtRepository coverArtRepository = mock(CoverArtRepository.class);
        ScanProgressEmitter progressEmitter = mock(ScanProgressEmitter.class);
        scannerService = new MusicScannerService(catalogService, repository, coverArtRepository, progressEmitter);
        ReflectionTestUtils.setField(scannerService, "maxDepth", 20);
        ReflectionTestUtils.setField(scannerService, "fileReaderThreads", 1);
        scannerService.initExecutor();

        testUser = new User();
        testUser.setId("user1");
        testUser.setUsername("testuser");

        when(repository.findByUserId("user1")).thenReturn(List.of());
        when(catalogService.identifyGenres(any())).thenReturn(Set.of(Genre.OTHER));
    }

    @Test
    void concurrentScansDoNotThrow() throws Exception {
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

        List<ScanResult> results = new ArrayList<>();
        for (Future<ScanResult> f : futures) {
            results.add(f.get(10, TimeUnit.SECONDS));
        }
        executor.shutdown();

        // All scans should complete without exceptions
        assertEquals(threadCount, results.size());

        // Each scan sees the same 10 malformed files
        for (ScanResult r : results) {
            assertEquals(10, r.getErrors(), "Each concurrent scan should process all files");
            assertEquals(0, r.getSaved());
        }
    }

    @Test
    void concurrentScansWithExistingFilesSkipDuplicates() throws Exception {
        Path mp3 = tempDir.resolve("existing.mp3");
        Files.write(mp3, new byte[]{0, 1, 2});

        // Pre-populate the "existing" list
        MusicFile existing = MusicFile.builder()
                .filePath(mp3.toString())
                .title("Existing")
                .artist("Artist")
                .build();
        when(repository.findByUserId("user1")).thenReturn(List.of(existing));

        int threadCount = 3;
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

        for (Future<ScanResult> f : futures) {
            ScanResult r = f.get(10, TimeUnit.SECONDS);
            assertEquals(1, r.getSkipped(), "Each scan should skip the existing file");
            assertEquals(0, r.getSaved());
        }
        executor.shutdown();
    }
}
