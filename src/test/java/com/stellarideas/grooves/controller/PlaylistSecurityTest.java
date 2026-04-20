package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.dto.AddTrackRequest;
import com.stellarideas.grooves.dto.PlaylistDTO;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaylistSecurityTest {

    private PlaylistController controller;
    private PlaylistService playlistService;
    private User userA;
    private User userB;

    @BeforeEach
    void setUp() {
        playlistService = mock(PlaylistService.class);

        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        MessageHelper msgHelper = new MessageHelper(messageSource);

        AuditService auditService = mock(AuditService.class);
        controller = new PlaylistController(playlistService, msgHelper, auditService);

        userA = User.builder().username("alice").email("alice@test.com").password("enc").build();
        userA.setId("userA");
        userA.setRoles(Set.of(Role.ROLE_USER));

        userB = User.builder().username("bob").email("bob@test.com").password("enc").build();
        userB.setId("userB");
        userB.setRoles(Set.of(Role.ROLE_USER));
    }

    @Test
    void userCannotAccessOtherUsersPlaylist() {
        when(playlistService.findByIdAndUserId("pl-bob", "userA")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getPlaylistTracks(userA, "pl-bob", null, null);
        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void userCannotDeleteOtherUsersPlaylist() {
        when(playlistService.findByIdAndUserId("pl-bob", "userA")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.deletePlaylist(userA, "pl-bob");
        assertEquals(404, response.getStatusCode().value());
        verify(playlistService, never()).deletePlaylist(any());
    }

    private AddTrackRequest addTrackRequest(String fileId) {
        AddTrackRequest req = new AddTrackRequest();
        req.setFileId(fileId);
        return req;
    }

    @Test
    void userCannotAddTrackToOtherUsersPlaylist() {
        when(playlistService.findByIdAndUserId("pl-bob", "userA")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.addTrack(userA, "pl-bob", addTrackRequest("f1"));
        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void userCannotAddOtherUsersFileToOwnPlaylist() {
        Playlist alicesPlaylist = new Playlist();
        alicesPlaylist.setId("pl-alice");
        alicesPlaylist.setName("Alice's Playlist");
        alicesPlaylist.setUserId("userA");
        alicesPlaylist.setTrackIds(new ArrayList<>());

        when(playlistService.findByIdAndUserId("pl-alice", "userA")).thenReturn(Optional.of(alicesPlaylist));
        when(playlistService.addTrack(alicesPlaylist, "f-bob", "userA")).thenReturn(false);

        ResponseEntity<?> response = controller.addTrack(userA, "pl-alice", addTrackRequest("f-bob"));
        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void userOnlySeesOwnPlaylists() {
        Playlist alicePlaylist = new Playlist();
        alicePlaylist.setId("pl-alice");
        alicePlaylist.setName("Alice's Playlist");
        alicePlaylist.setUserId("userA");
        alicePlaylist.setTrackIds(new ArrayList<>(List.of("f1")));

        when(playlistService.getPlaylists("userA")).thenReturn(List.of(alicePlaylist));

        List<PlaylistDTO> result = controller.getPlaylists(userA);
        assertEquals(1, result.size());
        assertEquals("pl-alice", result.get(0).getId());
    }
}
