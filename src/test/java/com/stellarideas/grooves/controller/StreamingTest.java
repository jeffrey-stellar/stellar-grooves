package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.service.AuditService;
import com.stellarideas.grooves.service.LibraryService;
import com.stellarideas.grooves.service.MessageHelper;
import com.stellarideas.grooves.service.MusicScannerService;
import com.stellarideas.grooves.service.ScanRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StreamingTest {

    private LibraryController controller;
    private LibraryService libraryService;
    private User testUser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        libraryService = mock(LibraryService.class);
        MusicScannerService scannerService = mock(MusicScannerService.class);

        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        MessageHelper msgHelper = new MessageHelper(messageSource);

        AuditService auditService = mock(AuditService.class);
        com.stellarideas.grooves.repository.UserRepository userRepository = mock(com.stellarideas.grooves.repository.UserRepository.class);
        ScanRateLimiter scanRateLimiter = mock(ScanRateLimiter.class);
        com.stellarideas.grooves.repository.PlaybackQueueRepository playbackQueueRepository = mock(com.stellarideas.grooves.repository.PlaybackQueueRepository.class);
        com.stellarideas.grooves.service.ScanProgressEmitter scanProgressEmitter = mock(com.stellarideas.grooves.service.ScanProgressEmitter.class);
        controller = new LibraryController(scannerService, libraryService, msgHelper, auditService, userRepository, scanRateLimiter, playbackQueueRepository, scanProgressEmitter);

        testUser = new User();
        testUser.setId("user1");
        testUser.setUsername("testuser");
        testUser.setMusicDirectory(tempDir.toString());
    }

    private Path createTempAudioFile(String name, int sizeBytes) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, new byte[sizeBytes]);
        return file;
    }

    @Test
    void streamFileReturnsFullContent() throws IOException {
        Path audioPath = createTempAudioFile("song.mp3", 1024);
        MusicFile file = MusicFile.builder()
                .id("f1").fileName("song.mp3").filePath(audioPath.toString()).build();
        when(libraryService.findFileByIdAndUserId("f1", "user1")).thenReturn(Optional.of(file));

        ResponseEntity<ResourceRegion> response = controller.streamFile(testUser, "f1", new HttpHeaders());

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(1024, response.getBody().getCount());
        assertEquals("audio/mpeg", response.getHeaders().getContentType().toString());
    }

    @Test
    void streamFileReturnsPartialContentForRangeRequest() throws IOException {
        Path audioPath = createTempAudioFile("song.mp3", 2048);
        MusicFile file = MusicFile.builder()
                .id("f1").fileName("song.mp3").filePath(audioPath.toString()).build();
        when(libraryService.findFileByIdAndUserId("f1", "user1")).thenReturn(Optional.of(file));

        HttpHeaders headers = new HttpHeaders();
        headers.setRange(java.util.List.of(HttpRange.createByteRange(0, 511)));

        ResponseEntity<ResourceRegion> response = controller.streamFile(testUser, "f1", headers);

        assertEquals(206, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(512, response.getBody().getCount());
    }

    @Test
    void streamFileReturns404ForMissingDatabaseEntry() throws IOException {
        when(libraryService.findFileByIdAndUserId("missing", "user1")).thenReturn(Optional.empty());

        ResponseEntity<ResourceRegion> response = controller.streamFile(testUser, "missing", new HttpHeaders());

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void streamFileReturns404WhenFileNotOnDisk() throws IOException {
        // File path is within musicDirectory but the file doesn't exist on disk
        String missingPath = tempDir.resolve("gone.mp3").toString();
        MusicFile file = MusicFile.builder()
                .id("f1").fileName("gone.mp3").filePath(missingPath).build();
        when(libraryService.findFileByIdAndUserId("f1", "user1")).thenReturn(Optional.of(file));

        ResponseEntity<ResourceRegion> response = controller.streamFile(testUser, "f1", new HttpHeaders());

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void streamFileReturns404WhenFileNotReadable() throws IOException {
        Path audioPath = createTempAudioFile("locked.mp3", 512);
        audioPath.toFile().setReadable(false);
        MusicFile file = MusicFile.builder()
                .id("f1").fileName("locked.mp3").filePath(audioPath.toString()).build();
        when(libraryService.findFileByIdAndUserId("f1", "user1")).thenReturn(Optional.of(file));

        ResponseEntity<ResourceRegion> response = controller.streamFile(testUser, "f1", new HttpHeaders());

        assertEquals(404, response.getStatusCode().value());
        audioPath.toFile().setReadable(true);
    }

    @Test
    void streamFlacFileReturnsCorrectMediaType() throws IOException {
        Path audioPath = createTempAudioFile("song.flac", 512);
        MusicFile file = MusicFile.builder()
                .id("f1").fileName("song.flac").filePath(audioPath.toString()).build();
        when(libraryService.findFileByIdAndUserId("f1", "user1")).thenReturn(Optional.of(file));

        ResponseEntity<ResourceRegion> response = controller.streamFile(testUser, "f1", new HttpHeaders());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("audio/flac", response.getHeaders().getContentType().toString());
    }

    @Test
    void streamM4aFileReturnsCorrectMediaType() throws IOException {
        Path audioPath = createTempAudioFile("song.m4a", 512);
        MusicFile file = MusicFile.builder()
                .id("f1").fileName("song.m4a").filePath(audioPath.toString()).build();
        when(libraryService.findFileByIdAndUserId("f1", "user1")).thenReturn(Optional.of(file));

        ResponseEntity<ResourceRegion> response = controller.streamFile(testUser, "f1", new HttpHeaders());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("audio/mp4", response.getHeaders().getContentType().toString());
    }

    @Test
    void streamUnknownExtReturnsOctetStream() throws IOException {
        Path audioPath = createTempAudioFile("song.wav", 512);
        MusicFile file = MusicFile.builder()
                .id("f1").fileName("song.wav").filePath(audioPath.toString()).build();
        when(libraryService.findFileByIdAndUserId("f1", "user1")).thenReturn(Optional.of(file));

        ResponseEntity<ResourceRegion> response = controller.streamFile(testUser, "f1", new HttpHeaders());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/octet-stream", response.getHeaders().getContentType().toString());
    }
}
