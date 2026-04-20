package com.stellarideas.grooves.service;

import com.stellarideas.grooves.dto.MusicFileDTO;
import com.stellarideas.grooves.model.MusicFile;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Curator surfaces that turn the existing {@link MusicFile} metadata
 * (rating, playCount, lastPlayedAt) into "rediscovery" prompts:
 * forgotten tracks, neglected favorites, one-hit-wonder artists.
 */
@Service
public class RediscoveryService {

    private final MongoTemplate mongoTemplate;
    private final Clock clock;
    private final int defaultForgottenDays;
    private final int defaultNeglectedDays;
    private final int defaultMinFavorite;
    private final int defaultMinCatalog;

    @Autowired
    public RediscoveryService(MongoTemplate mongoTemplate,
            @Value("${stellar.grooves.rediscovery.forgottenDays:180}") int forgottenDays,
            @Value("${stellar.grooves.rediscovery.neglectedDays:90}") int neglectedDays,
            @Value("${stellar.grooves.rediscovery.minFavoriteRating:4}") int minFavorite,
            @Value("${stellar.grooves.rediscovery.oneHitWonderMinCatalog:3}") int minCatalog) {
        this(mongoTemplate, Clock.systemUTC(), forgottenDays, neglectedDays, minFavorite, minCatalog);
    }

    RediscoveryService(MongoTemplate mongoTemplate, Clock clock,
                       int forgottenDays, int neglectedDays, int minFavorite, int minCatalog) {
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
        this.defaultForgottenDays = forgottenDays;
        this.defaultNeglectedDays = neglectedDays;
        this.defaultMinFavorite = minFavorite;
        this.defaultMinCatalog = minCatalog;
    }

    /**
     * Tracks played at least once but not in the last {@code days} days
     * (default: {@code stellar.grooves.rediscovery.forgottenDays}, 180).
     * Longest-forgotten first.
     */
    public Page<MusicFileDTO> findForgotten(String userId, Integer days, Pageable pageable) {
        int threshold = days != null && days > 0 ? days : defaultForgottenDays;
        Instant cutoff = Instant.now(clock).minus(Duration.ofDays(threshold));
        Criteria c = Criteria.where("userId").is(userId)
                .and("deleted").ne(true)
                .and("playCount").gt(0)
                .and("lastPlayedAt").lt(cutoff);
        Query q = new Query(c).with(Sort.by(Sort.Direction.ASC, "lastPlayedAt"));
        long total = mongoTemplate.count(q, MusicFile.class);
        q.with(pageable);
        List<MusicFile> content = mongoTemplate.find(q, MusicFile.class);
        return new PageImpl<>(content.stream().map(MusicFileDTO::from).collect(Collectors.toList()),
                pageable, total);
    }

    /**
     * Tracks rated {@code minRating}+ (default 4) that have either never been played
     * or weren't played in the last {@code days} days (default 90).
     * Highest rating first, then longest-untouched.
     */
    public Page<MusicFileDTO> findNeglectedFavorites(String userId, Integer minRating, Integer days, Pageable pageable) {
        int r = minRating != null && minRating > 0 ? minRating : defaultMinFavorite;
        int d = days != null && days > 0 ? days : defaultNeglectedDays;
        Instant cutoff = Instant.now(clock).minus(Duration.ofDays(d));

        Criteria base = Criteria.where("userId").is(userId)
                .and("deleted").ne(true)
                .and("rating").gte(r);
        Criteria neverOrOld = new Criteria().orOperator(
                Criteria.where("lastPlayedAt").exists(false),
                Criteria.where("lastPlayedAt").is(null),
                Criteria.where("lastPlayedAt").lt(cutoff));
        Criteria combined = new Criteria().andOperator(base, neverOrOld);

        Query q = new Query(combined).with(Sort.by(
                Sort.Order.desc("rating"),
                Sort.Order.asc("lastPlayedAt")));
        long total = mongoTemplate.count(q, MusicFile.class);
        q.with(pageable);
        List<MusicFile> content = mongoTemplate.find(q, MusicFile.class);
        return new PageImpl<>(content.stream().map(MusicFileDTO::from).collect(Collectors.toList()),
                pageable, total);
    }

    /**
     * "Untapped catalog": surface unplayed tracks by artists where the user has played
     * exactly one track despite owning {@code minCatalog}+ tracks by that artist.
     * Returns each artist's unplayed tracks plus summary counts.
     */
    public List<OneHitWonder> findOneHitWonders(String userId, Integer minCatalog, Integer limitArtists) {
        int min = minCatalog != null && minCatalog > 0 ? minCatalog : defaultMinCatalog;
        int cap = limitArtists != null && limitArtists > 0 ? Math.min(limitArtists, 50) : 20;

        // Step 1: aggregate by artist — total tracks and how many have been played.
        AggregationOperation group = context -> new Document("$group",
                new Document("_id", "$artist")
                        .append("total", new Document("$sum", 1))
                        .append("played", new Document("$sum",
                                new Document("$cond", List.of(
                                        new Document("$gt", List.of("$playCount", 0)),
                                        1, 0)))));
        AggregationOperation matchHits = context -> new Document("$match",
                new Document("played", 1).append("total", new Document("$gte", min)));
        AggregationOperation sort = context -> new Document("$sort", new Document("total", -1));
        AggregationOperation limitOp = context -> new Document("$limit", cap);

        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("userId").is(userId)
                        .and("deleted").ne(true)
                        .and("artist").ne(null).ne("")),
                group, matchHits, sort, limitOp);
        AggregationResults<Document> results = mongoTemplate.aggregate(agg, "music_files", Document.class);

        List<OneHitWonder> out = new ArrayList<>();
        for (Document d : results.getMappedResults()) {
            String artist = d.getString("_id");
            if (artist == null || artist.isBlank()) continue;
            int total = d.getInteger("total", 0);

            // Step 2: fetch the unplayed tracks for this artist (cap at 10 for UI density).
            Criteria c = Criteria.where("userId").is(userId)
                    .and("deleted").ne(true)
                    .and("artist").is(artist);
            Criteria notPlayed = new Criteria().orOperator(
                    Criteria.where("playCount").exists(false),
                    Criteria.where("playCount").is(0));
            Query q = new Query(new Criteria().andOperator(c, notPlayed))
                    .with(Sort.by(Sort.Order.desc("rating"), Sort.Order.asc("album"), Sort.Order.asc("title")))
                    .limit(10);
            List<MusicFileDTO> unplayed = mongoTemplate.find(q, MusicFile.class).stream()
                    .map(MusicFileDTO::from).collect(Collectors.toList());
            out.add(new OneHitWonder(artist, total, 1, unplayed));
        }
        return out;
    }

    /** Per-artist "untapped catalog" payload. */
    public record OneHitWonder(String artist, int totalTracks, int playedTracks, List<MusicFileDTO> unplayed) {}
}
