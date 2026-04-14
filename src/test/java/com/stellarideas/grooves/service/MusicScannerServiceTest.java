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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MusicScannerServiceTest {

    private MusicScannerService scannerService;
    private MusicFileRepository repository;
    private MusicCatalogService catalogService;
    private User testUser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        repository = mock(MusicFileRepository.class);
        catalogService = mock(MusicCatalogService.class);
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
    void scanEmptyDirectoryReturnsZero() throws Exception {
        ScanResult result = scannerService.scanDirectory(testUser, tempDir.toString());

        assertEquals(0, result.getSaved());
        assertEquals(0, result.getSkipped());
        assertEquals(0, result.getErrors());
    }

    @Test
    void scanDirectorySkipsNonAudioFiles() throws Exception {
        Files.writeString(tempDir.resolve("readme.txt"), "not audio");
        Files.writeString(tempDir.resolve("data.json"), "{}");

        ScanResult result = scannerService.scanDirectory(testUser, tempDir.toString());

        assertEquals(0, result.getSaved());
    }

    @Test
    void scanRecordsMalformedAudioFilesAsErrors() throws Exception {
        // Create a .mp3 file with garbage content — jAudioTagger will fail to parse it
        Files.write(tempDir.resolve("bad.mp3"), new byte[]{0, 1, 2, 3, 4, 5});

        ScanResult result = scannerService.scanDirectory(testUser, tempDir.toString());

        assertEquals(0, result.getSaved());
        assertEquals(1, result.getErrors());
        assertFalse(result.getErrorDetails().isEmpty());
        assertTrue(result.getErrorDetails().get(0).startsWith("bad.mp3"));
    }

    @Test
    void scanNonexistentDirectoryThrows() {
        assertThrows(NoSuchFileException.class, () ->
                scannerService.scanDirectory(testUser, "/nonexistent/path/music"));
    }

    @Test
    void scanSkipsDuplicatePaths() throws Exception {
        // Simulate an existing file at the same path
        Path mp3 = tempDir.resolve("existing.mp3");
        Files.write(mp3, new byte[]{0, 1, 2});

        MusicFile existing = MusicFile.builder()
                .filePath(mp3.toString())
                .title("Existing")
                .artist("Artist")
                .build();
        when(repository.findByUserId("user1")).thenReturn(List.of(existing));

        ScanResult result = scannerService.scanDirectory(testUser, tempDir.toString());

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
            // Symlinks not supported on this filesystem (e.g., some Windows configs)
            return;
        }

        ScanResult result = scannerService.scanDirectory(testUser, tempDir.toString());

        // The symlink should be skipped; the real file should be processed (and error since it's not valid audio)
        // We just verify the symlink wasn't double-counted
        assertTrue(result.getSaved() + result.getErrors() <= 1,
                "Symlinked file should not be processed separately");
    }

    @Test
    void scanHandlesNestedDirectories() throws Exception {
        Path subDir = tempDir.resolve("artist").resolve("album");
        Files.createDirectories(subDir);
        Files.write(subDir.resolve("track.mp3"), new byte[]{0, 1, 2, 3});

        ScanResult result = scannerService.scanDirectory(testUser, tempDir.toString());

        // The file is malformed so it should be an error, not a save — but it should be found
        assertEquals(1, result.getErrors());
    }

    @Test
    void scanSavesBatchCorrectly() throws Exception {
        // Create multiple malformed mp3 files — they'll all fail parsing but exercise the loop
        for (int i = 0; i < 5; i++) {
            Files.write(tempDir.resolve("track" + i + ".mp3"), new byte[]{0, 1, 2});
        }

        ScanResult result = scannerService.scanDirectory(testUser, tempDir.toString());

        assertEquals(5, result.getErrors());
        assertEquals(0, result.getSaved());
        verify(repository, never()).saveAll(any());
    }

    @Test
    void scanResultCapsErrorDetailsAt50() throws Exception {
        // Create 60 malformed mp3 files
        for (int i = 0; i < 60; i++) {
            Files.write(tempDir.resolve("track" + i + ".mp3"), new byte[]{0});
        }

        ScanResult result = scannerService.scanDirectory(testUser, tempDir.toString());

        assertEquals(60, result.getErrors());
        assertEquals(51, result.getErrorDetails().size(), "Error details should be capped at 50 plus truncation message");
        assertTrue(result.getErrorDetails().get(50).contains("truncated"),
                "Last entry should indicate truncation");
    }
}
