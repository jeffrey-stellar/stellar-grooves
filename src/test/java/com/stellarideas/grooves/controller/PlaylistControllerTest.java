package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.dto.CreatePlaylistRequest;
import com.stellarideas.grooves.dto.MusicFileDTO;
import com.stellarideas.grooves.dto.PlaylistDTO;
import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.Playlist;
import com.stellarideas.grooves.model.Role;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.service.AuditService;
import com.stellarideas.grooves.service.MessageHelper;
import com.stellarideas.grooves.service.PlaylistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlaylistControllerTest {

    private PlaylistController controller;
    private PlaylistService playlistService;
    private User testUser;

    @BeforeEach
    void setUp() {
        playlistService = mock(PlaylistService.class);

        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        MessageHelper msgHelper = new MessageHelper(messageSource);

        AuditService auditService = mock(AuditService.class);
        controller = new PlaylistController(playlistService, msgHelper, auditService);

        testUser = User.builder().username("testuser").email("t@t.com").password("enc").build();
        testUser.setId("user1");
        testUser.setRoles(Set.of(Role.ROLE_USER));
    }

    private CreatePlaylistRequest playlistRequest(String name) {
        CreatePlaylistRequest req = new CreatePlaylistRequest();
        req.setName(name);
        return req;
    }

    @Test
    void createPlaylistSucceeds() {
        Playlist saved = new Playlist();
        saved.setId("pl1");
        saved.setName("My Playlist");
        saved.setUserId("user1");
        when(playlistService.createPlaylist("My Playlist", "user1")).thenReturn(saved);

        ResponseEntity<?> response = controller.createPlaylist(testUser, playlistRequest("My Playlist"));

        assertEquals(200, response.getStatusCode().value());
        PlaylistDTO body = (PlaylistDTO) response.getBody();
        assertEquals("pl1", body.getId());
        assertEquals("My Playlist", body.getName());
        assertEquals(0, body.getTrackCount());
    }

    @Test
    void createPlaylistAccepts80CharName() {
        String name80 = "A".repeat(80);
        Playlist saved = new Playlist();
        saved.setId("pl2");
        saved.setName(name80);
        saved.setUserId("user1");
        when(playlistService.createPlaylist(name80, "user1")).thenReturn(saved);

        ResponseEntity<?> response = controller.createPlaylist(testUser, playlistRequest(name80));
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void getPlaylistTracksPreservesOrder() {
        Playlist playlist = new Playlist();
        playlist.setId("pl1");
        playlist.setName("Test");
        playlist.setUserId("user1");
        playlist.setTrackIds(new ArrayList<>(List.of("f1", "f2", "f3")));

        when(playlistService.findByIdAndUserId("pl1", "user1")).thenReturn(Optional.of(playlist));

        MusicFileDTO dto1 = new MusicFileDTO(); dto1.setId("f1"); dto1.setArtist("A1"); dto1.setTitle("S1");
        MusicFileDTO dto2 = new MusicFileDTO(); dto2.setId("f2"); dto2.setArtist("A2"); dto2.setTitle("S2");
        MusicFileDTO dto3 = new MusicFileDTO(); dto3.setId("f3"); dto3.setArtist("A3"); dto3.setTitle("S3");
        when(playlistService.getPlaylistTracks(playlist, "user1")).thenReturn(List.of(dto1, dto2, dto3));

        ResponseEntity<?> response = controller.getPlaylistTracks(testUser, "pl1");
        assertEquals(200, response.getStatusCode().value());

        @SuppressWarnings("unchecked")
        List<MusicFileDTO> tracks = (List<MusicFileDTO>) response.getBody();
        assertEquals(3, tracks.size());
        assertEquals("f1", tracks.get(0).getId());
        assertEquals("f2", tracks.get(1).getId());
        assertEquals("f3", tracks.get(2).getId());
    }

    @Test
    void getPlaylistTracksSkipsDeletedFiles() {
        Playlist playlist = new Playlist();
        playlist.setId("pl1");
        playlist.setName("Test");
        playlist.setUserId("user1");
        playlist.setTrackIds(new ArrayList<>(List.of("f1", "f_deleted", "f3")));

        when(playlistService.findByIdAndUserId("pl1", "user1")).thenReturn(Optional.of(playlist));

        MusicFileDTO dto1 = new MusicFileDTO(); dto1.setId("f1");
        MusicFileDTO dto3 = new MusicFileDTO(); dto3.setId("f3");
        when(playlistService.getPlaylistTracks(playlist, "user1")).thenReturn(List.of(dto1, dto3));

        ResponseEntity<?> response = controller.getPlaylistTracks(testUser, "pl1");
        @SuppressWarnings("unchecked")
        List<MusicFileDTO> tracks = (List<MusicFileDTO>) response.getBody();
        assertEquals(2, tracks.size());
        assertEquals("f1", tracks.get(0).getId());
        assertEquals("f3", tracks.get(1).getId());
    }

    @Test
    void getPlaylistTracksReturns404ForNonexistent() {
        when(playlistService.findByIdAndUserId("nope", "user1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getPlaylistTracks(testUser, "nope");
        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void getPlaylistTracksReturnsEmptyForEmptyPlaylist() {
        Playlist playlist = new Playlist();
        playlist.setId("pl1");
        playlist.setName("Empty");
        playlist.setUserId("user1");
        playlist.setTrackIds(new ArrayList<>());

        when(playlistService.findByIdAndUserId("pl1", "user1")).thenReturn(Optional.of(playlist));
        when(playlistService.getPlaylistTracks(playlist, "user1")).thenReturn(List.of());

        ResponseEntity<?> response = controller.getPlaylistTracks(testUser, "pl1");
        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<?> tracks = (List<?>) response.getBody();
        assertTrue(tracks.isEmpty());
    }
}
