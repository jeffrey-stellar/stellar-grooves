package com.stellarideas.grooves.repository;

import com.stellarideas.grooves.dto.MusicFileDTO;
import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.data.mongodb.core.query.TextQuery;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class MusicFileRepositoryCustomImpl implements MusicFileRepositoryCustom {

    private static final int PATTERN_CACHE_MAX_SIZE = 128;

    /** LRU cache for Pattern.quote() results to avoid recomputation on repeated searches. */
    @SuppressWarnings("serial")
    private final Map<String, String> quotedPatternCache = Collections.synchronizedMap(
            new LinkedHashMap<>(PATTERN_CACHE_MAX_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > PATTERN_CACHE_MAX_SIZE;
                }
            });

    private final MongoTemplate mongoTemplate;

    public MusicFileRepositoryCustomImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    String quotePattern(String input) {
        return quotedPatternCache.computeIfAbsent(input, java.util.regex.Pattern::quote);
    }

    @Override
    public List<Map<String, Object>> findDuplicatesByUserId(String userId) {
        // Use raw BSON stages for $toLower which has no clean Spring Data wrapper for group keys
        AggregationOperation groupStage = context -> new Document("$group",
                new Document("_id", new Document("titleLower", new Document("$toLower", "$title"))
                        .append("artistLower", new Document("$toLower", "$artist")))
                        .append("count", new Document("$sum", 1))
                        .append("title", new Document("$first", "$title"))
                        .append("artist", new Document("$first", "$artist"))
                        .append("files", new Document("$push", "$$ROOT")));

        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("userId").is(userId)
                        .and("title").ne(null).ne("")
                        .and("artist").ne(null).ne("")),
                groupStage,
                Aggregation.match(Criteria.where("count").gt(1)),
                Aggregation.limit(100)
        );

        List<Document> results = mongoTemplate.aggregate(
                aggregation, "music_files", Document.class
        ).getMappedResults();

        return mapDuplicateResults(results);
    }

    @Override
    public Map<String, Object> findDuplicatesByUserId(String userId, int skip, int limit) {
        AggregationOperation groupStage = context -> new Document("$group",
                new Document("_id", new Document("titleLower", new Document("$toLower", "$title"))
                        .append("artistLower", new Document("$toLower", "$artist")))
                        .append("count", new Document("$sum", 1))
                        .append("title", new Document("$first", "$title"))
                        .append("artist", new Document("$first", "$artist"))
                        .append("files", new Document("$push", "$$ROOT")));

        // Count total duplicate groups first
        Aggregation countAgg = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("userId").is(userId)
                        .and("title").ne(null).ne("")
                        .and("artist").ne(null).ne("")),
                groupStage,
                Aggregation.match(Criteria.where("count").gt(1)),
                Aggregation.count().as("total")
        );
        List<Document> countResult = mongoTemplate.aggregate(countAgg, "music_files", Document.class).getMappedResults();
        long total = countResult.isEmpty() ? 0 : countResult.get(0).getInteger("total", 0);

        // Fetch the paginated slice
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("userId").is(userId)
                        .and("title").ne(null).ne("")
                        .and("artist").ne(null).ne("")),
                groupStage,
                Aggregation.match(Criteria.where("count").gt(1)),
                Aggregation.sort(org.springframework.data.domain.Sort.Direction.DESC, "count"),
                Aggregation.skip((long) skip),
                Aggregation.limit(limit)
        );

        List<Document> results = mongoTemplate.aggregate(
                aggregation, "music_files", Document.class
        ).getMappedResults();

        List<Map<String, Object>> duplicates = mapDuplicateResults(results);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("duplicates", duplicates);
        response.put("total", total);
        response.put("page", skip / Math.max(limit, 1));
        response.put("size", limit);
        return response;
    }

    private List<Map<String, Object>> mapDuplicateResults(List<Document> results) {
        List<Map<String, Object>> duplicates = new ArrayList<>();
        for (Document doc : results) {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("title", doc.getString("title"));
            group.put("artist", doc.getString("artist"));

            List<Document> fileDocs = doc.getList("files", Document.class);
            List<MusicFileDTO> dtos = new ArrayList<>();
            if (fileDocs != null) {
                for (Document fd : fileDocs) {
                    MusicFile mf = mongoTemplate.getConverter().read(MusicFile.class, fd);
                    dtos.add(MusicFileDTO.from(mf));
                }
            }
            group.put("files", dtos);
            duplicates.add(group);
        }
        return duplicates;
    }

    @Override
    public Map<String, Object> findHashDuplicatesByUserId(String userId, int skip, int limit) {
        AggregationOperation groupStage = context -> new Document("$group",
                new Document("_id", "$fileHash")
                        .append("count", new Document("$sum", 1))
                        .append("files", new Document("$push", "$$ROOT")));

        // Count total hash-duplicate groups
        Aggregation countAgg = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("userId").is(userId)
                        .and("deleted").ne(true)
                        .and("fileHash").ne(null)),
                groupStage,
                Aggregation.match(Criteria.where("count").gt(1)),
                Aggregation.count().as("total")
        );
        List<Document> countResult = mongoTemplate.aggregate(countAgg, "music_files", Document.class).getMappedResults();
        long total = countResult.isEmpty() ? 0 : countResult.get(0).getInteger("total", 0);

        // Fetch the paginated slice
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("userId").is(userId)
                        .and("deleted").ne(true)
                        .and("fileHash").ne(null)),
                groupStage,
                Aggregation.match(Criteria.where("count").gt(1)),
                Aggregation.sort(org.springframework.data.domain.Sort.Direction.DESC, "count"),
                Aggregation.skip((long) skip),
                Aggregation.limit(limit)
        );

        List<Document> results = mongoTemplate.aggregate(
                aggregation, "music_files", Document.class
        ).getMappedResults();

        List<Map<String, Object>> duplicates = new ArrayList<>();
        for (Document doc : results) {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("fileHash", doc.getString("_id"));
            List<Document> fileDocs = doc.getList("files", Document.class);
            List<MusicFileDTO> dtos = new ArrayList<>();
            if (fileDocs != null) {
                for (Document fd : fileDocs) {
                    MusicFile mf = mongoTemplate.getConverter().read(MusicFile.class, fd);
                    dtos.add(MusicFileDTO.from(mf));
                }
            }
            group.put("files", dtos);
            duplicates.add(group);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("duplicates", duplicates);
        response.put("total", total);
        response.put("page", skip / Math.max(limit, 1));
        response.put("size", limit);
        return response;
    }

    @Override
    public Page<MusicFile> textSearch(String userId, String query, Pageable pageable) {
        TextCriteria textCriteria = TextCriteria.forDefaultLanguage().matchingPhrase(query);
        Query q = TextQuery.queryText(textCriteria).sortByScore();
        q.addCriteria(Criteria.where("userId").is(userId).and("deleted").ne(true));

        long total = mongoTemplate.count(q, MusicFile.class);
        q.with(pageable);
        List<MusicFile> results = mongoTemplate.find(q, MusicFile.class);
        return new PageImpl<>(results, pageable, total);
    }

    @Override
    public Page<MusicFile> filteredSearch(String userId, String query,
                                          Genre genre, String artist, String year, String fileExtension,
                                          Pageable pageable) {
        Criteria base = Criteria.where("userId").is(userId).and("deleted").ne(true);
        if (genre != null) {
            base = base.and("genre").is(genre);
        }
        if (artist != null && !artist.isBlank()) {
            base = base.and("artist").regex(quotePattern(artist), "i");
        }
        if (year != null && !year.isBlank()) {
            base = base.and("year").is(year);
        }
        if (fileExtension != null && !fileExtension.isBlank()) {
            String ext = fileExtension.startsWith(".") ? fileExtension : "." + fileExtension;
            base = base.and("fileName").regex(quotePattern(ext) + "$", "i");
        }

        Query q;
        if (query != null && !query.isBlank()) {
            String escaped = quotePattern(query);
            Criteria searchCriteria = new Criteria().orOperator(
                    Criteria.where("title").regex(escaped, "i"),
                    Criteria.where("artist").regex(escaped, "i"),
                    Criteria.where("album").regex(escaped, "i")
            );
            q = new Query(new Criteria().andOperator(base, searchCriteria));
        } else {
            q = new Query(base);
        }

        long total = mongoTemplate.count(q, MusicFile.class);
        q.with(pageable);
        List<MusicFile> results = mongoTemplate.find(q, MusicFile.class);
        return new PageImpl<>(results, pageable, total);
    }

    @Override
    public Map<String, Object> getStatistics(String userId) {
        Map<String, Object> stats = new LinkedHashMap<>();

        // Total tracks
        Criteria countCriteria = Criteria.where("userId").is(userId).and("deleted").ne(true);
        long total = mongoTemplate.count(
                org.springframework.data.mongodb.core.query.Query.query(countCriteria), "music_files");
        stats.put("totalTracks", total);

        // Genre distribution
        Aggregation genreAgg = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("userId").is(userId).and("deleted").ne(true)),
                Aggregation.group("genre").count().as("count"),
                Aggregation.sort(org.springframework.data.domain.Sort.Direction.DESC, "count")
        );
        List<Document> genreResults = mongoTemplate.aggregate(genreAgg, "music_files", Document.class).getMappedResults();
        List<Map<String, Object>> genreDist = new ArrayList<>();
        for (Document d : genreResults) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("genre", d.get("_id") != null ? d.get("_id").toString() : "OTHER");
            entry.put("count", d.getInteger("count", 0));
            genreDist.add(entry);
        }
        stats.put("genreDistribution", genreDist);

        // Top 10 artists
        Aggregation artistAgg = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("userId").is(userId).and("deleted").ne(true).and("artist").ne(null).ne("")),
                Aggregation.group("artist").count().as("count"),
                Aggregation.sort(org.springframework.data.domain.Sort.Direction.DESC, "count"),
                Aggregation.limit(10)
        );
        List<Document> artistResults = mongoTemplate.aggregate(artistAgg, "music_files", Document.class).getMappedResults();
        List<Map<String, Object>> topArtists = new ArrayList<>();
        for (Document d : artistResults) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("artist", d.getString("_id"));
            entry.put("count", d.getInteger("count", 0));
            topArtists.add(entry);
        }
        stats.put("topArtists", topArtists);
        // Total distinct artists
        long totalArtists = mongoTemplate.aggregate(Aggregation.newAggregation(
                Aggregation.match(Criteria.where("userId").is(userId).and("deleted").ne(true).and("artist").ne(null).ne("")),
                Aggregation.group("artist")
        ), "music_files", Document.class).getMappedResults().size();
        stats.put("totalArtists", totalArtists);

        // Total distinct albums
        long totalAlbums = mongoTemplate.aggregate(Aggregation.newAggregation(
                Aggregation.match(Criteria.where("userId").is(userId).and("deleted").ne(true).and("album").ne(null).ne("")),
                Aggregation.group("album")
        ), "music_files", Document.class).getMappedResults().size();
        stats.put("totalAlbums", totalAlbums);

        // Decade distribution (null/empty years already filtered in match stage)
        AggregationOperation decadeGroup = context -> new Document("$group",
                new Document("_id", new Document("$concat", List.of(
                        new Document("$substr", List.of("$year", 0, 3)),
                        "0s")))
                        .append("count", new Document("$sum", 1)));
        Aggregation decadeAgg = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("userId").is(userId).and("deleted").ne(true).and("year").ne(null).ne("")),
                decadeGroup,
                Aggregation.sort(org.springframework.data.domain.Sort.Direction.ASC, "_id")
        );
        List<Document> decadeResults = mongoTemplate.aggregate(decadeAgg, "music_files", Document.class).getMappedResults();
        List<Map<String, Object>> decadeDist = new ArrayList<>();
        for (Document d : decadeResults) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("decade", d.getString("_id"));
            entry.put("count", d.getInteger("count", 0));
            decadeDist.add(entry);
        }
        stats.put("decadeDistribution", decadeDist);

        // Average rating (excluding unrated)
        Aggregation ratingAgg = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("userId").is(userId).and("deleted").ne(true).and("rating").gt(0)),
                Aggregation.group().avg("rating").as("avgRating")
        );
        List<Document> ratingResults = mongoTemplate.aggregate(ratingAgg, "music_files", Document.class).getMappedResults();
        double avgRating = ratingResults.isEmpty() ? 0.0 : ratingResults.get(0).getDouble("avgRating");
        stats.put("averageRating", Math.round(avgRating * 100.0) / 100.0);

        return stats;
    }
}
