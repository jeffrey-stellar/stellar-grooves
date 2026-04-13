package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.MusicFileRepository;
import com.stellarideas.grooves.repository.PlaylistRepository;
import com.stellarideas.grooves.repository.UserRepository;
import com.stellarideas.grooves.service.MusicScannerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LibraryControllerTest {

    private LibraryController controller;
    private MusicFileRepository musicFileRepository;
    private PlaylistRepository playlistRepository;
    private UserRepository userRepository;
    private MusicScannerService scannerService;
    private User testUser;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        scannerService = mock(MusicScannerService.class);
        musicFileRepository = mock(MusicFileRepository.class);
        playlistRepository = mock(PlaylistRepository.class);
        controller = new LibraryController(userRepository, scannerService, musicFileRepository, playlistRepository);

        testUser = new User();
        testUser.setUsername("testuser");

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));

        var userDetails = org.springframework.security.core.userdetails.User
                .withUsername("testuser")
                .password("irrelevant")
                .authorities(List.of())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
    }

    @Test
    void getFilesEnforcesPagination() {
        MusicFile file = MusicFile.builder()
                .id("f1").title("Test Song").artist("Artist").genre(Genre.CLASSIC_ROCK).build();
        Page<MusicFile> page = new PageImpl<>(List.of(file));
        when(musicFileRepository.findByUser(eq(testUser), any(Pageable.class))).thenReturn(page);

        ResponseEntity<?> response = controller.getFiles(null, 0, 50);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("content"));
        assertTrue(body.containsKey("totalPages"));
    }

    @Test
    void getFilesClampsSizeToMax() {
        Page<MusicFile> page = new PageImpl<>(List.of());
        when(musicFileRepository.findByUser(eq(testUser), any(Pageable.class))).thenReturn(page);

        // Request size=9999 — should be clamped to MAX_PAGE_SIZE (200)
        controller.getFiles(null, 0, 9999);

        verify(musicFileRepository).findByUser(eq(testUser), argThat(pageable ->
                pageable.getPageSize() <= 200));
    }

    @Test
    void getFilesRejectsInvalidGenre() {
        assertThrows(IllegalArgumentException.class, () ->
                controller.getFiles("NOT_A_GENRE", 0, 50));
    }

    @Test
    void getFilesFiltersByGenre() {
        Page<MusicFile> page = new PageImpl<>(List.of());
        when(musicFileRepository.findByUserAndGenre(eq(testUser), eq(Genre.HARD_ROCK), any(Pageable.class)))
                .thenReturn(page);

        ResponseEntity<?> response = controller.getFiles("HARD_ROCK", 0, 50);

        assertEquals(200, response.getStatusCode().value());
        verify(musicFileRepository).findByUserAndGenre(eq(testUser), eq(Genre.HARD_ROCK), any(Pageable.class));
    }

    @Test
    void deleteFileRemovesFromRepository() {
        MusicFile file = MusicFile.builder().id("f1").title("Delete Me").build();
        when(musicFileRepository.findByIdAndUser("f1", testUser)).thenReturn(Optional.of(file));
        when(playlistRepository.findByUser(testUser)).thenReturn(List.of());

        ResponseEntity<?> response = controller.deleteFile("f1");

        assertEquals(200, response.getStatusCode().value());
        verify(musicFileRepository).delete(file);
    }

    @Test
    void deleteFileReturns404ForMissingFile() {
        when(musicFileRepository.findByIdAndUser("missing", testUser)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.deleteFile("missing");

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void streamFileReturns404ForOtherUsersFile() throws java.io.IOException {
        when(musicFileRepository.findByIdAndUser("other-user-file", testUser)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.streamFile("other-user-file",
                new org.springframework.http.HttpHeaders());

        assertEquals(404, response.getStatusCode().value());
    }
}
