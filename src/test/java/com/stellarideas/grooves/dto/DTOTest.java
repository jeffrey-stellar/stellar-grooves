package com.stellarideas.grooves.dto;

import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.PlaybackQueue;
import com.stellarideas.grooves.model.Playlist;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DTOTest {

    // ---- PlaybackQueueDTO ----

    @Test
    void playbackQueueDTO_from_mapsAllFields() {
        PlaybackQueue queue = new PlaybackQueue();
        queue.setTrackIds(List.of("t1", "t2"));
        queue.setCurrentTrackId("t1");
        queue.setShuffle(true);

        PlaybackQueueDTO dto = PlaybackQueueDTO.from(queue);

        assertEquals(List.of("t1", "t2"), dto.getTrackIds());
        assertEquals("t1", dto.getCurrentTrackId());
        assertTrue(dto.isShuffle());
    }

    @Test
    void playbackQueueDTO_from_defaultValues() {
        PlaybackQueue queue = new PlaybackQueue();

        PlaybackQueueDTO dto = PlaybackQueueDTO.from(queue);

        assertNotNull(dto.getTrackIds());
        assertTrue(dto.getTrackIds().isEmpty());
        assertNull(dto.getCurrentTrackId());
        assertFalse(dto.isShuffle());
    }

    @Test
    void playbackQueueDTO_gettersAndSetters() {
        PlaybackQueueDTO dto = new PlaybackQueueDTO();
        dto.setTrackIds(List.of("a", "b"));
        dto.setCurrentTrackId("a");
        dto.setShuffle(true);

        assertEquals(List.of("a", "b"), dto.getTrackIds());
        assertEquals("a", dto.getCurrentTrackId());
        assertTrue(dto.isShuffle());
    }

    // ---- PlaylistDTO ----

    @Test
    void playlistDTO_from_mapsAllFields() {
        Playlist playlist = new Playlist();
        playlist.setId("pl1");
        playlist.setName("My Playlist");
        playlist.setTrackIds(new ArrayList<>(List.of("t1", "t2", "t3")));
        playlist.setShareToken("share-abc");

        PlaylistDTO dto = PlaylistDTO.from(playlist);

        assertEquals("pl1", dto.getId());
        assertEquals("My Playlist", dto.getName());
        assertEquals(3, dto.getTrackCount());
        assertEquals("share-abc", dto.getShareToken());
    }

    @Test
    void playlistDTO_from_emptyTrackIds() {
        Playlist playlist = new Playlist();
        playlist.setId("pl2");
        playlist.setName("Empty");

        PlaylistDTO dto = PlaylistDTO.from(playlist);

        assertEquals(0, dto.getTrackCount());
        assertNull(dto.getShareToken());
    }

    @Test
    void playlistDTO_gettersAndSetters() {
        PlaylistDTO dto = new PlaylistDTO();
        dto.setId("id1");
        dto.setName("Test");
        dto.setTrackCount(5);
        dto.setShareToken("token");

        assertEquals("id1", dto.getId());
        assertEquals("Test", dto.getName());
        assertEquals(5, dto.getTrackCount());
        assertEquals("token", dto.getShareToken());
    }

    // ---- MusicFileDTO ----

    @Test
    void musicFileDTO_from_withGenreAndAdditionalGenres() {
        MusicFile file = MusicFile.builder()
                .id("mf1")
                .fileName("song.mp3")
                .artist("Metallica")
                .album("Master of Puppets")
                .title("Battery")
                .year("1986")
                .genre(Genre.THRASH_METAL)
                .additionalGenres(List.of(Genre.HEAVY_METAL, Genre.HARD_ROCK))
                .rating(5)
                .hasCoverArt(true)
                .build();

        MusicFileDTO dto = MusicFileDTO.from(file);

        assertEquals("mf1", dto.getId());
        assertEquals("song.mp3", dto.getFileName());
        assertEquals("Metallica", dto.getArtist());
        assertEquals("Master of Puppets", dto.getAlbum());
        assertEquals("Battery", dto.getTitle());
        assertEquals("1986", dto.getYear());
        assertEquals("THRASH_METAL", dto.getGenre());
        assertEquals(List.of("HEAVY_METAL", "HARD_ROCK"), dto.getAdditionalGenres());
        assertEquals(5, dto.getRating());
        assertTrue(dto.isHasCoverArt());
    }

    @Test
    void musicFileDTO_from_nullGenre() {
        MusicFile file = MusicFile.builder()
                .id("mf2")
                .title("Unknown")
                .build();

        MusicFileDTO dto = MusicFileDTO.from(file);

        assertEquals("mf2", dto.getId());
        assertNull(dto.getGenre());
        assertNull(dto.getAdditionalGenres());
    }

    @Test
    void musicFileDTO_from_nullAdditionalGenres() {
        MusicFile file = MusicFile.builder()
                .id("mf3")
                .genre(Genre.CLASSIC_ROCK)
                .build();

        MusicFileDTO dto = MusicFileDTO.from(file);

        assertEquals("CLASSIC_ROCK", dto.getGenre());
        assertNull(dto.getAdditionalGenres());
    }

    @Test
    void musicFileDTO_gettersAndSetters() {
        MusicFileDTO dto = new MusicFileDTO();
        dto.setId("id");
        dto.setFileName("file.mp3");
        dto.setArtist("Artist");
        dto.setAlbum("Album");
        dto.setTitle("Title");
        dto.setYear("2000");
        dto.setGenre("ROCK");
        dto.setAdditionalGenres(List.of("METAL"));
        dto.setRating(3);
        dto.setHasCoverArt(false);

        assertEquals("id", dto.getId());
        assertEquals("file.mp3", dto.getFileName());
        assertEquals("Artist", dto.getArtist());
        assertEquals("Album", dto.getAlbum());
        assertEquals("Title", dto.getTitle());
        assertEquals("2000", dto.getYear());
        assertEquals("ROCK", dto.getGenre());
        assertEquals(List.of("METAL"), dto.getAdditionalGenres());
        assertEquals(3, dto.getRating());
        assertFalse(dto.isHasCoverArt());
    }

    // ---- UpdateRatingRequest ----

    @Test
    void updateRatingRequest_getterSetter() {
        UpdateRatingRequest req = new UpdateRatingRequest();
        req.setRating(4);
        assertEquals(4, req.getRating());
    }

    // ---- UpdateGenreRequest ----

    @Test
    void updateGenreRequest_getterSetter() {
        UpdateGenreRequest req = new UpdateGenreRequest();
        req.setGenre("HEAVY_METAL");
        assertEquals("HEAVY_METAL", req.getGenre());
    }

    // ---- BulkDeleteRequest ----

    @Test
    void bulkDeleteRequest_getterSetter() {
        BulkDeleteRequest req = new BulkDeleteRequest();
        List<String> ids = List.of("f1", "f2", "f3");
        req.setFileIds(ids);
        assertEquals(ids, req.getFileIds());
    }

    // ---- ScanScheduleRequest ----

    @Test
    void scanScheduleRequest_getterSetter() {
        ScanScheduleRequest req = new ScanScheduleRequest();
        req.setCronExpression("0 0 * * *");
        req.setPath("/music");
        assertEquals("0 0 * * *", req.getCronExpression());
        assertEquals("/music", req.getPath());
    }
}
