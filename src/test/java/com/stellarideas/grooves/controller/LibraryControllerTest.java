package com.stellarideas.grooves.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.stellarideas.grooves.dto.*;
import com.stellarideas.grooves.model.*;
import com.stellarideas.grooves.repository.PlaybackQueueRepository;
import com.stellarideas.grooves.repository.UserRepository;
import com.stellarideas.grooves.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class LibraryControllerTest {

    private LibraryController controller;
    private LibraryService libraryService;
    private MusicScannerService scannerService;
    private AuditService auditService;
    private UserRepository userRepository;
    private ScanRateLimiter scanRateLimiter;
    private PlaybackQueueRepository playbackQueueRepository;
    private ScanProgressEmitter scanProgressEmitter;
    private UserRateLimiter userRateLimiter;
    private PlayHistoryService playHistoryService;
    private User testUser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        scannerService = mock(MusicScannerService.class);
        libraryService = mock(LibraryService.class);
        auditService = mock(AuditService.class);
        userRepository = mock(UserRepository.class);
        scanRateLimiter = mock(ScanRateLimiter.class);
        playbackQueueRepository = mock(PlaybackQueueRepository.class);
        scanProgressEmitter = mock(ScanProgressEmitter.class);
        userRateLimiter = mock(UserRateLimiter.class);
        playHistoryService = mock(PlayHistoryService.class);

        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        MessageHelper msgHelper = new MessageHelper(messageSource);

        when(scanRateLimiter.tryAcquire(anyString())).thenReturn(true);
        when(userRateLimiter.tryAcquire(anyString(), anyString())).thenReturn(true);

        controller = new LibraryController(scannerService, libraryService, msgHelper,
                auditService, userRepository, scanRateLimiter, playbackQueueRepository, scanProgressEmitter, userRateLimiter,
                new com.stellarideas.grooves.service.ScanPathValidator(msgHelper, ""),
                playHistoryService);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "maxQueueTracks", 5000);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "transcodeTimeoutSeconds", 300);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "maxTranscodeFileSize", 500L * 1024 * 1024);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "maxExportSize", 50000);

        testUser = new User();
        testUser.setId("user1");
        testUser.setUsername("testuser");
        testUser.setMusicDirectory(tempDir.toString());
    }

    // ---- getFiles ----

    @Test
    void getFilesWithoutGenreFilter() {
        MusicFile file = MusicFile.builder()
                .id("f1").title("Song").artist("Artist").genre(Genre.CLASSIC_ROCK).build();
        Page<MusicFile> page = new PageImpl<>(List.of(file));
        when(libraryService.getFiles(eq("user1"), isNull(), eq(0), eq(50))).thenReturn(page);

        ResponseEntity<?> response = controller.getFiles(testUser, null, 0, 50);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("content"));
        assertTrue(body.containsKey("totalPages"));
        assertTrue(body.containsKey("totalElements"));
    }

    @Test
    void getFilesWithGenreFilter() {
        Page<MusicFile> page = new PageImpl<>(List.of());
        when(libraryService.getFiles(eq("user1"), eq(Genre.HARD_ROCK), eq(0), eq(50))).thenReturn(page);

        ResponseEntity<?> response = controller.getFiles(testUser, "HARD_ROCK", 0, 50);

        assertEquals(200, response.getStatusCode().value());
        verify(libraryService).getFiles("user1", Genre.HARD_ROCK, 0, 50);
    }

    @Test
    void getFilesWithGenreCaseInsensitive() {
        Page<MusicFile> page = new PageImpl<>(List.of());
        when(libraryService.getFiles(eq("user1"), eq(Genre.CLASSIC_ROCK), eq(0), eq(50))).thenReturn(page);

        ResponseEntity<?> response = controller.getFiles(testUser, "classic_rock", 0, 50);

        assertEquals(200, response.getStatusCode().value());
        verify(libraryService).getFiles("user1", Genre.CLASSIC_ROCK, 0, 50);
    }

    @Test
    void getFilesRejectsInvalidGenre() {
        assertThrows(IllegalArgumentException.class, () ->
                controller.getFiles(testUser, "NOT_A_GENRE", 0, 50));
    }

    @Test
    void getFilesWithBlankGenrePassesNull() {
        Page<MusicFile> page = new PageImpl<>(List.of());
        when(libraryService.getFiles(eq("user1"), isNull(), eq(0), eq(50))).thenReturn(page);

        ResponseEntity<?> response = controller.getFiles(testUser, "  ", 0, 50);

        assertEquals(200, response.getStatusCode().value());
        verify(libraryService).getFiles("user1", null, 0, 50);
    }

    // ---- searchFiles ----

    @Test
    void searchFilesReturnsResults() {
        MusicFile file = MusicFile.builder()
                .id("f1").title("Stairway").artist("Led Zeppelin").genre(Genre.CLASSIC_ROCK).build();
        Page<MusicFile> page = new PageImpl<>(List.of(file));
        when(libraryService.searchFiles(eq("user1"), eq("stairway"), isNull(), isNull(), isNull(), isNull(), eq(0), eq(50))).thenReturn(page);

        ResponseEntity<?> response = controller.searchFiles(testUser, "stairway", null, null, null, null, 0, 50);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals("stairway", body.get("query"));
        assertTrue(body.containsKey("content"));
    }

    @Test
    void searchFilesRejectsBlanQuery() {
        ResponseEntity<?> response = controller.searchFiles(testUser, "   ", null, null, null, null, 0, 50);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void searchFilesRejectsNullQuery() {
        ResponseEntity<?> response = controller.searchFiles(testUser, null, null, null, null, null, 0, 50);

        assertEquals(400, response.getStatusCode().value());
    }

    // ---- streamFile ----

    @Test
    void streamFileReturns404WhenFileNotFound() throws IOException {
        when(libraryService.findFileByIdAndUserId("missing", "user1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.streamFile(testUser, "missing", new HttpHeaders());

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void streamFileReturns404WhenFileDoesNotExistOnDisk() throws IOException {
        MusicFile file = MusicFile.builder()
                .id("f1").filePath(tempDir.resolve("nonexistent.mp3").toString())
                .fileName("nonexistent.mp3").build();
        when(libraryService.findFileByIdAndUserId("f1", "user1")).thenReturn(Optional.of(file));

        ResponseEntity<?> response = controller.streamFile(testUser, "f1", new HttpHeaders());

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void streamFileReturnsForbiddenForPathTraversal() throws IOException {
        // Create a file outside the music directory
        Path outsideDir = Files.createTempDirectory("outside");
        Path outsideFile = outsideDir.resolve("hack.mp3");
        Files.write(outsideFile, new byte[]{0, 1, 2, 3});

        MusicFile file = MusicFile.builder()
                .id("f1").filePath(outsideFile.toString())
                .fileName("hack.mp3").build();
        when(libraryService.findFileByIdAndUserId("f1", "user1")).thenReturn(Optional.of(file));

        ResponseEntity<?> response = controller.streamFile(testUser, "f1", new HttpHeaders());

        assertEquals(403, response.getStatusCode().value());

        // Clean up
        Files.deleteIfExists(outsideFile);
        Files.deleteIfExists(outsideDir);
    }

    @Test
    void streamFileReturnsForbiddenWhenNoMusicDirectory() throws IOException {
        Path audioFile = tempDir.resolve("song.mp3");
        Files.write(audioFile, new byte[]{0, 1, 2, 3});

        MusicFile file = MusicFile.builder()
                .id("f1").filePath(audioFile.toString())
                .fileName("song.mp3").build();
        when(libraryService.findFileByIdAndUserId("f1", "user1")).thenReturn(Optional.of(file));

        testUser.setMusicDirectory(null);
        ResponseEntity<?> response = controller.streamFile(testUser, "f1", new HttpHeaders());

        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void streamFileSuccessfulFullRequest() throws IOException {
        Path audioFile = tempDir.resolve("song.mp3");
        Files.write(audioFile, new byte[]{0, 1, 2, 3, 4, 5, 6, 7});

        MusicFile file = MusicFile.builder()
                .id("f1").filePath(audioFile.toString())
                .fileName("song.mp3").build();
        when(libraryService.findFileByIdAndUserId("f1", "user1")).thenReturn(Optional.of(file));

        ResponseEntity<?> response = controller.streamFile(testUser, "f1", new HttpHeaders());

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getHeaders().containsKey("Accept-Ranges"));
        assertEquals("audio/mpeg", response.getHeaders().getContentType().toString());
    }

    @Test
    void streamFileSuccessfulRangeRequest() throws IOException {
        Path audioFile = tempDir.resolve("song.flac");
        byte[] content = new byte[1000];
        Files.write(audioFile, content);

        MusicFile file = MusicFile.builder()
                .id("f1").filePath(audioFile.toString())
                .fileName("song.flac").build();
        when(libraryService.findFileByIdAndUserId("f1", "user1")).thenReturn(Optional.of(file));

        HttpHeaders headers = new HttpHeaders();
        headers.setRange(List.of(HttpRange.createByteRange(0, 499)));

        ResponseEntity<?> response = controller.streamFile(testUser, "f1", headers);

        assertEquals(206, response.getStatusCode().value());
        assertEquals("audio/flac", response.getHeaders().getContentType().toString());
    }

    @Test
    void streamFileM4aMediaType() throws IOException {
        Path audioFile = tempDir.resolve("song.m4a");
        Files.write(audioFile, new byte[]{0, 1, 2, 3});

        MusicFile file = MusicFile.builder()
                .id("f1").filePath(audioFile.toString())
                .fileName("song.m4a").build();
        when(libraryService.findFileByIdAndUserId("f1", "user1")).thenReturn(Optional.of(file));

        ResponseEntity<?> response = controller.streamFile(testUser, "f1", new HttpHeaders());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("audio/mp4", response.getHeaders().getContentType().toString());
    }

    // ---- updateFileGenre ----

    @Test
    void updateFileGenreSuccess() {
        MusicFile file = MusicFile.builder().id("f1").title("Song").genre(Genre.OTHER).build();
        when(libraryService.findFileByIdAndUserId("f1", "user1")).thenReturn(Optional.of(file));

        UpdateGenreRequest request = new UpdateGenreRequest();
        request.setGenre("HARD_ROCK");

        ResponseEntity<?> response = controller.updateFileGenre(testUser, "f1", request);

        assertEquals(200, response.getStatusCode().value());
        verify(libraryService).updateGenre(file, Genre.HARD_ROCK, "user1");
        verify(auditService).log(eq("testuser"), eq(AuditService.Action.GENRE_UPDATE), eq("f1"), eq("HARD_ROCK"));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("HARD_ROCK", body.get("genre"));
    }

    @Test
    void updateFileGenreInvalidGenre() {
        UpdateGenreRequest request = new UpdateGenreRequest();
        request.setGenre("INVALID_GENRE");

        ResponseEntity<?> response = controller.updateFileGenre(testUser, "f1", request);

        assertEquals(400, response.getStatusCode().value());
        verify(libraryService, never()).updateGenre(any(), any(), any());
    }

    @Test
    void updateFileGenreFileNotFound() {
        when(libraryService.findFileByIdAndUserId("missing", "user1")).thenReturn(Optional.empty());

        UpdateGenreRequest request = new UpdateGenreRequest();
        request.setGenre("HARD_ROCK");

        ResponseEntity<?> response = controller.updateFileGenre(testUser, "missing", request);

        assertEquals(404, response.getStatusCode().value());
    }

    // ---- updateFileRating ----

    @Test
    void updateFileRatingSuccess() {
        MusicFile file = MusicFile.builder().id("f1").title("Song").rating(0).build();
        MusicFile updated = MusicFile.builder().id("f1").title("Song").rating(4).build();
        when(libraryService.findFileByIdAndUserId("f1", "user1")).thenReturn(Optional.of(file));
        when(libraryService.updateRating(file, 4)).thenReturn(updated);

        UpdateRatingRequest request = new UpdateRatingRequest();
        request.setRating(4);

        ResponseEntity<?> response = controller.updateFileRating(testUser, "f1", request);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(4, body.get("rating"));
        verify(auditService).log(eq("testuser"), eq(AuditService.Action.RATING_UPDATE), eq("f1"), eq("4"));
    }

    @Test
    void updateFileRatingFileNotFound() {
        when(libraryService.findFileByIdAndUserId("missing", "user1")).thenReturn(Optional.empty());

        UpdateRatingRequest request = new UpdateRatingRequest();
        request.setRating(3);

        ResponseEntity<?> response = controller.updateFileRating(testUser, "missing", request);

        assertEquals(404, response.getStatusCode().value());
    }

    // ---- recordPlay ----

    @Test
    void recordPlaySuccess() {
        when(playHistoryService.recordPlay("user1", "f1", 120_000, true)).thenReturn(true);

        RecordPlayRequest request = new RecordPlayRequest();
        request.setListenedMs(120_000);
        request.setCompleted(true);

        ResponseEntity<?> response = controller.recordPlay(testUser, "f1", request);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(true, body.get("recorded"));
        verify(playHistoryService).recordPlay("user1", "f1", 120_000, true);
    }

    @Test
    void recordPlayFileNotFound() {
        when(playHistoryService.recordPlay("user1", "missing", 60_000, false)).thenReturn(false);

        RecordPlayRequest request = new RecordPlayRequest();
        request.setListenedMs(60_000);
        request.setCompleted(false);

        ResponseEntity<?> response = controller.recordPlay(testUser, "missing", request);

        assertEquals(404, response.getStatusCode().value());
    }

    // ---- bulkDelete ----

    @Test
    void bulkDeleteSuccess() {
        when(libraryService.bulkDelete(List.of("f1", "f2", "f3"), "user1")).thenReturn(3L);

        BulkDeleteRequest request = new BulkDeleteRequest();
        request.setFileIds(List.of("f1", "f2", "f3"));

        ResponseEntity<?> response = controller.bulkDelete(testUser, request);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(3L, body.get("deleted"));
        verify(auditService).log(eq("testuser"), eq(AuditService.Action.BULK_DELETE), isNull(), eq("3 files"));
    }

    // ---- getCoverArt ----

    @Test
    void getCoverArtSuccess() {
        MusicFile file = MusicFile.builder()
                .id("f1").artist("Metallica").album("Master of Puppets")
                .hasCoverArt(true).build();
        when(libraryService.findFileByIdAndUserId("f1", "user1")).thenReturn(Optional.of(file));

        CoverArt art = new CoverArt();
        art.setMimeType("image/jpeg");
        art.setData(new byte[]{1, 2, 3});
        when(libraryService.getCoverArt("user1", "Metallica", "Master of Puppets"))
                .thenReturn(Optional.of(art));

        ResponseEntity<byte[]> response = controller.getCoverArt(testUser, "f1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("image/jpeg", response.getHeaders().getContentType().toString());
        assertArrayEquals(new byte[]{1, 2, 3}, response.getBody());
    }

    @Test
    void getCoverArtFileNotFound() {
        when(libraryService.findFileByIdAndUserId("missing", "user1")).thenReturn(Optional.empty());

        ResponseEntity<byte[]> response = controller.getCoverArt(testUser, "missing");

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void getCoverArtNoCoverArtFlag() {
        MusicFile file = MusicFile.builder()
                .id("f1").hasCoverArt(false).build();
        when(libraryService.findFileByIdAndUserId("f1", "user1")).thenReturn(Optional.of(file));

        ResponseEntity<byte[]> response = controller.getCoverArt(testUser, "f1");

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void getCoverArtNotFoundInStorage() {
        MusicFile file = MusicFile.builder()
                .id("f1").artist("Artist").album("Album").hasCoverArt(true).build();
        when(libraryService.findFileByIdAndUserId("f1", "user1")).thenReturn(Optional.of(file));
        when(libraryService.getCoverArt("user1", "Artist", "Album")).thenReturn(Optional.empty());

        ResponseEntity<byte[]> response = controller.getCoverArt(testUser, "f1");

        assertEquals(404, response.getStatusCode().value());
    }

    // ---- getDuplicates ----

    @Test
    void getDuplicatesSuccess() {
        Map<String, Object> duplicateResult = Map.of("groups", List.of());
        when(libraryService.findDuplicates("user1", 0, 50)).thenReturn(duplicateResult);

        ResponseEntity<?> response = controller.getDuplicates(testUser, 0, 50);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(duplicateResult, response.getBody());
    }

    // ---- deleteFile ----

    @Test
    void deleteFileSuccess() {
        MusicFile file = MusicFile.builder().id("f1").title("Delete Me").build();
        when(libraryService.findFileByIdAndUserId("f1", "user1")).thenReturn(Optional.of(file));

        ResponseEntity<?> response = controller.deleteFile(testUser, "f1");

        assertEquals(200, response.getStatusCode().value());
        verify(libraryService).deleteFile(file, "user1");
        verify(auditService).log("testuser", AuditService.Action.FILE_DELETE, "f1");
    }

    @Test
    void deleteFileNotFound() {
        when(libraryService.findFileByIdAndUserId("missing", "user1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.deleteFile(testUser, "missing");

        assertEquals(404, response.getStatusCode().value());
        verify(libraryService, never()).deleteFile(any(), any());
    }

    // ---- clearLibrary ----

    @Test
    void clearLibrarySuccess() {
        when(libraryService.clearLibrary("user1")).thenReturn(42L);

        ResponseEntity<?> response = controller.clearLibrary(testUser);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(42L, body.get("filesRemoved"));
        verify(auditService).log("testuser", AuditService.Action.LIBRARY_CLEAR, null, "42 files removed");
    }

    // ---- trash endpoints ----

    @Test
    @SuppressWarnings("unchecked")
    void getTrashSuccess() {
        MusicFileDTO dto = MusicFileDTO.from(
                MusicFile.builder().id("f1").title("Trashed").deleted(true).build());
        Page<MusicFileDTO> trashPage = new org.springframework.data.domain.PageImpl<>(
                List.of(dto), org.springframework.data.domain.PageRequest.of(0, 50), 1);
        when(libraryService.getTrash("user1", 0, 50)).thenReturn(trashPage);

        ResponseEntity<?> response = controller.getTrash(testUser, 0, 50);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(1L, body.get("totalElements"));
    }

    @Test
    void restoreFileSuccess() {
        ResponseEntity<?> response = controller.restoreFile(testUser, "f1");

        assertEquals(200, response.getStatusCode().value());
        verify(libraryService).restoreFile("f1", "user1");
        verify(auditService).log("testuser", AuditService.Action.FILE_RESTORE, "f1");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("File restored", body.get("message"));
    }

    @Test
    void restoreFileNotFound() {
        doThrow(new IllegalArgumentException("not found"))
                .when(libraryService).restoreFile("missing", "user1");

        ResponseEntity<?> response = controller.restoreFile(testUser, "missing");

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void permanentlyDeleteFileSuccess() {
        ResponseEntity<?> response = controller.permanentlyDeleteFile(testUser, "f1");

        assertEquals(200, response.getStatusCode().value());
        verify(libraryService).permanentlyDeleteFile("f1", "user1");
        verify(auditService).log("testuser", AuditService.Action.FILE_PERMANENT_DELETE, "f1");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("File permanently deleted", body.get("message"));
    }

    @Test
    void permanentlyDeleteFileNotFound() {
        doThrow(new IllegalArgumentException("not found"))
                .when(libraryService).permanentlyDeleteFile("missing", "user1");

        ResponseEntity<?> response = controller.permanentlyDeleteFile(testUser, "missing");

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void emptyTrashSuccess() {
        ResponseEntity<?> response = controller.emptyTrash(testUser);

        assertEquals(200, response.getStatusCode().value());
        verify(libraryService).emptyTrash("user1");
        verify(auditService).log("testuser", AuditService.Action.TRASH_EMPTY);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("Trash emptied", body.get("message"));
    }

    // ---- exportLibrary ----

    @Test
    void exportLibraryAsJson() {
        MusicFile file = MusicFile.builder()
                .id("f1").title("Song").artist("Artist").album("Album")
                .year("2023").genre(Genre.HARD_ROCK).fileName("song.mp3").build();
        when(libraryService.getAllFiles("user1")).thenReturn(List.of(file));

        ResponseEntity<?> response = controller.exportLibrary(testUser, "json");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/json", response.getHeaders().getContentType().toString());
        assertTrue(response.getHeaders().getFirst("Content-Disposition").contains("library.json"));
    }

    @Test
    void exportLibraryAsCsv() {
        MusicFile file = MusicFile.builder()
                .id("f1").title("Song").artist("Artist").album("Album")
                .year("2023").genre(Genre.HARD_ROCK).fileName("song.mp3").build();
        when(libraryService.getAllFiles("user1")).thenReturn(List.of(file));

        ResponseEntity<?> response = controller.exportLibrary(testUser, "csv");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("text/csv", response.getHeaders().getContentType().toString());
        assertTrue(response.getHeaders().getFirst("Content-Disposition").contains("library.csv"));

        String csv = (String) response.getBody();
        assertTrue(csv.startsWith("title,artist,album,year,genre,rating,fileName\n"));
        assertTrue(csv.contains("Song"));
        assertTrue(csv.contains("HARD_ROCK"));
    }

    @Test
    void exportLibraryCsvEscapesCommas() {
        MusicFile file = MusicFile.builder()
                .id("f1").title("Hello, World").artist("Artist").album("Album")
                .year("2023").genre(Genre.OTHER).fileName("song.mp3").build();
        when(libraryService.getAllFiles("user1")).thenReturn(List.of(file));

        ResponseEntity<?> response = controller.exportLibrary(testUser, "csv");

        String csv = (String) response.getBody();
        assertTrue(csv.contains("\"Hello, World\""));
    }

    @Test
    void exportLibraryCsvTooLarge() {
        // Create a list that exceeds MAX_EXPORT_SIZE (50,000)
        List<MusicFile> largeList = new java.util.ArrayList<>();
        for (int i = 0; i < 50_001; i++) {
            largeList.add(MusicFile.builder().id("f" + i).build());
        }
        when(libraryService.getAllFiles("user1")).thenReturn(largeList);

        ResponseEntity<?> response = controller.exportLibrary(testUser, "csv");

        assertEquals(413, response.getStatusCode().value());
    }

    @Test
    void exportLibraryJsonStreamsLargeResult() {
        // JSON export now streams — returns 200 with StreamingResponseBody instead of 413
        List<MusicFile> largeList = new java.util.ArrayList<>();
        for (int i = 0; i < 50_001; i++) {
            largeList.add(MusicFile.builder().id("f" + i).build());
        }
        when(libraryService.getAllFiles("user1")).thenReturn(largeList);

        ResponseEntity<?> response = controller.exportLibrary(testUser, "json");

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody() instanceof org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody);
    }

    @Test
    void exportLibraryDefaultsToJson() {
        when(libraryService.getAllFiles("user1")).thenReturn(List.of());

        ResponseEntity<?> response = controller.exportLibrary(testUser, "json");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/json", response.getHeaders().getContentType().toString());
    }

    // ---- scan schedule endpoints ----

    @Test
    void setScanScheduleSuccess() throws IOException {
        String realPath = tempDir.toRealPath().toString();
        ScanScheduleRequest request = new ScanScheduleRequest();
        request.setCronExpression("0 0 * * * *");
        request.setPath(realPath);

        ResponseEntity<?> response = controller.setScanSchedule(testUser, request);

        assertEquals(200, response.getStatusCode().value());
        verify(userRepository).save(testUser);
        assertEquals("0 0 * * * *", testUser.getScanSchedule());
        assertEquals(realPath, testUser.getScanPath());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("0 0 * * * *", body.get("cronExpression"));
        assertEquals(realPath, body.get("path"));
    }

    @Test
    void setScanScheduleInvalidCron() {
        ScanScheduleRequest request = new ScanScheduleRequest();
        request.setCronExpression("not-a-cron");
        request.setPath(tempDir.toString());

        ResponseEntity<?> response = controller.setScanSchedule(testUser, request);

        assertEquals(400, response.getStatusCode().value());
        verify(userRepository, never()).save(any());
    }

    @Test
    void setScanScheduleInvalidPath() {
        ScanScheduleRequest request = new ScanScheduleRequest();
        request.setCronExpression("0 0 * * * *");
        request.setPath("/nonexistent/path/that/does/not/exist");

        ResponseEntity<?> response = controller.setScanSchedule(testUser, request);

        assertEquals(400, response.getStatusCode().value());
        verify(userRepository, never()).save(any());
    }

    @Test
    void getScanScheduleSuccess() {
        testUser.setScanSchedule("0 0 * * * *");
        testUser.setScanPath("/music");
        testUser.setLastScheduledScan(Instant.parse("2025-01-01T00:00:00Z"));

        ResponseEntity<?> response = controller.getScanSchedule(testUser);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("0 0 * * * *", body.get("cronExpression"));
        assertEquals("/music", body.get("path"));
        assertEquals(Instant.parse("2025-01-01T00:00:00Z"), body.get("lastScheduledScan"));
    }

    @Test
    void getScanScheduleWhenNoneSet() {
        ResponseEntity<?> response = controller.getScanSchedule(testUser);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNull(body.get("cronExpression"));
        assertNull(body.get("path"));
    }

    @Test
    void clearScanScheduleSuccess() {
        testUser.setScanSchedule("0 0 * * * *");
        testUser.setScanPath("/music");

        ResponseEntity<?> response = controller.clearScanSchedule(testUser);

        assertEquals(200, response.getStatusCode().value());
        assertNull(testUser.getScanSchedule());
        assertNull(testUser.getScanPath());
        verify(userRepository).save(testUser);
    }

    // ---- playback queue endpoints ----

    @Test
    void getQueueWhenExists() {
        PlaybackQueue queue = new PlaybackQueue();
        queue.setUserId("user1");
        queue.setTrackIds(List.of("t1", "t2"));
        queue.setCurrentTrackId("t1");
        queue.setShuffle(true);
        when(playbackQueueRepository.findByUserId("user1")).thenReturn(Optional.of(queue));

        ResponseEntity<?> response = controller.getQueue(testUser);

        assertEquals(200, response.getStatusCode().value());
        PlaybackQueueDTO dto = (PlaybackQueueDTO) response.getBody();
        assertEquals(List.of("t1", "t2"), dto.getTrackIds());
        assertEquals("t1", dto.getCurrentTrackId());
        assertTrue(dto.isShuffle());
    }

    @Test
    void getQueueWhenEmpty() {
        when(playbackQueueRepository.findByUserId("user1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getQueue(testUser);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(List.of(), body.get("trackIds"));
        assertEquals("", body.get("currentTrackId"));
        assertEquals(false, body.get("shuffle"));
    }

    @Test
    void saveQueueCreatesNew() {
        when(playbackQueueRepository.findByUserId("user1")).thenReturn(Optional.empty());
        when(libraryService.findOwnedTrackIds(List.of("t1", "t2", "t3"), "user1"))
                .thenReturn(java.util.Set.of("t1", "t2", "t3"));

        PlaybackQueueDTO dto = new PlaybackQueueDTO();
        dto.setTrackIds(List.of("t1", "t2", "t3"));
        dto.setCurrentTrackId("t2");
        dto.setShuffle(false);

        ResponseEntity<?> response = controller.saveQueue(testUser, dto);

        assertEquals(200, response.getStatusCode().value());
        verify(playbackQueueRepository).save(argThat(q ->
                q.getUserId().equals("user1") &&
                q.getTrackIds().equals(List.of("t1", "t2", "t3")) &&
                q.getCurrentTrackId().equals("t2") &&
                !q.isShuffle()
        ));
    }

    @Test
    void saveQueueUpdatesExisting() {
        PlaybackQueue existing = new PlaybackQueue();
        existing.setId("q1");
        existing.setUserId("user1");
        existing.setTrackIds(List.of("old1"));
        when(playbackQueueRepository.findByUserId("user1")).thenReturn(Optional.of(existing));
        when(libraryService.findOwnedTrackIds(List.of("new1", "new2"), "user1"))
                .thenReturn(java.util.Set.of("new1", "new2"));

        PlaybackQueueDTO dto = new PlaybackQueueDTO();
        dto.setTrackIds(List.of("new1", "new2"));
        dto.setCurrentTrackId("new1");
        dto.setShuffle(true);

        ResponseEntity<?> response = controller.saveQueue(testUser, dto);

        assertEquals(200, response.getStatusCode().value());
        verify(playbackQueueRepository).save(argThat(q ->
                q.getId().equals("q1") &&
                q.getTrackIds().equals(List.of("new1", "new2")) &&
                q.isShuffle()
        ));
    }

    @Test
    void saveQueueHandlesNullTrackIds() {
        when(playbackQueueRepository.findByUserId("user1")).thenReturn(Optional.empty());

        PlaybackQueueDTO dto = new PlaybackQueueDTO();
        dto.setTrackIds(null);
        dto.setCurrentTrackId("t1");

        controller.saveQueue(testUser, dto);

        verify(playbackQueueRepository).save(argThat(q ->
                q.getTrackIds().equals(List.of())
        ));
    }

    @Test
    void clearQueueSuccess() {
        ResponseEntity<?> response = controller.clearQueue(testUser);

        assertEquals(200, response.getStatusCode().value());
        verify(playbackQueueRepository).deleteByUserId("user1");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("Queue cleared", body.get("message"));
    }

    // ---- getStatistics ----

    @Test
    void getStatisticsSuccess() {
        Map<String, Object> stats = Map.of("totalFiles", 100, "totalArtists", 20);
        when(libraryService.getStatistics("user1")).thenReturn(stats);

        ResponseEntity<?> response = controller.getStatistics(testUser);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(stats, response.getBody());
    }

    // ---- scan endpoint ----

    @Test
    void scanReturns429WhenRateLimited() {
        when(scanRateLimiter.tryAcquire("user1")).thenReturn(false);
        when(scanRateLimiter.secondsUntilAllowed("user1")).thenReturn(45L);

        ScanRequest request = new ScanRequest();
        request.setPath("/some/path");
        ResponseEntity<?> response = controller.scanDirectory(testUser, request);

        assertEquals(429, response.getStatusCode().value());
        ProblemDetail body = (ProblemDetail) response.getBody();
        assertNotNull(body);
        assertEquals(45L, body.getProperties().get("retryAfter"));
    }

    @Test
    void scanSuccessful() throws Exception {
        String realPath = tempDir.toRealPath().toString();
        ScanResult result = new ScanResult();
        result.addSaved(10);
        when(scannerService.scanDirectory(eq(testUser), eq(realPath))).thenReturn(result);

        ScanRequest request = new ScanRequest();
        request.setPath(realPath);

        ResponseEntity<?> response = controller.scanDirectory(testUser, request);

        assertEquals(200, response.getStatusCode().value());
        verify(userRepository).save(testUser);
        assertEquals(realPath, testUser.getMusicDirectory());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(10, body.get("filesFound"));
    }

    @Test
    void scanRejectsBadPath() throws Exception {
        ScanRequest request = new ScanRequest();
        request.setPath("/nonexistent/path/that/does/not/exist");

        ResponseEntity<?> response = controller.scanDirectory(testUser, request);

        assertEquals(400, response.getStatusCode().value());
        verify(scannerService, never()).scanDirectory(any(), any());
    }

    @Test
    void scanRejectsEmptyPath() {
        ScanRequest request = new ScanRequest();
        request.setPath("  ");

        ResponseEntity<?> response = controller.scanDirectory(testUser, request);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void scanHandlesUnexpectedError() throws Exception {
        String realPath = tempDir.toRealPath().toString();
        when(scannerService.scanDirectory(eq(testUser), eq(realPath)))
                .thenThrow(new RuntimeException("disk failure"));

        ScanRequest request = new ScanRequest();
        request.setPath(realPath);

        ResponseEntity<?> response = controller.scanDirectory(testUser, request);

        assertEquals(500, response.getStatusCode().value());
    }

    // --- sanitizeFilename tests ---

    @Test
    void sanitizeFilename_removesQuotesAndBackslashes() {
        assertEquals("song_name_.mp3", LibraryController.sanitizeFilename("song\"name\".mp3"));
        assertEquals("path_file.mp3", LibraryController.sanitizeFilename("path\\file.mp3"));
    }

    @Test
    void sanitizeFilename_removesSemicolonsAndSlashes() {
        assertEquals("file_name.mp3", LibraryController.sanitizeFilename("file;name.mp3"));
        assertEquals("path_to_file.mp3", LibraryController.sanitizeFilename("path/to/file.mp3"));
    }

    @Test
    void sanitizeFilename_removesControlCharacters() {
        assertEquals("file_name.mp3", LibraryController.sanitizeFilename("file\r\nname.mp3"));
        assertEquals("file_name.mp3", LibraryController.sanitizeFilename("file\tname.mp3"));
    }

    @Test
    void sanitizeFilename_collapsesMultipleUnderscores() {
        assertEquals("a_b.mp3", LibraryController.sanitizeFilename("a\"\"\"b.mp3"));
    }

    @Test
    void sanitizeFilename_returnsDefaultForNullOrBlank() {
        assertEquals("download.mp3", LibraryController.sanitizeFilename(null));
        assertEquals("download.mp3", LibraryController.sanitizeFilename(""));
        assertEquals("download.mp3", LibraryController.sanitizeFilename("   "));
    }

    @Test
    void sanitizeFilename_preservesSafeNames() {
        assertEquals("My Song (Live).mp3", LibraryController.sanitizeFilename("My Song (Live).mp3"));
        assertEquals("track-01_remix.mp3", LibraryController.sanitizeFilename("track-01_remix.mp3"));
    }
}
