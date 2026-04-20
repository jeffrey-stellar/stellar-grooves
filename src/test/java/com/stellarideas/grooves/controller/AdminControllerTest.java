package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.model.Role;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.CoverArtRepository;
import com.stellarideas.grooves.repository.MusicFileRepository;
import com.stellarideas.grooves.repository.PlayEventRepository;
import com.stellarideas.grooves.repository.PlaylistRepository;
import com.stellarideas.grooves.repository.UserRepository;
import com.stellarideas.grooves.service.AuditService;
import com.stellarideas.grooves.service.MessageHelper;
import com.stellarideas.grooves.service.MusicCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminControllerTest {

    private AdminController controller;
    private UserRepository userRepository;
    private MusicFileRepository musicFileRepository;
    private PlaylistRepository playlistRepository;
    private CoverArtRepository coverArtRepository;
    private PlayEventRepository playEventRepository;
    private AuditService auditService;
    private MusicCatalogService catalogService;
    private User adminUser;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        musicFileRepository = mock(MusicFileRepository.class);
        playlistRepository = mock(PlaylistRepository.class);
        coverArtRepository = mock(CoverArtRepository.class);
        playEventRepository = mock(PlayEventRepository.class);
        auditService = mock(AuditService.class);
        catalogService = mock(MusicCatalogService.class);

        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        MessageHelper msgHelper = new MessageHelper(messageSource);

        controller = new AdminController(userRepository, musicFileRepository,
                playlistRepository, coverArtRepository, playEventRepository, msgHelper, auditService, catalogService);

        adminUser = new User();
        adminUser.setId("admin1");
        adminUser.setUsername("admin");
        adminUser.setRoles(Set.of(Role.ROLE_ADMIN));
    }

    @Test
    void getStatsReturnsCounts() {
        when(userRepository.count()).thenReturn(10L);
        when(musicFileRepository.count()).thenReturn(500L);
        when(playlistRepository.count()).thenReturn(20L);

        ResponseEntity<?> response = controller.getStats();

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(10L, body.get("totalUsers"));
        assertEquals(500L, body.get("totalFiles"));
        assertEquals(20L, body.get("totalPlaylists"));
    }

    @Test
    void getAllUsersReturnsPaginatedList() {
        User user1 = User.builder().id("u1").username("user1").email("u1@test.com")
                .roles(Set.of(Role.ROLE_USER)).build();
        Page<User> page = new PageImpl<>(List.of(user1), PageRequest.of(0, 25), 1);
        when(userRepository.findAll(any(PageRequest.class))).thenReturn(page);
        when(musicFileRepository.countByUserId("u1")).thenReturn(42L);

        ResponseEntity<?> response = controller.getAllUsers(adminUser, 0, 25);

        assertEquals(200, response.getStatusCode().value());
        verify(auditService).log(eq("admin"), eq(AuditService.Action.ADMIN_VIEW_USERS));
    }

    @Test
    void getAllUsersClampsSizeToMax() {
        Page<User> page = new PageImpl<>(List.of(), PageRequest.of(0, 100), 0);
        when(userRepository.findAll(any(PageRequest.class))).thenReturn(page);

        controller.getAllUsers(adminUser, 0, 500);

        // Verify clamped to MAX_PAGE_SIZE (100)
        verify(userRepository).findAll(PageRequest.of(0, 100));
    }

    @Test
    void getAllUsersClampsSizeMinimumToOne() {
        Page<User> page = new PageImpl<>(List.of(), PageRequest.of(0, 1), 0);
        when(userRepository.findAll(any(PageRequest.class))).thenReturn(page);

        controller.getAllUsers(adminUser, 0, 0);

        verify(userRepository).findAll(PageRequest.of(0, 1));
    }

    @Test
    void getUserByIdReturnsUser() {
        User user = User.builder().id("u1").username("user1").email("u1@test.com")
                .musicDirectory("/music").roles(Set.of(Role.ROLE_USER)).build();
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));
        when(musicFileRepository.countByUserId("u1")).thenReturn(5L);

        ResponseEntity<?> response = controller.getUserById("u1");

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("u1", body.get("id"));
        assertEquals("user1", body.get("username"));
        assertEquals("u1@test.com", body.get("email"));
        assertEquals("/music", body.get("musicDirectory"));
        assertEquals(5L, body.get("fileCount"));
        assertNull(body.get("password"));
    }

    @Test
    void getUserByIdReturns404ForMissingUser() {
        when(userRepository.findById("missing")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getUserById("missing");

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void deleteUserCascadesAndRemovesAllData() {
        User target = User.builder().id("u2").username("victim").email("v@test.com")
                .roles(Set.of(Role.ROLE_USER)).build();
        when(userRepository.findById("u2")).thenReturn(Optional.of(target));
        when(musicFileRepository.deleteByUserId("u2")).thenReturn(10L);
        when(playlistRepository.deleteByUserId("u2")).thenReturn(3L);

        ResponseEntity<?> response = controller.deleteUser(adminUser, "u2");

        assertEquals(200, response.getStatusCode().value());
        verify(coverArtRepository).deleteByUserId("u2");
        verify(userRepository).deleteById("u2");
        verify(auditService).log(eq("admin"), eq(AuditService.Action.ADMIN_DELETE_USER),
                eq("victim"), contains("10 files"));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(10L, body.get("filesRemoved"));
        assertEquals(3L, body.get("playlistsRemoved"));
    }

    @Test
    void deleteUserReturns404ForMissingUser() {
        when(userRepository.findById("missing")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.deleteUser(adminUser, "missing");

        assertEquals(404, response.getStatusCode().value());
        verify(userRepository, never()).deleteById(anyString());
    }
}
