package com.stellarideas.grooves.integration;

import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.repository.MusicFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the {@code $facet}-based getStatistics pipeline against
 * a real MongoDB. Confirms each branch (totalTracks, genres, top artists,
 * distinct counts, decade math, average rating) produces the expected shape
 * for a representative seed, including handling of null/blank/missing fields.
 */
class LibraryStatsIT extends BaseIntegrationTest {

    @Autowired
    private MusicFileRepository repository;

    private static final String USER = "stats-user";
    private static final String OTHER = "other-user";

    @BeforeEach
    void seed() {
        repository.deleteAll();
        repository.saveAll(List.of(
                track(USER, "Iron Maiden",   "Powerslave",       "Aces High",    Genre.HEAVY_METAL,  1984, 5),
                track(USER, "Iron Maiden",   "Powerslave",       "2 Minutes",    Genre.HEAVY_METAL,  1984, 4),
                track(USER, "Iron Maiden",   "The Number of...", "Hallowed",     Genre.HEAVY_METAL,  1982, 5),
                track(USER, "Metallica",     "Master of Puppets","Battery",      Genre.THRASH_METAL, 1986, 5),
                track(USER, "Metallica",     "Ride the Light.",  "Fade to Black",Genre.THRASH_METAL, 1984, 0),
                track(USER, "Led Zeppelin",  "IV",               "Black Dog",    Genre.HARD_ROCK,    1971, 4),
                track(USER, "Led Zeppelin",  "IV",               "Stairway",     Genre.HARD_ROCK,    1971, 0),
                track(USER, "",              "Bootleg",          "Untitled",     Genre.OTHER,        null, 0), // blank artist
                track(USER, "Anonymous",     "",                 "Lost",         Genre.OTHER,        null, 0), // blank album
                softDeleted(track(USER, "Black Sabbath", "Paranoid", "Iron Man", Genre.HARD_ROCK,    1970, 5)),
                track(OTHER, "Other User Band", "Album", "Track", Genre.HARD_ROCK, 2000, 3) // belongs to a different user
        ));
    }

    @Test
    void totalTracksExcludesSoftDeletedAndOtherUsers() {
        Map<String, Object> stats = repository.getStatistics(USER);
        assertEquals(9L, stats.get("totalTracks"));
    }

    @Test
    void genreDistributionAggregatesActiveTracksOnly() {
        Map<String, Object> stats = repository.getStatistics(USER);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dist = (List<Map<String, Object>>) stats.get("genreDistribution");

        Map<String, Integer> byGenre = new java.util.HashMap<>();
        for (Map<String, Object> entry : dist) {
            byGenre.put((String) entry.get("genre"), ((Number) entry.get("count")).intValue());
        }
        assertEquals(3, byGenre.get("HEAVY_METAL"));
        assertEquals(2, byGenre.get("THRASH_METAL"));
        assertEquals(2, byGenre.get("HARD_ROCK"));
        assertEquals(2, byGenre.get("OTHER"));
        assertTrue(!byGenre.isEmpty(), "Soft-deleted Black Sabbath HARD_ROCK should not be counted");
        // Verify ordering: descending by count
        int prev = Integer.MAX_VALUE;
        for (Map<String, Object> entry : dist) {
            int c = ((Number) entry.get("count")).intValue();
            assertTrue(c <= prev, "Genres must be sorted by count descending");
            prev = c;
        }
    }

    @Test
    void topArtistsExcludesBlankAndCapsAt10() {
        Map<String, Object> stats = repository.getStatistics(USER);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> top = (List<Map<String, Object>>) stats.get("topArtists");
        assertTrue(top.size() <= 10);
        // First artist should be Iron Maiden with 3 tracks
        assertEquals("Iron Maiden", top.get(0).get("artist"));
        assertEquals(3, ((Number) top.get(0).get("count")).intValue());
        // No blank artist key should appear
        for (Map<String, Object> entry : top) {
            assertTrue(entry.get("artist") != null && !((String) entry.get("artist")).isEmpty());
        }
    }

    @Test
    void totalArtistsAndAlbumsExcludeBlanks() {
        Map<String, Object> stats = repository.getStatistics(USER);
        // Distinct non-blank artists for USER: Iron Maiden, Metallica, Led Zeppelin, Anonymous = 4
        assertEquals(4L, stats.get("totalArtists"));
        // Distinct non-blank albums for USER: Powerslave, The Number of..., Master of Puppets,
        // Ride the Light., IV, Bootleg = 6
        assertEquals(6L, stats.get("totalAlbums"));
    }

    @Test
    void decadeDistributionUsesIntegerYearMath() {
        Map<String, Object> stats = repository.getStatistics(USER);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dec = (List<Map<String, Object>>) stats.get("decadeDistribution");
        Map<String, Integer> byDecade = new java.util.HashMap<>();
        for (Map<String, Object> entry : dec) {
            byDecade.put((String) entry.get("decade"), ((Number) entry.get("count")).intValue());
        }
        // 1984, 1984, 1982 → 1980s = 5 (plus 1986 and 1984)
        assertEquals(5, byDecade.get("1980s"));
        // 1971, 1971 → 1970s = 2
        assertEquals(2, byDecade.get("1970s"));
        // null years are excluded
        assertTrue(!byDecade.containsKey(null));
    }

    @Test
    void averageRatingExcludesUnratedAndRoundsToTwoDecimals() {
        Map<String, Object> stats = repository.getStatistics(USER);
        // Rated values among USER's active tracks: 5,4,5,5,4 → avg = 4.6
        double avg = ((Number) stats.get("averageRating")).doubleValue();
        assertEquals(4.6, avg, 0.001);
    }

    @Test
    void emptyUserReturnsZeroStats() {
        Map<String, Object> stats = repository.getStatistics("ghost-user");
        assertEquals(0L, stats.get("totalTracks"));
        assertEquals(0L, stats.get("totalArtists"));
        assertEquals(0L, stats.get("totalAlbums"));
        assertEquals(0.0, stats.get("averageRating"));
        assertTrue(((List<?>) stats.get("genreDistribution")).isEmpty());
        assertTrue(((List<?>) stats.get("topArtists")).isEmpty());
        assertTrue(((List<?>) stats.get("decadeDistribution")).isEmpty());
    }

    private static MusicFile track(String userId, String artist, String album, String title,
                                   Genre genre, Integer year, int rating) {
        return MusicFile.builder()
                .userId(userId)
                .filePath("/m/" + userId + "/" + title + "-" + System.nanoTime())
                .fileName(title + ".mp3")
                .artist(artist)
                .album(album)
                .title(title)
                .genre(genre)
                .year(year)
                .rating(rating)
                .build();
    }

    private static MusicFile softDeleted(MusicFile m) {
        m.setDeleted(true);
        m.setDeletedAt(java.time.Instant.now());
        return m;
    }
}
