package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.Playlist;
import com.stellarideas.grooves.service.PlaylistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SharedPlaylistControllerTest {

    private SharedPlaylistController controller;
    private PlaylistService playlistService;

    @BeforeEach
    void setUp() {
        playlistService = mock(PlaylistService.class);
        controller = new SharedPlaylistController(playlistService);
    }

    @Test
    void getSharedPlaylistReturnsTracksForValidToken() {
        Playlist playlist = new Playlist();
        playlist.setId("p1");
        playlist.setName("My Playlist");
        playlist.setUserId("user1");
        playlist.setShareToken("valid-token");
        playlist.setTrackIds(List.of("t1", "t2"));

        MusicFile track1 = MusicFile.builder()
                .id("t1").title("Song One").artist("Artist A")
                .album("Album X").year("2020").genre(Genre.CLASSIC_ROCK).build();
        MusicFile track2 = MusicFile.builder()
                .id("t2").title("Song Two").artist("Artist B")
                .album("Album Y").year("2021").genre(Genre.HEAVY_METAL).build();

        when(playlistService.findByShareToken("valid-token")).thenReturn(Optional.of(playlist));
        when(playlistService.getOrderedFiles(playlist, "user1")).thenReturn(List.of(track1, track2));

        ResponseEntity<?> response = controller.getSharedPlaylist("valid-token");

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("My Playlist", body.get("name"));
        assertEquals(2, body.get("trackCount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tracks = (List<Map<String, Object>>) body.get("tracks");
        assertEquals("Song One", tracks.get(0).get("title"));
        assertEquals("Artist B", tracks.get(1).get("artist"));
    }

    @Test
    void getSharedPlaylistReturns404ForInvalidToken() {
        when(playlistService.findByShareToken("bad-token")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getSharedPlaylist("bad-token");

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void getSharedPlaylistHandlesEmptyPlaylist() {
        Playlist playlist = new Playlist();
        playlist.setId("p2");
        playlist.setName("Empty Playlist");
        playlist.setUserId("user1");
        playlist.setShareToken("empty-token");

        when(playlistService.findByShareToken("empty-token")).thenReturn(Optional.of(playlist));
        when(playlistService.getOrderedFiles(playlist, "user1")).thenReturn(List.of());

        ResponseEntity<?> response = controller.getSharedPlaylist("empty-token");

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(0, body.get("trackCount"));
    }

    @Test
    void getSharedPlaylistHandlesTrackWithNullGenre() {
        Playlist playlist = new Playlist();
        playlist.setId("p3");
        playlist.setName("Mixed");
        playlist.setUserId("user1");
        playlist.setShareToken("null-genre-token");

        MusicFile track = MusicFile.builder()
                .id("t1").title("Unknown Genre").artist("Artist")
                .album("Album").year("2023").genre(null).build();

        when(playlistService.findByShareToken("null-genre-token")).thenReturn(Optional.of(playlist));
        when(playlistService.getOrderedFiles(playlist, "user1")).thenReturn(List.of(track));

        ResponseEntity<?> response = controller.getSharedPlaylist("null-genre-token");

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tracks = (List<Map<String, Object>>) body.get("tracks");
        assertNull(tracks.get(0).get("genre"));
    }
}
