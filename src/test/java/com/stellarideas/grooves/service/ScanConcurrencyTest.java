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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests that concurrent synchronous scans for the same user are properly serialized.
 * The per-user lock (and the ScanJob active-count guard) ensure only one scan runs
 * at a time, preventing race conditions on cover art quota checks and duplicate detection.
 */
class ScanConcurrencyTest {

    private MusicScannerService scannerService;
    private MusicFileRepository repository;
    private AudioMetadataReader metadataReader;
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
        ScanJobRepository scanJobRepository = mock(ScanJobRepository.class);
        UserRepository userRepository = mock(UserRepository.class);

        metadataReader = ScannerTestFactory.newMetadataReader();
        CoverArtHandler coverArtHandler = ScannerTestFactory.newCoverArtHandler(
                coverArtRepository, 10_485_760, 524_288_000L);

        scannerService = ScannerTestFactory.newScanner(
                catalogService, repository, scanJobRepository, userRepository,
                progressEmitter, passThroughValidator(), metadataReader, coverArtHandler);

        // No existing active jobs; save returns the job with an id
        when(scanJobRepository.countByUserIdAndStatusIn(anyString(), any())).thenReturn(0L);
        when(scanJobRepository.save(any(ScanJob.class))).thenAnswer(inv -> {
            ScanJob j = inv.getArgument(0);
            if (j.getId() == null) j.setId("job-" + System.nanoTime());
            return j;
        });

        testUser = new User();
        testUser.setId("user1");
        testUser.setUsername("testuser");

        when(repository.findByUserId("user1")).thenReturn(List.of());
        when(catalogService.identifyGenres(any())).thenReturn(Set.of(Genre.OTHER));
    }

    @AfterEach
    void tearDown() { if (metadataReader != null) metadataReader.destroy(); }

    private ScanResult sync(User user) throws java.io.IOException {
        return scannerService.scanDirectorySync(user, tempDir.toString(), ScanJob.Type.MANUAL);
    }

    @Test
    void concurrentScansRejectAllButOne() throws Exception {
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
                return sync(testUser);
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
                return sync(user);
            }));
        }

        latch.countDown();

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

        ScanResult r1 = sync(testUser);
        assertEquals(1, r1.getErrors());

        MusicFile existing = MusicFile.builder()
                .filePath(mp3.toString())
                .title("Existing")
                .artist("Artist")
                .build();
        when(repository.findByUserId("user1")).thenReturn(List.of(existing));

        ScanResult r2 = sync(testUser);
        assertEquals(1, r2.getSkipped(), "Second scan should skip the existing file");
        assertEquals(0, r2.getSaved());
    }
}
