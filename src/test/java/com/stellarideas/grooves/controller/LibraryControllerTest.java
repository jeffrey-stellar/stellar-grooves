package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.service.AuditService;
import com.stellarideas.grooves.service.LibraryService;
import com.stellarideas.grooves.service.MessageHelper;
import com.stellarideas.grooves.service.MusicScannerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LibraryControllerTest {

    private LibraryController controller;
    private LibraryService libraryService;
    private MusicScannerService scannerService;
    private User testUser;

    @BeforeEach
    void setUp() {
        scannerService = mock(MusicScannerService.class);
        libraryService = mock(LibraryService.class);

        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        MessageHelper msgHelper = new MessageHelper(messageSource);

        AuditService auditService = mock(AuditService.class);
        com.stellarideas.grooves.repository.UserRepository userRepository = mock(com.stellarideas.grooves.repository.UserRepository.class);
        controller = new LibraryController(scannerService, libraryService, msgHelper, auditService, userRepository);

        testUser = new User();
        testUser.setId("user1");
        testUser.setUsername("testuser");
    }

    @Test
    void getFilesEnforcesPagination() {
        MusicFile file = MusicFile.builder()
                .id("f1").title("Test Song").artist("Artist").genre(Genre.CLASSIC_ROCK).build();
        Page<MusicFile> page = new PageImpl<>(List.of(file));
        when(libraryService.getFiles(eq("user1"), isNull(), eq(0), eq(50))).thenReturn(page);

        ResponseEntity<?> response = controller.getFiles(testUser, null, 0, 50);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("content"));
        assertTrue(body.containsKey("totalPages"));
    }

    @Test
    void getFilesRejectsInvalidGenre() {
        assertThrows(IllegalArgumentException.class, () ->
                controller.getFiles(testUser, "NOT_A_GENRE", 0, 50));
    }

    @Test
    void getFilesFiltersByGenre() {
        Page<MusicFile> page = new PageImpl<>(List.of());
        when(libraryService.getFiles(eq("user1"), eq(Genre.HARD_ROCK), eq(0), eq(50))).thenReturn(page);

        ResponseEntity<?> response = controller.getFiles(testUser, "HARD_ROCK", 0, 50);

        assertEquals(200, response.getStatusCode().value());
        verify(libraryService).getFiles(eq("user1"), eq(Genre.HARD_ROCK), eq(0), eq(50));
    }

    @Test
    void deleteFileRemovesViaService() {
        MusicFile file = MusicFile.builder().id("f1").title("Delete Me").build();
        when(libraryService.findFileByIdAndUserId("f1", "user1")).thenReturn(Optional.of(file));

        ResponseEntity<?> response = controller.deleteFile(testUser, "f1");

        assertEquals(200, response.getStatusCode().value());
        verify(libraryService).deleteFile(file, "user1");
    }

    @Test
    void deleteFileReturns404ForMissingFile() {
        when(libraryService.findFileByIdAndUserId("missing", "user1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.deleteFile(testUser, "missing");

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void streamFileReturns404ForOtherUsersFile() throws java.io.IOException {
        when(libraryService.findFileByIdAndUserId("other-user-file", "user1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.streamFile(testUser, "other-user-file",
                new org.springframework.http.HttpHeaders());

        assertEquals(404, response.getStatusCode().value());
    }
}
