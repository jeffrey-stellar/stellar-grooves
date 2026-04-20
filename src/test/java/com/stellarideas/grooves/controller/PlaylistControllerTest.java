package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.dto.AddTrackRequest;
import com.stellarideas.grooves.dto.CreatePlaylistRequest;
import com.stellarideas.grooves.dto.MusicFileDTO;
import com.stellarideas.grooves.dto.PlaylistDTO;
import com.stellarideas.grooves.dto.ReorderTracksRequest;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

    private Playlist testPlaylist(String id, String name) {
        Playlist p = new Playlist();
        p.setId(id);
        p.setName(name);
        p.setUserId("user1");
        p.setTrackIds(new ArrayList<>());
        return p;
    }

    // --- CRUD ---

    @Test
    void createPlaylistSucceeds() {
        Playlist saved = testPlaylist("pl1", "My Playlist");
        when(playlistService.createPlaylist("My Playlist", "user1")).thenReturn(saved);

        CreatePlaylistRequest req = new CreatePlaylistRequest();
        req.setName("My Playlist");

        ResponseEntity<?> response = controller.createPlaylist(testUser, req);

        assertEquals(200, response.getStatusCode().value());
        PlaylistDTO body = (PlaylistDTO) response.getBody();
        assertEquals("pl1", body.getId());
    }

    @Test
    void deletePlaylistSucceeds() {
        Playlist playlist = testPlaylist("pl1", "Rock Mix");
        when(playlistService.findByIdAndUserId("pl1", "user1")).thenReturn(Optional.of(playlist));

        ResponseEntity<?> response = controller.deletePlaylist(testUser, "pl1");

        assertEquals(200, response.getStatusCode().value());
        verify(playlistService).deletePlaylist(playlist);
    }

    @Test
    void deletePlaylistReturns404() {
        when(playlistService.findByIdAndUserId("nope", "user1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.deletePlaylist(testUser, "nope");

        assertEquals(404, response.getStatusCode().value());
    }

    // --- Track operations ---

    @Test
    void addTrackSucceeds() {
        Playlist playlist = testPlaylist("pl1", "Test");
        playlist.setTrackIds(new ArrayList<>(List.of("f1")));
        when(playlistService.findByIdAndUserId("pl1", "user1")).thenReturn(Optional.of(playlist));
        when(playlistService.addTrack(playlist, "f2", "user1")).thenReturn(true);

        AddTrackRequest req = new AddTrackRequest();
        req.setFileId("f2");

        ResponseEntity<?> response = controller.addTrack(testUser, "pl1", req);

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void addTrackReturns404ForMissingPlaylist() {
        when(playlistService.findByIdAndUserId("nope", "user1")).thenReturn(Optional.empty());

        AddTrackRequest req = new AddTrackRequest();
        req.setFileId("f1");

        ResponseEntity<?> response = controller.addTrack(testUser, "nope", req);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void addTrackReturns404ForMissingFile() {
        Playlist playlist = testPlaylist("pl1", "Test");
        when(playlistService.findByIdAndUserId("pl1", "user1")).thenReturn(Optional.of(playlist));
        when(playlistService.addTrack(playlist, "missing", "user1")).thenReturn(false);

        AddTrackRequest req = new AddTrackRequest();
        req.setFileId("missing");

        ResponseEntity<?> response = controller.addTrack(testUser, "pl1", req);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void removeTrackSucceeds() {
        Playlist playlist = testPlaylist("pl1", "Test");
        playlist.setTrackIds(new ArrayList<>(List.of("f1")));
        when(playlistService.findByIdAndUserId("pl1", "user1")).thenReturn(Optional.of(playlist));

        ResponseEntity<?> response = controller.removeTrack(testUser, "pl1", "f1");

        assertEquals(200, response.getStatusCode().value());
        verify(playlistService).removeTrack(playlist, "f1");
    }

    @Test
    void getPlaylistTracksPreservesOrder() {
        Playlist playlist = testPlaylist("pl1", "Test");
        playlist.setTrackIds(new ArrayList<>(List.of("f1", "f2")));
        when(playlistService.findByIdAndUserId("pl1", "user1")).thenReturn(Optional.of(playlist));

        MusicFileDTO dto1 = new MusicFileDTO();
        dto1.setId("f1");
        MusicFileDTO dto2 = new MusicFileDTO();
        dto2.setId("f2");
        when(playlistService.getPlaylistTracks(playlist, "user1")).thenReturn(
                Map.of("tracks", List.of(dto1, dto2), "missingTracks", List.of()));

        ResponseEntity<?> response = controller.getPlaylistTracks(testUser, "pl1", null, null);

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void getPlaylistTracksReturns404() {
        when(playlistService.findByIdAndUserId("nope", "user1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getPlaylistTracks(testUser, "nope", null, null);

        assertEquals(404, response.getStatusCode().value());
    }

    // --- Reorder ---

    @Test
    void reorderTracksSucceeds() {
        Playlist playlist = testPlaylist("pl1", "Test");
        playlist.setTrackIds(new ArrayList<>(List.of("f1", "f2")));
        when(playlistService.findByIdAndUserId("pl1", "user1")).thenReturn(Optional.of(playlist));
        when(playlistService.reorderTracks(playlist, List.of("f2", "f1"))).thenReturn(true);

        ReorderTracksRequest req = new ReorderTracksRequest();
        req.setTrackIds(List.of("f2", "f1"));

        ResponseEntity<?> response = controller.reorderTracks(testUser, "pl1", req);

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void reorderTracksRejectsMismatch() {
        Playlist playlist = testPlaylist("pl1", "Test");
        playlist.setTrackIds(new ArrayList<>(List.of("f1", "f2")));
        when(playlistService.findByIdAndUserId("pl1", "user1")).thenReturn(Optional.of(playlist));
        when(playlistService.reorderTracks(playlist, List.of("f1", "f3"))).thenReturn(false);

        ReorderTracksRequest req = new ReorderTracksRequest();
        req.setTrackIds(List.of("f1", "f3"));

        ResponseEntity<?> response = controller.reorderTracks(testUser, "pl1", req);

        assertEquals(400, response.getStatusCode().value());
    }

    // --- Share ---

    @Test
    void generateShareTokenSucceeds() {
        Playlist playlist = testPlaylist("pl1", "Test");
        when(playlistService.findByIdAndUserId("pl1", "user1")).thenReturn(Optional.of(playlist));
        when(playlistService.generateShareToken(eq(playlist), isNull())).thenReturn("share-token-123");

        ResponseEntity<?> response = controller.generateShareToken(testUser, "pl1", null);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("share-token-123", body.get("shareToken"));
    }

    @Test
    void generateShareTokenWithExpirationSucceeds() {
        Playlist playlist = testPlaylist("pl1", "Test");
        playlist.setShareTokenExpiresAt(java.time.Instant.now().plusSeconds(86400 * 30));
        when(playlistService.findByIdAndUserId("pl1", "user1")).thenReturn(Optional.of(playlist));
        when(playlistService.generateShareToken(eq(playlist), eq(30))).thenReturn("share-token-456");

        ResponseEntity<?> response = controller.generateShareToken(testUser, "pl1", 30);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("share-token-456", body.get("shareToken"));
        assertNotNull(body.get("expiresAt"));
    }

    @Test
    void generateShareTokenRejectsInvalidExpiration() {
        Playlist playlist = testPlaylist("pl1", "Test");
        when(playlistService.findByIdAndUserId("pl1", "user1")).thenReturn(Optional.of(playlist));

        ResponseEntity<?> response = controller.generateShareToken(testUser, "pl1", 0);
        assertEquals(400, response.getStatusCode().value());

        ResponseEntity<?> response2 = controller.generateShareToken(testUser, "pl1", 400);
        assertEquals(400, response2.getStatusCode().value());
    }

    @Test
    void revokeShareTokenSucceeds() {
        Playlist playlist = testPlaylist("pl1", "Test");
        when(playlistService.findByIdAndUserId("pl1", "user1")).thenReturn(Optional.of(playlist));

        ResponseEntity<?> response = controller.revokeShareToken(testUser, "pl1");

        assertEquals(200, response.getStatusCode().value());
        verify(playlistService).revokeShareToken(playlist);
    }

    // --- Export ---

    @Test
    void exportPlaylistAsJson() {
        Playlist playlist = testPlaylist("pl1", "Rock Mix");
        when(playlistService.findByIdAndUserId("pl1", "user1")).thenReturn(Optional.of(playlist));

        MusicFile f1 = MusicFile.builder().id("f1").title("Song 1").artist("Artist 1")
                .album("Album 1").year("2020").fileName("song1.mp3").genre(Genre.CLASSIC_ROCK).build();
        when(playlistService.getOrderedFiles(playlist, "user1")).thenReturn(List.of(f1));

        ResponseEntity<?> response = controller.exportPlaylist(testUser, "pl1", "json");

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getHeaders().getContentType().toString().contains("json"));
    }

    @Test
    void exportPlaylistAsM3u() {
        Playlist playlist = testPlaylist("pl1", "Rock Mix");
        when(playlistService.findByIdAndUserId("pl1", "user1")).thenReturn(Optional.of(playlist));

        MusicFile f1 = MusicFile.builder().id("f1").title("Song 1").artist("Artist 1")
                .fileName("song1.mp3").genre(Genre.CLASSIC_ROCK).build();
        MusicFile f2 = MusicFile.builder().id("f2").title("Song 2").artist("")
                .fileName("song2.mp3").genre(Genre.OTHER).build();
        when(playlistService.getOrderedFiles(playlist, "user1")).thenReturn(List.of(f1, f2));

        ResponseEntity<?> response = controller.exportPlaylist(testUser, "pl1", "m3u");

        assertEquals(200, response.getStatusCode().value());
        String body = (String) response.getBody();
        assertTrue(body.startsWith("#EXTM3U"));
        assertTrue(body.contains("Artist 1 - Song 1"));
        assertTrue(body.contains("song2.mp3"));
    }

    @Test
    void exportPlaylistReturns404() {
        when(playlistService.findByIdAndUserId("nope", "user1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.exportPlaylist(testUser, "nope", "json");

        assertEquals(404, response.getStatusCode().value());
    }

    // --- Get playlists ---

    @Test
    void getPlaylistsReturnsList() {
        Playlist p1 = testPlaylist("pl1", "Mix 1");
        Playlist p2 = testPlaylist("pl2", "Mix 2");
        when(playlistService.getPlaylists("user1")).thenReturn(List.of(p1, p2));

        List<PlaylistDTO> result = controller.getPlaylists(testUser);

        assertEquals(2, result.size());
    }
}
