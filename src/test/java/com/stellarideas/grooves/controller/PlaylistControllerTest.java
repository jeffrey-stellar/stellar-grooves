package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.dto.CreatePlaylistRequest;
import com.stellarideas.grooves.dto.MusicFileDTO;
import com.stellarideas.grooves.dto.PlaylistDTO;
import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.Playlist;
import com.stellarideas.grooves.model.Role;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.MusicFileRepository;
import com.stellarideas.grooves.repository.PlaylistRepository;
import com.stellarideas.grooves.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlaylistControllerTest {

    private PlaylistController controller;
    private PlaylistRepository playlistRepository;
    private MusicFileRepository musicFileRepository;
    private UserRepository userRepository;
    private User testUser;

    @BeforeEach
    void setUp() {
        playlistRepository = mock(PlaylistRepository.class);
        musicFileRepository = mock(MusicFileRepository.class);
        userRepository = mock(UserRepository.class);

        controller = new PlaylistController(userRepository, playlistRepository, musicFileRepository);

        testUser = User.builder().username("testuser").email("t@t.com").password("enc").build();
        testUser.setId("user1");
        testUser.setRoles(Set.of(Role.ROLE_USER));
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));

        // Set security context
        var userDetails = org.springframework.security.core.userdetails.User
                .withUsername("testuser")
                .password("enc")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();
        var auth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
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
        saved.setUser(testUser);
        when(playlistRepository.save(any())).thenReturn(saved);

        ResponseEntity<?> response = controller.createPlaylist(playlistRequest("My Playlist"));

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
        saved.setUser(testUser);
        when(playlistRepository.save(any())).thenReturn(saved);

        ResponseEntity<?> response = controller.createPlaylist(playlistRequest(name80));
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void getPlaylistTracksPreservesOrder() {
        Playlist playlist = new Playlist();
        playlist.setId("pl1");
        playlist.setName("Test");
        playlist.setUser(testUser);
        playlist.setTrackIds(new ArrayList<>(List.of("f1", "f2", "f3")));

        when(playlistRepository.findByIdAndUser("pl1", testUser)).thenReturn(Optional.of(playlist));

        MusicFile file1 = MusicFile.builder().artist("A1").title("S1").genre(Genre.HARD_ROCK).build();
        file1.setId("f1");
        MusicFile file2 = MusicFile.builder().artist("A2").title("S2").genre(Genre.THRASH_METAL).build();
        file2.setId("f2");
        MusicFile file3 = MusicFile.builder().artist("A3").title("S3").genre(Genre.CLASSIC_ROCK).build();
        file3.setId("f3");

        // DB returns in arbitrary order
        when(musicFileRepository.findByIdInAndUser(eq(List.of("f1", "f2", "f3")), eq(testUser)))
                .thenReturn(List.of(file3, file1, file2));

        ResponseEntity<?> response = controller.getPlaylistTracks("pl1");
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
        playlist.setUser(testUser);
        playlist.setTrackIds(new ArrayList<>(List.of("f1", "f_deleted", "f3")));

        when(playlistRepository.findByIdAndUser("pl1", testUser)).thenReturn(Optional.of(playlist));

        MusicFile file1 = MusicFile.builder().artist("A1").title("S1").genre(Genre.HARD_ROCK).build();
        file1.setId("f1");
        MusicFile file3 = MusicFile.builder().artist("A3").title("S3").genre(Genre.CLASSIC_ROCK).build();
        file3.setId("f3");

        when(musicFileRepository.findByIdInAndUser(any(), eq(testUser)))
                .thenReturn(List.of(file1, file3));

        ResponseEntity<?> response = controller.getPlaylistTracks("pl1");
        @SuppressWarnings("unchecked")
        List<MusicFileDTO> tracks = (List<MusicFileDTO>) response.getBody();
        assertEquals(2, tracks.size());
        assertEquals("f1", tracks.get(0).getId());
        assertEquals("f3", tracks.get(1).getId());
    }

    @Test
    void getPlaylistTracksReturns404ForNonexistent() {
        when(playlistRepository.findByIdAndUser("nope", testUser)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getPlaylistTracks("nope");
        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void getPlaylistTracksReturnsEmptyForEmptyPlaylist() {
        Playlist playlist = new Playlist();
        playlist.setId("pl1");
        playlist.setName("Empty");
        playlist.setUser(testUser);
        playlist.setTrackIds(new ArrayList<>());

        when(playlistRepository.findByIdAndUser("pl1", testUser)).thenReturn(Optional.of(playlist));

        ResponseEntity<?> response = controller.getPlaylistTracks("pl1");
        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<?> tracks = (List<?>) response.getBody();
        assertTrue(tracks.isEmpty());
    }
}
