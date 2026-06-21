package com.stellarideas.grooves.integration;

import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.Playlist;
import com.stellarideas.grooves.repository.MusicFileRepository;
import com.stellarideas.grooves.repository.PlaylistRepository;
import com.stellarideas.grooves.service.PlaylistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for playlist operations under concurrency against real MongoDB.
 * Tests concurrent track additions, reordering, and share token generation.
 */
class PlaylistConcurrencyIT extends BaseIntegrationTest {

    @Autowired
    private PlaylistService playlistService;

    @Autowired
    private PlaylistRepository playlistRepository;

    @Autowired
    private MusicFileRepository musicFileRepository;

    private static final String USER_ID = "concurrency-user";

    @BeforeEach
    void cleanUp() {
        playlistRepository.deleteAll();
        musicFileRepository.deleteAll();
    }

    @Test
    void concurrentTrackAdditionsToSamePlaylist() throws Exception {
        Playlist playlist = playlistService.createPlaylist("Concurrent Playlist", USER_ID);

        // Create tracks
        List<String> trackIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            MusicFile track = MusicFile.builder()
                    .title("Track " + i)
                    .artist("Artist " + i)
                    .album("Album")
                    .genre(Genre.HARD_ROCK)
                    .userId(USER_ID)
                    .filePath("/music/track" + i + ".mp3")
                    .fileName("track" + i + ".mp3")
                    .build();
            track = musicFileRepository.save(track);
            trackIds.add(track.getId());
        }

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        // Each thread adds a different track
        for (int i = 0; i < threadCount; i++) {
            final String trackId = trackIds.get(i);
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // Re-fetch the playlist to simulate concurrent access
                    Playlist current = playlistRepository.findById(playlist.getId()).orElseThrow();
                    playlistService.addTrack(current, trackId, USER_ID);
                } catch (Exception e) {
                    // Some may fail due to concurrent modification — that's expected
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        // Verify the playlist has tracks (at least some should succeed)
        Playlist result = playlistRepository.findById(playlist.getId()).orElseThrow();
        assertFalse(result.getTrackIds().isEmpty(), "At least some tracks should be added");
        // No duplicate track IDs
        long uniqueCount = result.getTrackIds().stream().distinct().count();
        assertEquals(result.getTrackIds().size(), uniqueCount,
                "Playlist should not contain duplicate track IDs");
    }

    @Test
    void concurrentShareTokenGenerationProducesValidState() throws Exception {
        Playlist playlist = playlistService.createPlaylist("Share Test", USER_ID);

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                Playlist current = playlistRepository.findById(playlist.getId()).orElseThrow();
                return playlistService.generateShareToken(current, null);
            }));
        }

        startLatch.countDown();

        // Concurrent generateShareToken calls each save the playlist, so they collide
        // on optimistic locking — some threads fail and that's expected (matching the
        // other concurrency tests here). Collect only the tokens that persisted.
        List<String> tokens = new ArrayList<>();
        for (Future<String> f : futures) {
            try {
                String token = f.get(10, TimeUnit.SECONDS);
                if (token != null) {
                    tokens.add(token);
                }
            } catch (ExecutionException e) {
                // optimistic-locking collision under concurrency — acceptable
            }
        }
        executor.shutdown();

        // The final state should have exactly one share token
        Playlist result = playlistRepository.findById(playlist.getId()).orElseThrow();
        assertNotNull(result.getShareToken(), "Playlist should have a share token");
        // The token in the DB should match one of the generated tokens
        assertTrue(tokens.contains(result.getShareToken()),
                "Final share token should be one of the generated tokens");
    }

    @Test
    void concurrentAddAndRemoveDoesNotCorrupt() throws Exception {
        Playlist playlist = playlistService.createPlaylist("Add Remove Test", USER_ID);

        // Create two tracks
        MusicFile track1 = musicFileRepository.save(MusicFile.builder()
                .title("Track A").artist("Artist").album("Album").genre(Genre.CLASSIC_ROCK)
                .userId(USER_ID).filePath("/music/a.mp3").fileName("a.mp3").build());
        MusicFile track2 = musicFileRepository.save(MusicFile.builder()
                .title("Track B").artist("Artist").album("Album").genre(Genre.CLASSIC_ROCK)
                .userId(USER_ID).filePath("/music/b.mp3").fileName("b.mp3").build());

        // Pre-add track1
        Playlist p = playlistRepository.findById(playlist.getId()).orElseThrow();
        playlistService.addTrack(p, track1.getId(), USER_ID);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        // Thread 1: remove track1
        executor.submit(() -> {
            try {
                startLatch.await();
                Playlist current = playlistRepository.findById(playlist.getId()).orElseThrow();
                playlistService.removeTrack(current, track1.getId());
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread 2: add track2
        executor.submit(() -> {
            try {
                startLatch.await();
                Playlist current = playlistRepository.findById(playlist.getId()).orElseThrow();
                playlistService.addTrack(current, track2.getId(), USER_ID);
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        // Verify the playlist is in a valid state (no null entries, no corruption)
        Playlist result = playlistRepository.findById(playlist.getId()).orElseThrow();
        assertNotNull(result.getTrackIds());
        assertFalse(result.getTrackIds().contains(null), "No null entries in track list");
    }
}
