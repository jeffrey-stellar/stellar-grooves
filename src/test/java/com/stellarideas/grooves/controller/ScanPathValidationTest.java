package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.dto.ScanRequest;
import com.stellarideas.grooves.dto.ScanResult;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.service.AuditService;
import com.stellarideas.grooves.service.LibraryService;
import com.stellarideas.grooves.service.MessageHelper;
import com.stellarideas.grooves.service.MusicScannerService;
import com.stellarideas.grooves.service.ScanRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScanPathValidationTest {

    private LibraryController controller;
    private User testUser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        MusicScannerService scannerService = mock(MusicScannerService.class);
        try {
            com.stellarideas.grooves.model.ScanJob stubJob = new com.stellarideas.grooves.model.ScanJob();
            stubJob.setId("job-stub");
            stubJob.setStatus(com.stellarideas.grooves.model.ScanJob.Status.QUEUED);
            when(scannerService.startAsyncScan(any(User.class), anyString())).thenReturn(stubJob);
        } catch (Exception ignored) {}
        LibraryService libraryService = mock(LibraryService.class);
        AuditService auditService = mock(AuditService.class);

        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        MessageHelper msgHelper = new MessageHelper(messageSource);

        com.stellarideas.grooves.repository.UserRepository userRepository = mock(com.stellarideas.grooves.repository.UserRepository.class);
        ScanRateLimiter scanRateLimiter = mock(ScanRateLimiter.class);
        when(scanRateLimiter.tryAcquire(anyString())).thenReturn(true);
        com.stellarideas.grooves.repository.PlaybackQueueRepository playbackQueueRepository = mock(com.stellarideas.grooves.repository.PlaybackQueueRepository.class);
        com.stellarideas.grooves.service.ScanProgressEmitter scanProgressEmitter = mock(com.stellarideas.grooves.service.ScanProgressEmitter.class);
        controller = new LibraryController(scannerService, libraryService, msgHelper, auditService, userRepository, scanRateLimiter, playbackQueueRepository, scanProgressEmitter, mock(com.stellarideas.grooves.service.UserRateLimiter.class), new com.stellarideas.grooves.service.ScanPathValidator(msgHelper, ""), mock(com.stellarideas.grooves.service.PlayHistoryService.class), mock(com.stellarideas.grooves.service.FfmpegAvailability.class));

        testUser = new User();
        testUser.setId("user1");
        testUser.setUsername("testuser");
    }

    private ScanRequest scanRequest(String path) {
        ScanRequest req = new ScanRequest();
        req.setPath(path);
        return req;
    }

    @Test
    void rejectsEmptyStringPath() {
        ResponseEntity<?> response = controller.scanDirectory(testUser, scanRequest(""));
        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void rejectsNonexistentPath() {
        ResponseEntity<?> response = controller.scanDirectory(testUser, scanRequest("/nonexistent/path/xyz"));
        assertEquals(400, response.getStatusCode().value());
        org.springframework.http.ProblemDetail body = (org.springframework.http.ProblemDetail) response.getBody();
        assertNotNull(body);
        String detail = body.getDetail();
        assertTrue(detail.contains("not exist") || detail.contains("not a directory"));
    }

    @Test
    void rejectsPathTraversalWithDotDot() {
        String traversal = tempDir.toString() + "/../../../etc";
        ResponseEntity<?> response = controller.scanDirectory(testUser, scanRequest(traversal));
        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void rejectsFileInsteadOfDirectory() throws IOException {
        Path file = tempDir.resolve("notadir.txt");
        Files.writeString(file, "hello");

        ResponseEntity<?> response = controller.scanDirectory(testUser, scanRequest(file.toString()));
        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void rejectsSymlinkDirectory() throws IOException {
        Path realDir = tempDir.resolve("real");
        Files.createDirectory(realDir);
        Path symlink = tempDir.resolve("link");
        Files.createSymbolicLink(symlink, realDir);

        ResponseEntity<?> response = controller.scanDirectory(testUser, scanRequest(symlink.toString()));
        assertEquals(400, response.getStatusCode().value());
        org.springframework.http.ProblemDetail body = (org.springframework.http.ProblemDetail) response.getBody();
        assertNotNull(body);
        String detail = body.getDetail();
        assertTrue(detail.contains("symbolic link") || detail.contains("real path"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void rejectsNestedSymlinkInPath() throws IOException {
        Path outer = tempDir.resolve("outer");
        Files.createDirectory(outer);
        Path innerSymlink = outer.resolve("sneaky");
        Files.createSymbolicLink(innerSymlink, Path.of("/tmp"));

        ResponseEntity<?> response = controller.scanDirectory(testUser, scanRequest(innerSymlink.toString()));
        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void acceptsValidRealDirectory() throws Exception {
        Path validDir = tempDir.toRealPath().resolve("music");
        Files.createDirectory(validDir);

        ResponseEntity<?> response = controller.scanDirectory(testUser, scanRequest(validDir.toString()));
        assertEquals(202, response.getStatusCode().value(),
                "Async scan should return 202 Accepted for a valid path");
    }
}
