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
import java.util.Map;
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
    void generateShareTokenWithoutExpiration() {
        Playlist playlist = new Playlist();

        String token = service.generateShareToken(playlist, null);

        assertNotNull(token);
        assertFalse(token.isBlank());
        assertEquals(token, playlist.getShareToken());
        assertNull(playlist.getShareTokenExpiresAt());
        verify(playlistRepository).save(playlist);
    }

    @Test
    void generateShareTokenWithExpiration() {
        Playlist playlist = new Playlist();

        String token = service.generateShareToken(playlist, 7);

        assertNotNull(token);
        assertEquals(token, playlist.getShareToken());
        assertNotNull(playlist.getShareTokenExpiresAt());
        assertTrue(playlist.getShareTokenExpiresAt().isAfter(java.time.Instant.now()));
        assertTrue(playlist.getShareTokenExpiresAt().isBefore(
                java.time.Instant.now().plus(java.time.Duration.ofDays(8))));
        verify(playlistRepository).save(playlist);
    }

    @Test
    void revokeShareTokenClearsExpiration() {
        Playlist playlist = new Playlist();
        playlist.setShareToken("some-token");
        playlist.setShareTokenExpiresAt(java.time.Instant.now().plus(java.time.Duration.ofDays(7)));

        service.revokeShareToken(playlist);

        assertNull(playlist.getShareToken());
        assertNull(playlist.getShareTokenExpiresAt());
        verify(playlistRepository).save(playlist);
    }

    @Test
    void getPlaylistTracksPreservesOrder() {
        MusicFile f1 = MusicFile.builder().id("f1").title("Song 1").artist("A").genre(Genre.OTHER).build();
        MusicFile f2 = MusicFile.builder().id("f2").title("Song 2").artist("B").genre(Genre.OTHER).build();
        when(musicFileRepository.findByIdInAndUserId(anyList(), eq("user1"))).thenReturn(List.of(f2, f1));

        Playlist playlist = new Playlist();
        playlist.setTrackIds(new ArrayList<>(List.of("f1", "f2")));

        Map<String, Object> result = service.getPlaylistTracks(playlist, "user1");
        @SuppressWarnings("unchecked")
        List<MusicFileDTO> tracks = (List<MusicFileDTO>) result.get("tracks");

        assertEquals(2, tracks.size());
        assertEquals("Song 1", tracks.get(0).getTitle());
        assertEquals("Song 2", tracks.get(1).getTitle());
        @SuppressWarnings("unchecked")
        List<String> missing = (List<String>) result.get("missingTracks");
        assertTrue(missing.isEmpty());
    }

    @Test
    void getPlaylistTracksEmptyList() {
        Playlist playlist = new Playlist();
        playlist.setTrackIds(new ArrayList<>());

        Map<String, Object> result = service.getPlaylistTracks(playlist, "user1");
        @SuppressWarnings("unchecked")
        List<MusicFileDTO> tracks = (List<MusicFileDTO>) result.get("tracks");

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

    @Test
    void paginatedGetPlaylistTracksReturnsOnlyPageSlice() {
        Playlist playlist = new Playlist();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 250; i++) ids.add("t" + i);
        playlist.setTrackIds(ids);

        // Only the slice is hydrated — simulate the repo returning exactly those
        when(musicFileRepository.findByIdInAndUserId(anyList(), eq("user1"))).thenAnswer(inv -> {
            List<String> slice = inv.getArgument(0);
            return slice.stream().map(id -> MusicFile.builder().id(id).build()).toList();
        });

        Map<String, Object> result = service.getPlaylistTracks(playlist, "user1", 1, 50);

        assertEquals(250, result.get("totalTracks"));
        assertEquals(1, result.get("page"));
        assertEquals(50, result.get("size"));
        @SuppressWarnings("unchecked")
        List<MusicFileDTO> tracks = (List<MusicFileDTO>) result.get("tracks");
        assertEquals(50, tracks.size());
        assertEquals("t50", tracks.get(0).getId());
        assertEquals("t99", tracks.get(49).getId());
    }

    @Test
    void paginatedGetPlaylistTracksPreservesOrderOnPartialMongoResult() {
        Playlist playlist = new Playlist();
        playlist.setTrackIds(new ArrayList<>(List.of("a", "b", "c", "d", "e")));
        // Mongo returns them in a different order (set-like) to prove we reorder
        when(musicFileRepository.findByIdInAndUserId(anyList(), eq("user1"))).thenReturn(List.of(
                MusicFile.builder().id("c").build(),
                MusicFile.builder().id("a").build(),
                MusicFile.builder().id("b").build()));

        Map<String, Object> result = service.getPlaylistTracks(playlist, "user1", 0, 3);

        @SuppressWarnings("unchecked")
        List<MusicFileDTO> tracks = (List<MusicFileDTO>) result.get("tracks");
        assertEquals(List.of("a", "b", "c"), tracks.stream().map(MusicFileDTO::getId).toList());
    }

    @Test
    void paginatedReportsMissingInSlice() {
        Playlist playlist = new Playlist();
        playlist.setTrackIds(new ArrayList<>(List.of("a", "b", "c", "d")));
        // Mongo only returns 'a' and 'c' — 'b' and 'd' were purged
        when(musicFileRepository.findByIdInAndUserId(anyList(), eq("user1"))).thenReturn(List.of(
                MusicFile.builder().id("a").build(),
                MusicFile.builder().id("c").build()));

        Map<String, Object> result = service.getPlaylistTracks(playlist, "user1", 0, 4);

        @SuppressWarnings("unchecked")
        List<String> missing = (List<String>) result.get("missingTracks");
        assertEquals(List.of("b", "d"), missing);
    }

    @Test
    void paginatedHandlesEmptyPlaylist() {
        Playlist playlist = new Playlist();
        playlist.setTrackIds(new ArrayList<>());

        Map<String, Object> result = service.getPlaylistTracks(playlist, "user1", 0, 50);

        assertEquals(0, result.get("totalTracks"));
        assertEquals(List.of(), result.get("tracks"));
        verify(musicFileRepository, never()).findByIdInAndUserId(anyList(), anyString());
    }

    @Test
    void paginatedClampsNegativePageAndSize() {
        Playlist playlist = new Playlist();
        playlist.setTrackIds(new ArrayList<>(List.of("a")));
        when(musicFileRepository.findByIdInAndUserId(anyList(), eq("user1"))).thenReturn(List.of(
                MusicFile.builder().id("a").build()));

        Map<String, Object> r1 = service.getPlaylistTracks(playlist, "user1", -1, 50);
        assertEquals(0, r1.get("page"));

        Map<String, Object> r2 = service.getPlaylistTracks(playlist, "user1", 0, 0);
        assertEquals(50, r2.get("size"));

        Map<String, Object> r3 = service.getPlaylistTracks(playlist, "user1", 0, 99999);
        assertEquals(500, r3.get("size"));
    }

    @Test
    void paginatedPageBeyondTotalReturnsEmpty() {
        Playlist playlist = new Playlist();
        playlist.setTrackIds(new ArrayList<>(List.of("a", "b")));

        Map<String, Object> result = service.getPlaylistTracks(playlist, "user1", 5, 50);

        assertEquals(2, result.get("totalTracks"));
        assertEquals(List.of(), result.get("tracks"));
        verify(musicFileRepository, never()).findByIdInAndUserId(anyList(), anyString());
    }
}
