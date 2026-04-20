package com.stellarideas.grooves.service;

import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.Playlist;
import com.stellarideas.grooves.model.SmartPlaylist;
import com.stellarideas.grooves.repository.PlaylistRepository;
import com.stellarideas.grooves.repository.SmartPlaylistRepository;
import com.stellarideas.grooves.smartplaylist.QueryParseException;
import com.stellarideas.grooves.smartplaylist.SmartPlaylistQueryParser;
import com.stellarideas.grooves.smartplaylist.SmartPlaylistQueryTranslator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SmartPlaylistServiceTest {

    private SmartPlaylistService service;
    private SmartPlaylistRepository repository;
    private PlaylistRepository playlistRepository;
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        repository = mock(SmartPlaylistRepository.class);
        playlistRepository = mock(PlaylistRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        service = new SmartPlaylistService(
                repository,
                playlistRepository,
                new SmartPlaylistQueryParser(),
                new SmartPlaylistQueryTranslator(),
                mongoTemplate);
        ReflectionTestUtils.setField(service, "materializeMax", 100);
    }

    private SmartPlaylist playlist(String userId, String queryString) {
        SmartPlaylist sp = new SmartPlaylist();
        sp.setId("sp-1");
        sp.setUserId(userId);
        sp.setName("Rediscover");
        sp.setQueryString(queryString);
        return sp;
    }

    @Test
    void createValidatesQueryBeforeSaving() {
        assertThrows(QueryParseException.class,
                () -> service.create("user-1", "My List", "bogus:field"));
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsDuplicateName() {
        when(repository.existsByUserIdAndName("user-1", "Rediscover")).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> service.create("user-1", "Rediscover", "rating:>=4"));
        verify(repository, never()).save(any());
    }

    @Test
    void createPersistsValidQuery() {
        when(repository.save(any(SmartPlaylist.class))).thenAnswer(inv -> inv.getArgument(0));

        SmartPlaylist saved = service.create("user-1", "Rediscover", "rating:>=4 lastPlayed:>6mo");

        assertEquals("Rediscover", saved.getName());
        assertEquals("user-1", saved.getUserId());
        verify(repository).save(any(SmartPlaylist.class));
    }

    @Test
    void countScopesQueryByUser() {
        when(mongoTemplate.count(any(Query.class), eq(MusicFile.class))).thenReturn(17L);

        long count = service.count(playlist("user-1", "genre:thrash_metal"));

        assertEquals(17L, count);
        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).count(captor.capture(), eq(MusicFile.class));
        assertEquals("user-1", captor.getValue().getQueryObject().get("userId"));
    }

    @Test
    void materializeSnapshotsTracksIntoNewPlaylist() {
        MusicFile t1 = MusicFile.builder().id("t1").artist("A").title("One").genre(Genre.THRASH_METAL).build();
        MusicFile t2 = MusicFile.builder().id("t2").artist("B").title("Two").genre(Genre.THRASH_METAL).build();
        when(mongoTemplate.find(any(Query.class), eq(MusicFile.class))).thenReturn(List.of(t1, t2));
        when(playlistRepository.save(any(Playlist.class))).thenAnswer(inv -> {
            Playlist p = inv.getArgument(0);
            p.setId("pl-1");
            return p;
        });

        SmartPlaylistService.MaterializeResult result = service.materialize(
                playlist("user-1", "genre:thrash_metal"), "Snapshot");

        assertEquals("pl-1", result.playlist().getId());
        assertEquals("Snapshot", result.playlist().getName());
        assertEquals(List.of("t1", "t2"), result.playlist().getTrackIds());
        assertEquals(2, result.trackCount());
        assertFalse(result.truncated());
    }

    @Test
    void materializeMarksTruncatedWhenCapHit() {
        List<MusicFile> files = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            files.add(MusicFile.builder().id("t" + i).build());
        }
        when(mongoTemplate.find(any(Query.class), eq(MusicFile.class))).thenReturn(files);
        when(playlistRepository.save(any(Playlist.class))).thenAnswer(inv -> {
            Playlist p = inv.getArgument(0); p.setId("pl-2"); return p;
        });

        SmartPlaylistService.MaterializeResult result = service.materialize(
                playlist("user-1", "rating:>=0"), "Big");

        assertEquals(100, result.trackCount());
        assertTrue(result.truncated());
    }

    @Test
    void materializeRejectsInvalidQuery() {
        SmartPlaylist bad = playlist("user-1", "lastPlayed:6mo"); // missing comparator
        assertThrows(QueryParseException.class, () -> service.materialize(bad, "X"));
        verifyNoInteractions(playlistRepository);
    }

    @Test
    void executeAppliesUserSortOverDefault() {
        when(mongoTemplate.count(any(Query.class), eq(MusicFile.class))).thenReturn(3L);
        when(mongoTemplate.find(any(Query.class), eq(MusicFile.class))).thenReturn(List.of());

        service.execute("user-1", "rating:>=4 sort:rating", 0, 10);

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(captor.capture(), eq(MusicFile.class));
        org.bson.Document sortDoc = captor.getValue().getSortObject();
        assertEquals(-1, sortDoc.get("rating"));
        assertNull(sortDoc.get("artist"), "default sort should not apply when user provided one");
    }

    @Test
    void executeClampsTotalToUserLimit() {
        when(mongoTemplate.count(any(Query.class), eq(MusicFile.class))).thenReturn(500L);
        when(mongoTemplate.find(any(Query.class), eq(MusicFile.class))).thenReturn(List.of());

        Page<MusicFile> page = service.execute("user-1", "rating:>=4 limit:50", 0, 25);

        assertEquals(50L, page.getTotalElements());
    }

    @Test
    void executeWithRandomSortInvokesAggregateAndReturnsSingleSample() {
        MusicFile a = MusicFile.builder().id("a").build();
        MusicFile b = MusicFile.builder().id("b").build();
        @SuppressWarnings("unchecked")
        AggregationResults<MusicFile> results = mock(AggregationResults.class);
        when(results.getMappedResults()).thenReturn(List.of(a, b));
        when(mongoTemplate.aggregate(any(Aggregation.class), eq(MusicFile.class), eq(MusicFile.class)))
                .thenReturn(results);

        Page<MusicFile> page = service.execute("user-1", "genre:heavy_metal sort:random limit:2", 0, 10);

        assertEquals(2, page.getContent().size());
        assertEquals(2L, page.getTotalElements());
        verify(mongoTemplate, never()).find(any(Query.class), eq(MusicFile.class));
    }

    @Test
    void materializeWithRandomSortUsesAggregate() {
        MusicFile a = MusicFile.builder().id("a").build();
        MusicFile b = MusicFile.builder().id("b").build();
        @SuppressWarnings("unchecked")
        AggregationResults<MusicFile> results = mock(AggregationResults.class);
        when(results.getMappedResults()).thenReturn(List.of(a, b));
        when(mongoTemplate.aggregate(any(Aggregation.class), eq(MusicFile.class), eq(MusicFile.class)))
                .thenReturn(results);
        when(playlistRepository.save(any(Playlist.class))).thenAnswer(inv -> {
            Playlist p = inv.getArgument(0); p.setId("pl-r"); return p;
        });

        SmartPlaylistService.MaterializeResult result = service.materialize(
                playlist("user-1", "genre:heavy_metal sort:random limit:2"), "Shuffled");

        assertEquals(List.of("a", "b"), result.playlist().getTrackIds());
        assertFalse(result.truncated());
        verify(mongoTemplate, never()).find(any(Query.class), eq(MusicFile.class));
    }

    @Test
    void materializeRespectsUserLimitBelowCap() {
        List<MusicFile> files = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) files.add(MusicFile.builder().id("t" + i).build());
        when(mongoTemplate.find(any(Query.class), eq(MusicFile.class))).thenReturn(files);
        when(playlistRepository.save(any(Playlist.class))).thenAnswer(inv -> {
            Playlist p = inv.getArgument(0); p.setId("pl-x"); return p;
        });

        SmartPlaylistService.MaterializeResult result = service.materialize(
                playlist("user-1", "rating:>=0 limit:10"), "Top10");

        assertEquals(10, result.trackCount());
        assertFalse(result.truncated(),
                "a user-requested limit fully satisfied is not a truncation");
    }
}
