package com.stellarideas.grooves.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelEqualsHashCodeTest {

    // ---- User ----

    @Test
    void user_sameId_equals() {
        User a = new User();
        a.setId("u1");
        User b = new User();
        b.setId("u1");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void user_differentId_notEquals() {
        User a = new User();
        a.setId("u1");
        User b = new User();
        b.setId("u2");
        assertNotEquals(a, b);
    }

    @Test
    void user_nullIds_equals() {
        User a = new User();
        User b = new User();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void user_nullObject_notEquals() {
        User a = new User();
        a.setId("u1");
        assertNotEquals(a, null);
    }

    @Test
    void user_differentClass_notEquals() {
        User a = new User();
        a.setId("u1");
        assertNotEquals(a, "u1");
    }

    // ---- MusicFile ----

    @Test
    void musicFile_sameId_equals() {
        MusicFile a = new MusicFile();
        a.setId("m1");
        MusicFile b = new MusicFile();
        b.setId("m1");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void musicFile_differentId_notEquals() {
        MusicFile a = new MusicFile();
        a.setId("m1");
        MusicFile b = new MusicFile();
        b.setId("m2");
        assertNotEquals(a, b);
    }

    @Test
    void musicFile_nullIds_equals() {
        assertEquals(new MusicFile(), new MusicFile());
    }

    @Test
    void musicFile_nullObject_notEquals() {
        MusicFile a = new MusicFile();
        a.setId("m1");
        assertNotEquals(a, null);
    }

    @Test
    void musicFile_differentClass_notEquals() {
        MusicFile a = new MusicFile();
        a.setId("m1");
        assertNotEquals(a, "m1");
    }

    // ---- Playlist ----

    @Test
    void playlist_sameId_equals() {
        Playlist a = new Playlist();
        a.setId("p1");
        Playlist b = new Playlist();
        b.setId("p1");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void playlist_differentId_notEquals() {
        Playlist a = new Playlist();
        a.setId("p1");
        Playlist b = new Playlist();
        b.setId("p2");
        assertNotEquals(a, b);
    }

    @Test
    void playlist_nullIds_equals() {
        assertEquals(new Playlist(), new Playlist());
    }

    @Test
    void playlist_nullObject_notEquals() {
        Playlist a = new Playlist();
        a.setId("p1");
        assertNotEquals(a, null);
    }

    @Test
    void playlist_differentClass_notEquals() {
        Playlist a = new Playlist();
        a.setId("p1");
        assertNotEquals(a, "p1");
    }

    // ---- RefreshToken ----

    @Test
    void refreshToken_sameId_equals() {
        RefreshToken a = new RefreshToken();
        a.setId("rt1");
        RefreshToken b = new RefreshToken();
        b.setId("rt1");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void refreshToken_differentId_notEquals() {
        RefreshToken a = new RefreshToken();
        a.setId("rt1");
        RefreshToken b = new RefreshToken();
        b.setId("rt2");
        assertNotEquals(a, b);
    }

    @Test
    void refreshToken_nullIds_equals() {
        assertEquals(new RefreshToken(), new RefreshToken());
    }

    @Test
    void refreshToken_nullObject_notEquals() {
        RefreshToken a = new RefreshToken();
        a.setId("rt1");
        assertNotEquals(a, null);
    }

    @Test
    void refreshToken_differentClass_notEquals() {
        RefreshToken a = new RefreshToken();
        a.setId("rt1");
        assertNotEquals(a, "rt1");
    }

    // ---- BlacklistedToken ----

    @Test
    void blacklistedToken_sameId_equals() {
        BlacklistedToken a = new BlacklistedToken();
        a.setId("bt1");
        BlacklistedToken b = new BlacklistedToken();
        b.setId("bt1");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void blacklistedToken_differentId_notEquals() {
        BlacklistedToken a = new BlacklistedToken();
        a.setId("bt1");
        BlacklistedToken b = new BlacklistedToken();
        b.setId("bt2");
        assertNotEquals(a, b);
    }

    @Test
    void blacklistedToken_nullIds_equals() {
        assertEquals(new BlacklistedToken(), new BlacklistedToken());
    }

    @Test
    void blacklistedToken_nullObject_notEquals() {
        BlacklistedToken a = new BlacklistedToken();
        a.setId("bt1");
        assertNotEquals(a, null);
    }

    @Test
    void blacklistedToken_differentClass_notEquals() {
        BlacklistedToken a = new BlacklistedToken();
        a.setId("bt1");
        assertNotEquals(a, "bt1");
    }

    // ---- PasswordResetToken ----

    @Test
    void passwordResetToken_sameId_equals() {
        PasswordResetToken a = new PasswordResetToken();
        a.setId("prt1");
        PasswordResetToken b = new PasswordResetToken();
        b.setId("prt1");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void passwordResetToken_differentId_notEquals() {
        PasswordResetToken a = new PasswordResetToken();
        a.setId("prt1");
        PasswordResetToken b = new PasswordResetToken();
        b.setId("prt2");
        assertNotEquals(a, b);
    }

    @Test
    void passwordResetToken_nullIds_equals() {
        assertEquals(new PasswordResetToken(), new PasswordResetToken());
    }

    @Test
    void passwordResetToken_nullObject_notEquals() {
        PasswordResetToken a = new PasswordResetToken();
        a.setId("prt1");
        assertNotEquals(a, null);
    }

    @Test
    void passwordResetToken_differentClass_notEquals() {
        PasswordResetToken a = new PasswordResetToken();
        a.setId("prt1");
        assertNotEquals(a, "prt1");
    }

    // ---- CoverArt ----

    @Test
    void coverArt_sameId_equals() {
        CoverArt a = new CoverArt();
        a.setId("ca1");
        CoverArt b = new CoverArt();
        b.setId("ca1");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void coverArt_differentId_notEquals() {
        CoverArt a = new CoverArt();
        a.setId("ca1");
        CoverArt b = new CoverArt();
        b.setId("ca2");
        assertNotEquals(a, b);
    }

    @Test
    void coverArt_nullIds_equals() {
        assertEquals(new CoverArt(), new CoverArt());
    }

    @Test
    void coverArt_nullObject_notEquals() {
        CoverArt a = new CoverArt();
        a.setId("ca1");
        assertNotEquals(a, null);
    }

    @Test
    void coverArt_differentClass_notEquals() {
        CoverArt a = new CoverArt();
        a.setId("ca1");
        assertNotEquals(a, "ca1");
    }

    // ---- PlaybackQueue ----

    @Test
    void playbackQueue_sameId_equals() {
        PlaybackQueue a = new PlaybackQueue();
        a.setId("pq1");
        PlaybackQueue b = new PlaybackQueue();
        b.setId("pq1");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void playbackQueue_differentId_notEquals() {
        PlaybackQueue a = new PlaybackQueue();
        a.setId("pq1");
        PlaybackQueue b = new PlaybackQueue();
        b.setId("pq2");
        assertNotEquals(a, b);
    }

    @Test
    void playbackQueue_nullIds_equals() {
        assertEquals(new PlaybackQueue(), new PlaybackQueue());
    }

    @Test
    void playbackQueue_nullObject_notEquals() {
        PlaybackQueue a = new PlaybackQueue();
        a.setId("pq1");
        assertNotEquals(a, null);
    }

    @Test
    void playbackQueue_differentClass_notEquals() {
        PlaybackQueue a = new PlaybackQueue();
        a.setId("pq1");
        assertNotEquals(a, "pq1");
    }

    // ---- GenreCorrection ----

    @Test
    void genreCorrection_sameId_equals() {
        GenreCorrection a = new GenreCorrection();
        a.setId("gc1");
        GenreCorrection b = new GenreCorrection();
        b.setId("gc1");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void genreCorrection_differentId_notEquals() {
        GenreCorrection a = new GenreCorrection();
        a.setId("gc1");
        GenreCorrection b = new GenreCorrection();
        b.setId("gc2");
        assertNotEquals(a, b);
    }

    @Test
    void genreCorrection_nullIds_equals() {
        assertEquals(new GenreCorrection(), new GenreCorrection());
    }

    @Test
    void genreCorrection_nullObject_notEquals() {
        GenreCorrection a = new GenreCorrection();
        a.setId("gc1");
        assertNotEquals(a, null);
    }

    @Test
    void genreCorrection_differentClass_notEquals() {
        GenreCorrection a = new GenreCorrection();
        a.setId("gc1");
        assertNotEquals(a, "gc1");
    }

    // ---- GenreCorrection constructor and setArtist logic ----

    @Test
    void genreCorrection_constructor_setsArtistLower() {
        GenreCorrection gc = new GenreCorrection("Metallica", Genre.HEAVY_METAL, "user1");
        assertEquals("Metallica", gc.getArtist());
        assertEquals("metallica", gc.getArtistLower());
        assertEquals(Genre.HEAVY_METAL, gc.getGenre());
        assertEquals("user1", gc.getCorrectedByUserId());
        assertNotNull(gc.getCorrectedAt());
    }

    @Test
    void genreCorrection_constructor_nullArtist_setsEmptyLower() {
        GenreCorrection gc = new GenreCorrection(null, Genre.OTHER, "user1");
        assertNull(gc.getArtist());
        assertEquals("", gc.getArtistLower());
    }

    @Test
    void genreCorrection_setArtist_updatesArtistLower() {
        GenreCorrection gc = new GenreCorrection();
        gc.setArtist("Iron Maiden");
        assertEquals("Iron Maiden", gc.getArtist());
        assertEquals("iron maiden", gc.getArtistLower());
    }

    @Test
    void genreCorrection_setArtist_null_setsEmptyLower() {
        GenreCorrection gc = new GenreCorrection();
        gc.setArtist(null);
        assertNull(gc.getArtist());
        assertEquals("", gc.getArtistLower());
    }

    @Test
    void genreCorrection_settersAndGetters() {
        GenreCorrection gc = new GenreCorrection();
        gc.setId("gc1");
        gc.setGenre(Genre.THRASH_METAL);
        gc.setCorrectedByUserId("user42");
        Instant now = Instant.now();
        gc.setCorrectedAt(now);

        assertEquals("gc1", gc.getId());
        assertEquals(Genre.THRASH_METAL, gc.getGenre());
        assertEquals("user42", gc.getCorrectedByUserId());
        assertEquals(now, gc.getCorrectedAt());
    }

    // ---- PlaybackQueue getters/setters ----

    @Test
    void playbackQueue_gettersAndSetters() {
        PlaybackQueue pq = new PlaybackQueue();
        pq.setId("pq1");
        pq.setUserId("user1");
        pq.setTrackIds(List.of("t1", "t2", "t3"));
        pq.setCurrentTrackId("t2");
        pq.setShuffle(true);
        Instant now = Instant.now();
        pq.setUpdatedAt(now);

        assertEquals("pq1", pq.getId());
        assertEquals("user1", pq.getUserId());
        assertEquals(List.of("t1", "t2", "t3"), pq.getTrackIds());
        assertEquals("t2", pq.getCurrentTrackId());
        assertTrue(pq.isShuffle());
        assertEquals(now, pq.getUpdatedAt());
    }

    @Test
    void playbackQueue_defaultValues() {
        PlaybackQueue pq = new PlaybackQueue();
        assertNotNull(pq.getTrackIds());
        assertTrue(pq.getTrackIds().isEmpty());
        assertFalse(pq.isShuffle());
        assertNull(pq.getCurrentTrackId());
        assertNull(pq.getUserId());
        assertNull(pq.getId());
        assertNull(pq.getUpdatedAt());
    }

    // ---- BlacklistedToken constructor ----

    @Test
    void blacklistedToken_constructor_setsBlacklistedAt() {
        Instant before = Instant.now();
        BlacklistedToken bt = new BlacklistedToken("jti-123", Instant.now().plusSeconds(3600), "testuser");
        Instant after = Instant.now();

        assertEquals("jti-123", bt.getJti());
        assertEquals("testuser", bt.getUsername());
        assertNotNull(bt.getBlacklistedAt());
        assertNotNull(bt.getExpiresAt());
        // blacklistedAt should be between before and after
        assertFalse(bt.getBlacklistedAt().isBefore(before));
        assertFalse(bt.getBlacklistedAt().isAfter(after));
    }
}
