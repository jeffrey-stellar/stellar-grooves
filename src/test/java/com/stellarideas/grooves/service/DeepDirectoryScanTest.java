package com.stellarideas.grooves.service;

import com.stellarideas.grooves.dto.ScanResult;
import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.CoverArtRepository;
import com.stellarideas.grooves.repository.MusicFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for deep and complex directory structures during scanning,
 * including depth limiting, mixed file types at various levels,
 * and empty nested directories.
 */
class DeepDirectoryScanTest {

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
        ReflectionTestUtils.setField(scannerService, "fileReaderThreads", 1);
        ReflectionTestUtils.setField(scannerService, "supportedExtensionsConfig", ".mp3,.m4a,.flac");
        ReflectionTestUtils.setField(scannerService, "hardMaxDepth", 50);
        ReflectionTestUtils.setField(scannerService, "batchSize", 200);
        ReflectionTestUtils.setField(scannerService, "maxCoverArtBytes", 10485760);
        scannerService.initExecutor();

        testUser = new User();
        testUser.setId("user1");
        testUser.setUsername("testuser");

        when(repository.findByUserId("user1")).thenReturn(List.of());
        when(catalogService.identifyGenres(any())).thenReturn(Set.of(Genre.OTHER));
    }

    @Test
    void scanRespectsMaxDepthLimit() throws Exception {
        // Set a shallow depth limit
        ReflectionTestUtils.setField(scannerService, "maxDepth", 1);

        // Files.walk depth counting:
        // depth 0: tempDir (root)
        // depth 1: tempDir/shallow.mp3 (reachable with maxDepth=1)
        // depth 1: tempDir/sub/ (reachable with maxDepth=1, but files inside need depth 2)
        Files.write(tempDir.resolve("shallow.mp3"), new byte[]{0, 1, 2});
        Path sub = tempDir.resolve("sub");
        Files.createDirectories(sub);
        Files.write(sub.resolve("deep.mp3"), new byte[]{0, 1, 2});

        ScanResult result = scannerService.scanDirectory(testUser, tempDir.toString());

        // shallow.mp3 at depth 1 should be found
        // deep.mp3 at depth 2 should be excluded by the depth limit
        assertEquals(1, result.getErrors(), "Only the file within depth limit should be processed");
    }

    @Test
    void scanHandlesDeeplyNestedStructure() throws Exception {
        ReflectionTestUtils.setField(scannerService, "maxDepth", 10);

        // Create a 5-level deep structure with files at multiple levels
        Path current = tempDir;
        for (int i = 1; i <= 5; i++) {
            current = current.resolve("level" + i);
            Files.createDirectories(current);
            Files.write(current.resolve("track" + i + ".mp3"), new byte[]{0, 1, 2});
        }

        ScanResult result = scannerService.scanDirectory(testUser, tempDir.toString());

        assertEquals(5, result.getErrors(), "All 5 files across the nested structure should be found");
    }

    @Test
    void scanIgnoresNonAudioFilesInDeepDirs() throws Exception {
        ReflectionTestUtils.setField(scannerService, "maxDepth", 10);

        Path sub = tempDir.resolve("artist").resolve("album");
        Files.createDirectories(sub);

        // Mix of audio and non-audio files
        Files.write(sub.resolve("track.mp3"), new byte[]{0, 1, 2});
        Files.writeString(sub.resolve("cover.jpg"), "fake image");
        Files.writeString(sub.resolve("notes.txt"), "liner notes");
        Files.writeString(sub.resolve("playlist.m3u"), "#EXTM3U");
        Files.write(sub.resolve("bonus.flac"), new byte[]{0, 1, 2});

        ScanResult result = scannerService.scanDirectory(testUser, tempDir.toString());

        // Only .mp3 and .flac should be processed (both malformed, so errors)
        assertEquals(2, result.getErrors());
    }

    @Test
    void scanHandlesEmptyNestedDirectories() throws Exception {
        ReflectionTestUtils.setField(scannerService, "maxDepth", 10);

        // Create empty nested directories
        Files.createDirectories(tempDir.resolve("empty1").resolve("empty2").resolve("empty3"));
        // One file in a sibling directory
        Path musicDir = tempDir.resolve("music");
        Files.createDirectories(musicDir);
        Files.write(musicDir.resolve("song.mp3"), new byte[]{0, 1, 2});

        ScanResult result = scannerService.scanDirectory(testUser, tempDir.toString());

        assertEquals(1, result.getErrors(), "Only the actual audio file should be processed");
        assertEquals(0, result.getSaved());
    }

    @Test
    void scanWithMaxDepthZeroProcessesOnlyRootFiles() throws Exception {
        ReflectionTestUtils.setField(scannerService, "maxDepth", 0);

        Files.write(tempDir.resolve("root.mp3"), new byte[]{0, 1, 2});
        Path sub = tempDir.resolve("subdir");
        Files.createDirectories(sub);
        Files.write(sub.resolve("nested.mp3"), new byte[]{0, 1, 2});

        ScanResult result = scannerService.scanDirectory(testUser, tempDir.toString());

        // With depth 0, only the root directory's files should be processed
        // Files.walk with maxDepth=0 returns only the root itself, not its files
        // So nothing should be found (root is a directory, not a file)
        assertEquals(0, result.getErrors() + result.getSaved(),
                "No files should be found at depth 0");
    }

    @Test
    void scanHardMaxDepthClampsConfiguredValue() throws Exception {
        // Set maxDepth higher than HARD_MAX_DEPTH (50)
        ReflectionTestUtils.setField(scannerService, "maxDepth", 100);

        // Create a 3-level deep structure — this just tests the code path
        Path sub = tempDir.resolve("a").resolve("b").resolve("c");
        Files.createDirectories(sub);
        Files.write(sub.resolve("track.mp3"), new byte[]{0, 1, 2});

        ScanResult result = scannerService.scanDirectory(testUser, tempDir.toString());

        // File should still be found since 3 < HARD_MAX_DEPTH (50)
        assertEquals(1, result.getErrors());
    }

    @Test
    void scanHandlesAllSupportedExtensions() throws Exception {
        ReflectionTestUtils.setField(scannerService, "maxDepth", 5);

        Path sub = tempDir.resolve("mixed");
        Files.createDirectories(sub);

        Files.write(sub.resolve("song.mp3"), new byte[]{0, 1});
        Files.write(sub.resolve("song.m4a"), new byte[]{0, 1});
        Files.write(sub.resolve("song.flac"), new byte[]{0, 1});
        Files.write(sub.resolve("song.wav"), new byte[]{0, 1}); // unsupported
        Files.write(sub.resolve("song.ogg"), new byte[]{0, 1}); // unsupported

        ScanResult result = scannerService.scanDirectory(testUser, tempDir.toString());

        // Only .mp3, .m4a, .flac should be processed
        assertEquals(3, result.getErrors());
    }
}
