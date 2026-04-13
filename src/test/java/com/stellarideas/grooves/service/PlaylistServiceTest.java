package com.stellarideas.grooves.service;

import com.stellarideas.grooves.dto.MusicFileDTO;
import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.Playlist;
import com.stellarideas.grooves.repository.MusicFileRepository;
import com.stellarideas.grooves.repository.PlaylistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlaylistServiceTest {

    private PlaylistService service;
    private PlaylistRepository playlistRepository;
    private MusicFileRepository musicFileRepository;

    @BeforeEach
    void setUp() {
        playlistRepository = mock(PlaylistRepository.class);
        musicFileRepository = mock(MusicFileRepository.class);
        service = new PlaylistService(playlistRepository, musicFileRepository);
    }

    @Test
    void createPlaylistTrimName() {
        when(playlistRepository.save(any(Playlist.class))).thenAnswer(inv -> inv.getArgument(0));

        Playlist result = service.createPlaylist("  Rock Mix  ", "user1");

        assertEquals("Rock Mix", result.getName());
        assertEquals("user1", result.getUserId());
    }

    @Test
    void addTrackSucceeds() {
        MusicFile file = MusicFile.builder().id("f1").build();
        when(musicFileRepository.findByIdAndUserIdAndDeletedFalse("f1", "user1")).thenReturn(Optional.of(file));

        Playlist playlist = new Playlist();
        playlist.setTrackIds(new ArrayList<>());

        boolean added = service.addTrack(playlist, "f1", "user1");

        assertTrue(added);
        assertTrue(playlist.getTrackIds().contains("f1"));
        verify(playlistRepository).save(playlist);
    }

    @Test
    void addTrackReturnsFalseForMissingFile() {
        when(musicFileRepository.findByIdAndUserIdAndDeletedFalse("missing", "user1")).thenReturn(Optional.empty());

        Playlist playlist = new Playlist();
        playlist.setTrackIds(new ArrayList<>());

        boolean added = service.addTrack(playlist, "missing", "user1");

        assertFalse(added);
        verify(playlistRepository, never()).save(any());
    }

    @Test
    void addTrackNoDuplicates() {
        MusicFile file = MusicFile.builder().id("f1").build();
        when(musicFileRepository.findByIdAndUserIdAndDeletedFalse("f1", "user1")).thenReturn(Optional.of(file));

        Playlist playlist = new Playlist();
        playlist.setTrackIds(new ArrayList<>(List.of("f1")));

        boolean added = service.addTrack(playlist, "f1", "user1");

        assertTrue(added);
        assertEquals(1, playlist.getTrackIds().size());
        verify(playlistRepository, never()).save(any());
    }

    @Test
    void removeTrack() {
        Playlist playlist = new Playlist();
        playlist.setTrackIds(new ArrayList<>(List.of("f1", "f2")));

        service.removeTrack(playlist, "f1");

        assertEquals(List.of("f2"), playlist.getTrackIds());
        verify(playlistRepository).save(playlist);
    }

    @Test
    void reorderTracksSucceeds() {
        Playlist playlist = new Playlist();
        playlist.setTrackIds(new ArrayList<>(List.of("f1", "f2", "f3")));

        boolean success = service.reorderTracks(playlist, List.of("f3", "f1", "f2"));

        assertTrue(success);
        assertEquals(List.of("f3", "f1", "f2"), playlist.getTrackIds());
    }

    @Test
    void reorderTracksRejectsMismatch() {
        Playlist playlist = new Playlist();
        playlist.setTrackIds(new ArrayList<>(List.of("f1", "f2")));

        boolean success = service.reorderTracks(playlist, List.of("f1", "f3"));

        assertFalse(success);
    }

    @Test
    void generateShareToken() {
        Playlist playlist = new Playlist();

        String token = service.generateShareToken(playlist);

        assertNotNull(token);
        assertFalse(token.isBlank());
        assertEquals(token, playlist.getShareToken());
        verify(playlistRepository).save(playlist);
    }

    @Test
    void revokeShareToken() {
        Playlist playlist = new Playlist();
        playlist.setShareToken("some-token");

        service.revokeShareToken(playlist);

        assertNull(playlist.getShareToken());
        verify(playlistRepository).save(playlist);
    }

    @Test
    void getPlaylistTracksPreservesOrder() {
        MusicFile f1 = MusicFile.builder().id("f1").title("Song 1").artist("A").genre(Genre.OTHER).build();
        MusicFile f2 = MusicFile.builder().id("f2").title("Song 2").artist("B").genre(Genre.OTHER).build();
        when(musicFileRepository.findByIdInAndUserId(anyList(), eq("user1"))).thenReturn(List.of(f2, f1));

        Playlist playlist = new Playlist();
        playlist.setTrackIds(new ArrayList<>(List.of("f1", "f2")));

        List<MusicFileDTO> tracks = service.getPlaylistTracks(playlist, "user1");

        assertEquals(2, tracks.size());
        assertEquals("Song 1", tracks.get(0).getTitle());
        assertEquals("Song 2", tracks.get(1).getTitle());
    }

    @Test
    void getPlaylistTracksEmptyList() {
        Playlist playlist = new Playlist();
        playlist.setTrackIds(new ArrayList<>());

        List<MusicFileDTO> tracks = service.getPlaylistTracks(playlist, "user1");

        assertTrue(tracks.isEmpty());
    }

    @Test
    void deletePlaylist() {
        Playlist playlist = new Playlist();
        playlist.setId("p1");

        service.deletePlaylist(playlist);

        verify(playlistRepository).delete(playlist);
    }

    @Test
    void findByShareToken() {
        Playlist playlist = new Playlist();
        when(playlistRepository.findByShareToken("token123")).thenReturn(Optional.of(playlist));

        Optional<Playlist> result = service.findByShareToken("token123");

        assertTrue(result.isPresent());
    }
}
