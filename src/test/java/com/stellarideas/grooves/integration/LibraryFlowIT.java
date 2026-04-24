package com.stellarideas.grooves.integration;

import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.Playlist;
import com.stellarideas.grooves.model.Role;
import com.stellarideas.grooves.model.SmartPlaylist;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.MusicFileRepository;
import com.stellarideas.grooves.repository.PlayEventRepository;
import com.stellarideas.grooves.repository.PlaylistRepository;
import com.stellarideas.grooves.repository.SmartPlaylistRepository;
import com.stellarideas.grooves.repository.UserRepository;
import com.stellarideas.grooves.service.LibraryService;
import com.stellarideas.grooves.service.PlayHistoryService;
import com.stellarideas.grooves.service.SmartPlaylistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end flow against a real MongoDB: seed a library, search it, run a
 * smart-playlist query, materialize the result, then record a play and verify
 * the play count and history reflect it. Catches integration-level regressions
 * (criteria translation, aggregation, atomic counter updates) that unit tests
 * with mocked MongoTemplate can't.
 */
class LibraryFlowIT extends BaseIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private MusicFileRepository musicFileRepository;
    @Autowired private PlaylistRepository playlistRepository;
    @Autowired private SmartPlaylistRepository smartPlaylistRepository;
    @Autowired private PlayEventRepository playEventRepository;
    @Autowired private LibraryService libraryService;
    @Autowired private SmartPlaylistService smartPlaylistService;
    @Autowired private PlayHistoryService playHistoryService;

    private User user;

    @BeforeEach
    void cleanUp() {
        playEventRepository.deleteAll();
        smartPlaylistRepository.deleteAll();
        playlistRepository.deleteAll();
        musicFileRepository.deleteAll();
        userRepository.deleteAll();

        User u = new User();
        u.setUsername("flowuser");
        u.setEmail("flow@test.com");
        u.setPassword("encoded");
        u.setRoles(Set.of(Role.ROLE_USER));
        user = userRepository.save(u);

        seedLibrary();
    }

    private void seedLibrary() {
        // Mix of genres and ratings so the smart-playlist query has something to filter
        save("Black Sabbath", "Paranoid", "War Pigs",        Genre.HEAVY_METAL, 5);
        save("Black Sabbath", "Paranoid", "Iron Man",        Genre.HEAVY_METAL, 5);
        save("Iron Maiden",   "Powerslave","Aces High",      Genre.HEAVY_METAL, 4);
        save("Metallica",     "Master of Puppets","Battery", Genre.THRASH_METAL, 4);
        save("Metallica",     "Kill Em All","Whiplash",      Genre.THRASH_METAL, 3);
        save("Boston",        "Boston","More Than a Feeling",Genre.CLASSIC_ROCK, 4);
        save("Poison",        "Look What the Cat Dragged In","Talk Dirty to Me", Genre.HAIR_METAL, 2);
    }

    private MusicFile save(String artist, String album, String title, Genre genre, int rating) {
        MusicFile f = MusicFile.builder()
                .userId(user.getId())
                .filePath("/library/" + artist + "/" + album + "/" + title + ".mp3")
                .fileName(title + ".mp3")
                .artist(artist).album(album).title(title)
                .genre(genre).rating(rating)
                .build();
        return musicFileRepository.save(f);
    }

    @Test
    void scanResultsFlowThroughSearchSmartPlaylistMaterializeAndPlay() {
        // 1. Search hits the indexed library and returns a known track
        Page<MusicFile> warPigs = libraryService.searchFiles(user.getId(), "War Pigs", 0, 10);
        assertEquals(1, warPigs.getTotalElements(), "search should find 'War Pigs'");
        MusicFile target = warPigs.getContent().get(0);
        assertEquals("Black Sabbath", target.getArtist());

        // 2. Smart playlist: top-rated metal across two genres, sorted by rating
        SmartPlaylist sp = smartPlaylistService.create(
                user.getId(),
                "Top Metal",
                "(genre:heavy_metal OR genre:thrash_metal) rating:>=4 sort:rating:desc");

        // 3. Preview executes the query against real Mongo — verify expected matches
        SmartPlaylistService.PreviewResult preview = smartPlaylistService.preview(sp, 0, 50);
        List<MusicFile> previewTracks = preview.page().getContent();
        assertEquals(4, preview.page().getTotalElements(),
                "should match 2 War Pigs/Iron Man + Aces High + Battery (rating>=4)");
        assertFalse(preview.truncated(), "small result set is not truncated");
        // Sort:rating:desc — ratings should be non-increasing
        for (int i = 1; i < previewTracks.size(); i++) {
            assertTrue(previewTracks.get(i - 1).getRating() >= previewTracks.get(i).getRating(),
                    "tracks should be ordered by rating desc");
        }
        // No classic rock or hair metal should sneak in
        previewTracks.forEach(t ->
                assertTrue(t.getGenre() == Genre.HEAVY_METAL || t.getGenre() == Genre.THRASH_METAL,
                        "unexpected genre in result: " + t.getGenre()));

        // 4. Materialize snapshots the current matches into a static playlist
        SmartPlaylistService.MaterializeResult mat = smartPlaylistService.materialize(sp, "Top Metal Snapshot");
        assertEquals(4, mat.trackCount());
        assertFalse(mat.truncated());
        Playlist saved = playlistRepository.findById(mat.playlist().getId()).orElseThrow();
        assertEquals("Top Metal Snapshot", saved.getName());
        assertEquals(4, saved.getTrackIds().size());

        // 5. Record a play and verify playCount + lastPlayedAt move
        assertTrue(playHistoryService.recordPlay(user.getId(), target.getId(), 30_000, true));
        MusicFile after = musicFileRepository.findByIdAndUserIdAndDeletedFalse(target.getId(), user.getId()).orElseThrow();
        assertEquals(1, after.getPlayCount(), "playCount should increment by 1");
        assertNotNull(after.getLastPlayedAt(), "lastPlayedAt should be set");

        // 6. The recorded play surfaces in recent history
        Page<PlayHistoryService.RecentPlay> history =
                playHistoryService.getRecentPlays(user.getId(), PlayHistoryService.Window.ALL, 0, 10);
        assertEquals(1, history.getTotalElements());
        assertEquals(target.getId(), history.getContent().get(0).track().getId());
    }
}
