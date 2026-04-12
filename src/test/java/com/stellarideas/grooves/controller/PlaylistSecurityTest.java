package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.dto.AddTrackRequest;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that verify user A cannot access user B's playlists or music files.
 */
class PlaylistSecurityTest {

    private PlaylistController controller;
    private PlaylistRepository playlistRepository;
    private MusicFileRepository musicFileRepository;
    private UserRepository userRepository;
    private User userA;
    private User userB;

    @BeforeEach
    void setUp() {
        playlistRepository = mock(PlaylistRepository.class);
        musicFileRepository = mock(MusicFileRepository.class);
        userRepository = mock(UserRepository.class);

        controller = new PlaylistController(userRepository, playlistRepository, musicFileRepository);

        userA = User.builder().username("alice").email("alice@test.com").password("enc").build();
        userA.setId("userA");
        userA.setRoles(Set.of(Role.ROLE_USER));

        userB = User.builder().username("bob").email("bob@test.com").password("enc").build();
        userB.setId("userB");
        userB.setRoles(Set.of(Role.ROLE_USER));

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(userA));
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(userB));

        // Authenticate as user A
        setAuthenticatedUser("alice");
    }

    private void setAuthenticatedUser(String username) {
        var userDetails = org.springframework.security.core.userdetails.User
                .withUsername(username)
                .password("enc")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();
        var auth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void userCannotAccessOtherUsersPlaylist() {
        // User B's playlist exists but findByIdAndUser scoped to user A returns empty
        Playlist bobsPlaylist = new Playlist();
        bobsPlaylist.setId("pl-bob");
        bobsPlaylist.setName("Bob's Jams");
        bobsPlaylist.setUser(userB);

        when(playlistRepository.findByIdAndUser("pl-bob", userA)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getPlaylistTracks("pl-bob");
        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void userCannotDeleteOtherUsersPlaylist() {
        when(playlistRepository.findByIdAndUser("pl-bob", userA)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.deletePlaylist("pl-bob");
        assertEquals(404, response.getStatusCode().value());
        verify(playlistRepository, never()).delete(any());
    }

    private AddTrackRequest addTrackRequest(String fileId) {
        AddTrackRequest req = new AddTrackRequest();
        req.setFileId(fileId);
        return req;
    }

    @Test
    void userCannotAddTrackToOtherUsersPlaylist() {
        when(playlistRepository.findByIdAndUser("pl-bob", userA)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.addTrack("pl-bob", addTrackRequest("f1"));
        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void userCannotAddOtherUsersFileToOwnPlaylist() {
        Playlist alicesPlaylist = new Playlist();
        alicesPlaylist.setId("pl-alice");
        alicesPlaylist.setName("Alice's Playlist");
        alicesPlaylist.setUser(userA);
        alicesPlaylist.setTrackIds(new ArrayList<>());

        when(playlistRepository.findByIdAndUser("pl-alice", userA)).thenReturn(Optional.of(alicesPlaylist));
        // Bob's file — not found when queried scoped to user A
        when(musicFileRepository.findByIdAndUser("f-bob", userA)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.addTrack("pl-alice", addTrackRequest("f-bob"));
        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void userOnlySeesOwnPlaylists() {
        Playlist alicePlaylist = new Playlist();
        alicePlaylist.setId("pl-alice");
        alicePlaylist.setName("Alice's Playlist");
        alicePlaylist.setUser(userA);
        alicePlaylist.setTrackIds(new ArrayList<>(List.of("f1")));

        when(playlistRepository.findByUser(userA)).thenReturn(List.of(alicePlaylist));

        List<PlaylistDTO> result = controller.getPlaylists();
        assertEquals(1, result.size());
        assertEquals("pl-alice", result.get(0).getId());
    }
}
