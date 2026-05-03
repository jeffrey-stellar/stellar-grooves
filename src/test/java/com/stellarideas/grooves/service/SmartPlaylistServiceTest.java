package com.stellarideas.grooves.service;

import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.Playlist;
import com.stellarideas.grooves.model.SmartPlaylist;
import com.stellarideas.grooves.repository.PlaylistRepository;
import com.stellarideas.grooves.repository.SmartPlaylistRepository;
import com.stellarideas.grooves.repository.UserRepository;
import com.stellarideas.grooves.smartplaylist.QueryParseException;
import com.stellarideas.grooves.smartplaylist.SmartPlaylistQueryParser;
import com.stellarideas.grooves.smartplaylist.SmartPlaylistQueryTranslator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
    private UserRepository userRepository;
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        repository = mock(SmartPlaylistRepository.class);
        playlistRepository = mock(PlaylistRepository.class);
        userRepository = mock(UserRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        service = new SmartPlaylistService(
                repository,
                playlistRepository,
                userRepository,
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

        SmartPlaylistService.PreviewResult result = service.execute("user-1", "rating:>=4 limit:50", 0, 25);

        assertEquals(50L, result.page().getTotalElements());
        assertTrue(result.truncated(), "500 matches clipped to limit of 50 should be reported as truncated");
    }

    @Test
    void executeCapsAtMaterializeMaxWhenNoUserLimit() {
        when(mongoTemplate.count(any(Query.class), eq(MusicFile.class))).thenReturn(5_000L);
        when(mongoTemplate.find(any(Query.class), eq(MusicFile.class))).thenReturn(List.of());

        // materializeMax is set to 100 in setUp
        SmartPlaylistService.PreviewResult result = service.execute("user-1", "rating:>=4", 0, 25);

        assertEquals(100L, result.page().getTotalElements());
        assertTrue(result.truncated(), "no user limit + 5000 matches should cap at materializeMax and be truncated");
    }

    @Test
    void executeDoesNotReportTruncatedWhenUnderCap() {
        when(mongoTemplate.count(any(Query.class), eq(MusicFile.class))).thenReturn(10L);
        when(mongoTemplate.find(any(Query.class), eq(MusicFile.class))).thenReturn(List.of());

        SmartPlaylistService.PreviewResult result = service.execute("user-1", "rating:>=4", 0, 25);

        assertEquals(10L, result.page().getTotalElements());
        assertFalse(result.truncated());
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

        SmartPlaylistService.PreviewResult result = service.execute("user-1", "genre:heavy_metal sort:random limit:2", 0, 10);

        assertEquals(2, result.page().getContent().size());
        assertEquals(2L, result.page().getTotalElements());
        verify(mongoTemplate, never()).find(any(Query.class), eq(MusicFile.class));
    }

    @Test
    void randomSortPipelineMatchesBeforeSampleWithLimitSize() {
        @SuppressWarnings("unchecked")
        AggregationResults<MusicFile> results = mock(AggregationResults.class);
        when(results.getMappedResults()).thenReturn(List.of());
        when(mongoTemplate.aggregate(any(Aggregation.class), eq(MusicFile.class), eq(MusicFile.class)))
                .thenReturn(results);

        service.execute("user-1", "genre:heavy_metal sort:random limit:20", 0, 100);

        ArgumentCaptor<Aggregation> aggCaptor = ArgumentCaptor.forClass(Aggregation.class);
        verify(mongoTemplate).aggregate(aggCaptor.capture(), eq(MusicFile.class), eq(MusicFile.class));
        String pipeline = aggCaptor.getValue().toString();
        // $match must come before $sample so the sample runs over the filtered set,
        // and sample size must equal the user's limit (not a larger intermediate).
        int matchIdx = pipeline.indexOf("$match");
        int sampleIdx = pipeline.indexOf("$sample");
        assertTrue(matchIdx >= 0 && sampleIdx > matchIdx,
                "expected $match before $sample in pipeline: " + pipeline);
        assertTrue(pipeline.contains("\"size\" : 20") || pipeline.contains("\"size\": 20"),
                "expected $sample size to equal limit (20): " + pipeline);
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

    // ─── Sharing / subscriptions ────────────────────────────────────────

    @Test
    void shareGeneratesTokenAndPersists() {
        SmartPlaylist sp = playlist("curator-1", "genre:thrash_metal");
        when(repository.save(any(SmartPlaylist.class))).thenAnswer(inv -> inv.getArgument(0));

        SmartPlaylist shared = service.share(sp);

        assertNotNull(shared.getShareToken());
        assertFalse(shared.getShareToken().isBlank());
        verify(repository).save(sp);
    }

    @Test
    void shareIsIdempotentWhenTokenAlreadyExists() {
        SmartPlaylist sp = playlist("curator-1", "genre:thrash_metal");
        sp.setShareToken("existing-token");

        SmartPlaylist shared = service.share(sp);

        assertEquals("existing-token", shared.getShareToken());
        verify(repository, never()).save(any());
    }

    @Test
    void shareRejectedOnSubscription() {
        SmartPlaylist sub = playlist("user-2", "");
        sub.setSubscribedFromId("source-1");
        assertThrows(IllegalStateException.class, () -> service.share(sub));
    }

    @Test
    void revokeShareClearsToken() {
        SmartPlaylist sp = playlist("curator-1", "genre:hard_rock");
        sp.setShareToken("tok-abc");
        when(repository.save(any(SmartPlaylist.class))).thenAnswer(inv -> inv.getArgument(0));

        SmartPlaylist revoked = service.revokeShare(sp);

        assertNull(revoked.getShareToken());
        assertNull(revoked.getShareTokenExpiresAt());
    }

    @Test
    void subscribeCreatesNewRowLinkedToSource() {
        SmartPlaylist source = playlist("curator-1", "genre:thrash_metal");
        when(repository.findBySubscribedFromId("sp-1")).thenReturn(List.of());
        when(repository.existsByUserIdAndName(eq("subscriber-1"), any())).thenReturn(false);
        when(repository.save(any(SmartPlaylist.class))).thenAnswer(inv -> inv.getArgument(0));

        SmartPlaylist sub = service.subscribe("subscriber-1", source);

        assertEquals("subscriber-1", sub.getUserId());
        assertEquals("sp-1", sub.getSubscribedFromId());
        assertTrue(sub.isSubscription());
        assertEquals("Rediscover", sub.getName());
    }

    @Test
    void subscribeIsIdempotentForSameUser() {
        SmartPlaylist source = playlist("curator-1", "genre:thrash_metal");
        SmartPlaylist existingSub = new SmartPlaylist();
        existingSub.setId("sub-1");
        existingSub.setUserId("subscriber-1");
        existingSub.setSubscribedFromId("sp-1");
        when(repository.findBySubscribedFromId("sp-1")).thenReturn(List.of(existingSub));

        SmartPlaylist result = service.subscribe("subscriber-1", source);

        assertEquals("sub-1", result.getId());
        verify(repository, never()).save(any());
    }

    @Test
    void subscribeRejectsSelfSubscription() {
        SmartPlaylist source = playlist("curator-1", "genre:thrash_metal");
        assertThrows(IllegalArgumentException.class, () -> service.subscribe("curator-1", source));
    }

    @Test
    void subscribeAvoidsNameCollision() {
        SmartPlaylist source = playlist("curator-1", "genre:thrash_metal"); // name "Rediscover"
        when(repository.findBySubscribedFromId("sp-1")).thenReturn(List.of());
        when(repository.existsByUserIdAndName("subscriber-1", "Rediscover")).thenReturn(true);
        when(repository.existsByUserIdAndName("subscriber-1", "Rediscover (2)")).thenReturn(false);
        when(repository.save(any(SmartPlaylist.class))).thenAnswer(inv -> inv.getArgument(0));

        SmartPlaylist sub = service.subscribe("subscriber-1", source);

        assertEquals("Rediscover (2)", sub.getName());
    }

    @Test
    void resolveQueryStringReadsFromSourceForSubscription() {
        SmartPlaylist source = playlist("curator-1", "genre:hard_rock rating:>=4");
        SmartPlaylist sub = new SmartPlaylist();
        sub.setId("sub-1");
        sub.setUserId("subscriber-1");
        sub.setSubscribedFromId("sp-1");
        sub.setQueryString("STALE-CACHE-VALUE"); // should be ignored
        when(repository.findById("sp-1")).thenReturn(java.util.Optional.of(source));

        assertEquals("genre:hard_rock rating:>=4", service.resolveQueryString(sub));
    }

    @Test
    void resolveQueryStringFallsBackToOwnerQueryWhenNotSubscription() {
        SmartPlaylist owner = playlist("user-1", "genre:thrash_metal");
        assertEquals("genre:thrash_metal", service.resolveQueryString(owner));
    }

    @Test
    void resolveQueryStringThrowsWhenSourceDeleted() {
        SmartPlaylist sub = new SmartPlaylist();
        sub.setUserId("subscriber-1");
        sub.setSubscribedFromId("source-deleted");
        when(repository.findById("source-deleted")).thenReturn(java.util.Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.resolveQueryString(sub));
    }

    @Test
    void countOnSubscriptionReadsLiveQueryFromSource() {
        SmartPlaylist source = playlist("curator-1", "genre:thrash_metal");
        SmartPlaylist sub = new SmartPlaylist();
        sub.setId("sub-1");
        sub.setUserId("subscriber-1");
        sub.setSubscribedFromId("sp-1");
        when(repository.findById("sp-1")).thenReturn(java.util.Optional.of(source));
        when(mongoTemplate.count(any(Query.class), eq(MusicFile.class))).thenReturn(42L);

        long count = service.count(sub);

        assertEquals(42L, count);
        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).count(captor.capture(), eq(MusicFile.class));
        // Subscriber's userId is what's used for ownership scoping — query runs against subscriber's library
        assertEquals("subscriber-1", captor.getValue().getQueryObject().get("userId"));
    }

    @Test
    void updateRejectsSubscriptionEdits() {
        SmartPlaylist sub = new SmartPlaylist();
        sub.setUserId("subscriber-1");
        sub.setSubscribedFromId("source-1");
        sub.setName("My Sub");
        sub.setQueryString("");
        assertThrows(IllegalStateException.class,
                () -> service.update(sub, "Renamed", "rating:>=5", null));
        verify(repository, never()).save(any());
    }

    @Test
    void renameSubscriptionUpdatesName() {
        SmartPlaylist sub = new SmartPlaylist();
        sub.setId("sub-1");
        sub.setUserId("subscriber-1");
        sub.setSubscribedFromId("source-1");
        sub.setName("Original");
        when(repository.existsByUserIdAndName("subscriber-1", "My Local Name")).thenReturn(false);
        when(repository.save(any(SmartPlaylist.class))).thenAnswer(inv -> inv.getArgument(0));

        SmartPlaylist saved = service.renameSubscription(sub, "My Local Name");

        assertEquals("My Local Name", saved.getName());
    }

    @Test
    void renameSubscriptionRejectedOnOwnedRow() {
        SmartPlaylist owner = playlist("user-1", "genre:thrash_metal");
        assertThrows(IllegalStateException.class,
                () -> service.renameSubscription(owner, "Rename"));
    }

    @Test
    void forkCopiesSourceQueryAndClearsLink() {
        SmartPlaylist source = playlist("curator-1", "genre:thrash_metal sort:random limit:50");
        source.setDescription("Curator's intent");
        SmartPlaylist sub = new SmartPlaylist();
        sub.setId("sub-1");
        sub.setUserId("subscriber-1");
        sub.setSubscribedFromId("sp-1");
        sub.setQueryString("OLD-SNAPSHOT");
        when(repository.findById("sp-1")).thenReturn(java.util.Optional.of(source));
        when(repository.save(any(SmartPlaylist.class))).thenAnswer(inv -> inv.getArgument(0));

        SmartPlaylist forked = service.fork(sub);

        assertNull(forked.getSubscribedFromId());
        assertEquals("genre:thrash_metal sort:random limit:50", forked.getQueryString());
        assertEquals("Curator's intent", forked.getDescription());
        assertFalse(forked.isSubscription());
    }

    @Test
    void forkRejectedOnOwnedRow() {
        SmartPlaylist owner = playlist("user-1", "genre:thrash_metal");
        assertThrows(IllegalStateException.class, () -> service.fork(owner));
    }

    @Test
    void forkFallsBackToSnapshotWhenSourceDeleted() {
        // The subscriber row carries a snapshotted queryString from subscribe time —
        // fork uses that rather than failing when the curator has deleted the source.
        SmartPlaylist sub = new SmartPlaylist();
        sub.setId("sub-1");
        sub.setUserId("subscriber-1");
        sub.setSubscribedFromId("source-gone");
        sub.setQueryString("genre:thrash_metal year:>=1986"); // snapshot
        when(repository.findById("source-gone")).thenReturn(java.util.Optional.empty());
        when(repository.save(any(SmartPlaylist.class))).thenAnswer(inv -> inv.getArgument(0));

        SmartPlaylist forked = service.fork(sub);

        assertNull(forked.getSubscribedFromId());
        assertEquals("genre:thrash_metal year:>=1986", forked.getQueryString());
        assertFalse(forked.isSubscription());
    }

    @Test
    void subscriberCountReturnsZeroForUnsavedPlaylist() {
        SmartPlaylist sp = new SmartPlaylist(); // no id
        assertEquals(0L, service.subscriberCount(sp));
        verify(repository, never()).countBySubscribedFromId(any());
    }

    @Test
    void subscriberCountDelegatesToRepo() {
        SmartPlaylist sp = playlist("curator-1", "genre:thrash_metal");
        when(repository.countBySubscribedFromId("sp-1")).thenReturn(7L);
        assertEquals(7L, service.subscriberCount(sp));
    }
}
