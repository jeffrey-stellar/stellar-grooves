package com.stellarideas.grooves.service;

import com.stellarideas.grooves.dto.MusicFileDTO;
import com.stellarideas.grooves.model.MusicFile;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RediscoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-20T10:00:00Z");

    private MongoTemplate mongoTemplate;
    private RediscoveryService service;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        service = new RediscoveryService(
                mongoTemplate, Clock.fixed(NOW, ZoneOffset.UTC),
                180, 90, 4, 3);
    }

    // ── Forgotten ─────────────────────────────────────────────

    @Test
    void forgottenFiltersByPlayCountAndCutoff() {
        MusicFile f = MusicFile.builder().id("f1").userId("u1").title("T").artist("A").playCount(3).build();
        when(mongoTemplate.count(any(Query.class), eq(MusicFile.class))).thenReturn(1L);
        when(mongoTemplate.find(any(Query.class), eq(MusicFile.class))).thenReturn(List.of(f));

        Page<MusicFileDTO> page = service.findForgotten("u1", null, PageRequest.of(0, 20));

        assertEquals(1, page.getContent().size());
        assertEquals("f1", page.getContent().get(0).getId());

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).count(captor.capture(), eq(MusicFile.class));
        Document q = captor.getValue().getQueryObject();
        assertEquals("u1", q.get("userId"));
        assertEquals(new Document("$ne", true), q.get("deleted"));
        assertEquals(new Document("$gt", 0), q.get("playCount"));
        Document last = (Document) q.get("lastPlayedAt");
        assertNotNull(last, "lastPlayedAt cutoff must be set");
        Instant cutoff = (Instant) last.get("$lt");
        // Default 180-day threshold → cutoff is 180 days before NOW.
        assertEquals(NOW.minusSeconds(180L * 86400), cutoff);
    }

    @Test
    void forgottenHonorsCustomDaysOverride() {
        when(mongoTemplate.count(any(Query.class), eq(MusicFile.class))).thenReturn(0L);
        when(mongoTemplate.find(any(Query.class), eq(MusicFile.class))).thenReturn(List.of());

        service.findForgotten("u1", 30, PageRequest.of(0, 20));

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).count(captor.capture(), eq(MusicFile.class));
        Document q = captor.getValue().getQueryObject();
        Instant cutoff = (Instant) ((Document) q.get("lastPlayedAt")).get("$lt");
        assertEquals(NOW.minusSeconds(30L * 86400), cutoff);
    }

    // ── Neglected favorites ───────────────────────────────────

    @Test
    void neglectedFavoritesMatchesHighRatingAndNeverOrOld() {
        MusicFile a = MusicFile.builder().id("a").userId("u1").rating(5).build();
        MusicFile b = MusicFile.builder().id("b").userId("u1").rating(4).build();
        when(mongoTemplate.count(any(Query.class), eq(MusicFile.class))).thenReturn(2L);
        when(mongoTemplate.find(any(Query.class), eq(MusicFile.class))).thenReturn(List.of(a, b));

        Page<MusicFileDTO> page = service.findNeglectedFavorites("u1", null, null, PageRequest.of(0, 20));

        assertEquals(2, page.getTotalElements());
        assertEquals("a", page.getContent().get(0).getId());

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).count(captor.capture(), eq(MusicFile.class));
        Document q = captor.getValue().getQueryObject();
        // Top-level $and with base + or-clause; base enforces rating >= 4.
        List<Document> andClauses = (List<Document>) q.get("$and");
        assertNotNull(andClauses, "Criteria should combine base and or-clause via $and");
        Document base = andClauses.get(0);
        assertEquals("u1", base.get("userId"));
        assertEquals(new Document("$gte", 4), base.get("rating"));
        Document orWrapper = andClauses.get(1);
        List<Document> orClauses = (List<Document>) orWrapper.get("$or");
        assertNotNull(orClauses);
        assertEquals(3, orClauses.size(), "Should match missing, null, or old lastPlayedAt");
    }

    @Test
    void neglectedFavoritesAcceptsOverrides() {
        when(mongoTemplate.count(any(Query.class), eq(MusicFile.class))).thenReturn(0L);
        when(mongoTemplate.find(any(Query.class), eq(MusicFile.class))).thenReturn(List.of());

        service.findNeglectedFavorites("u1", 5, 365, PageRequest.of(0, 20));

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).count(captor.capture(), eq(MusicFile.class));
        Document q = captor.getValue().getQueryObject();
        List<Document> andClauses = (List<Document>) q.get("$and");
        Document base = andClauses.get(0);
        assertEquals(new Document("$gte", 5), base.get("rating"));
        Document orWrapper = andClauses.get(1);
        List<Document> orClauses = (List<Document>) orWrapper.get("$or");
        Instant cutoff = (Instant) ((Document) orClauses.get(2).get("lastPlayedAt")).get("$lt");
        assertEquals(NOW.minusSeconds(365L * 86400), cutoff);
    }

    // ── One-hit wonders ───────────────────────────────────────

    @Test
    void oneHitWondersReturnsUnplayedTracksForQualifyingArtists() {
        @SuppressWarnings("unchecked")
        AggregationResults<Document> agg = mock(AggregationResults.class);
        when(agg.getMappedResults()).thenReturn(List.of(
                new Document("_id", "Metallica").append("total", 12).append("played", 1),
                new Document("_id", "AC/DC").append("total", 5).append("played", 1)));
        when(mongoTemplate.aggregate(any(Aggregation.class), eq("music_files"), eq(Document.class))).thenReturn(agg);

        MusicFile m1 = MusicFile.builder().id("m1").artist("Metallica").title("One").build();
        MusicFile m2 = MusicFile.builder().id("m2").artist("Metallica").title("Two").build();
        MusicFile a1 = MusicFile.builder().id("a1").artist("AC/DC").title("Highway").build();
        // Each artist's follow-up `find` returns its own unplayed tracks.
        when(mongoTemplate.find(any(Query.class), eq(MusicFile.class)))
                .thenReturn(List.of(m1, m2))
                .thenReturn(List.of(a1));

        List<RediscoveryService.OneHitWonder> out = service.findOneHitWonders("u1", null, null);

        assertEquals(2, out.size());
        assertEquals("Metallica", out.get(0).artist());
        assertEquals(12, out.get(0).totalTracks());
        assertEquals(1, out.get(0).playedTracks());
        assertEquals(2, out.get(0).unplayed().size());
        assertEquals("AC/DC", out.get(1).artist());
        assertEquals(1, out.get(1).unplayed().size());
    }

    @Test
    void oneHitWondersIgnoresBlankArtistIds() {
        @SuppressWarnings("unchecked")
        AggregationResults<Document> agg = mock(AggregationResults.class);
        when(agg.getMappedResults()).thenReturn(List.of(
                new Document("_id", "").append("total", 10).append("played", 1),
                new Document("_id", null).append("total", 5).append("played", 1)));
        when(mongoTemplate.aggregate(any(Aggregation.class), eq("music_files"), eq(Document.class))).thenReturn(agg);

        List<RediscoveryService.OneHitWonder> out = service.findOneHitWonders("u1", null, null);

        assertTrue(out.isEmpty(), "Blank/null artist keys must be dropped before the per-artist find");
        // And the per-artist find should never have been issued.
        verify(mongoTemplate, never()).find(any(Query.class), eq(MusicFile.class));
    }

    @Test
    void oneHitWondersCapsLimitArtists() {
        @SuppressWarnings("unchecked")
        AggregationResults<Document> agg = mock(AggregationResults.class);
        when(agg.getMappedResults()).thenReturn(List.of());
        when(mongoTemplate.aggregate(any(Aggregation.class), eq("music_files"), eq(Document.class))).thenReturn(agg);

        // A caller-provided limit of 999 must be clamped below 50 in the pipeline.
        service.findOneHitWonders("u1", null, 999);

        ArgumentCaptor<Aggregation> captor = ArgumentCaptor.forClass(Aggregation.class);
        verify(mongoTemplate).aggregate(captor.capture(), eq("music_files"), eq(Document.class));
        String json = captor.getValue().toString();
        assertTrue(json.contains("\"$limit\" : 50"), "Should clamp limitArtists to 50; got: " + json);
    }
}
