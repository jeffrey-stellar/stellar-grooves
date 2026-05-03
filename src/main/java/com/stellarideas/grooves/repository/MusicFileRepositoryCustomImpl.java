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

    /** Projection pushed per file in duplicate groups — avoids $$ROOT memory bloat. */
    private static Document duplicateFileProjection() {
        return new Document("_id", "$_id")
                .append("title", "$title")
                .append("artist", "$artist")
                .append("filePath", "$filePath")
                .append("fileName", "$fileName")
                .append("format", "$format")
                .append("fileHash", "$fileHash")
                .append("genre", "$genre")
                .append("rating", "$rating")
                .append("year", "$year")
                .append("album", "$album")
                .append("hasCoverArt", "$hasCoverArt")
                .append("userId", "$userId");
    }

    @Override
    public List<Map<String, Object>> findDuplicatesByUserId(String userId) {
        AggregationOperation groupStage = context -> new Document("$group",
                new Document("_id", new Document("titleLower", new Document("$toLower", "$title"))
                        .append("artistLower", new Document("$toLower", "$artist")))
                        .append("count", new Document("$sum", 1))
                        .append("title", new Document("$first", "$title"))
                        .append("artist", new Document("$first", "$artist"))
                        .append("files", new Document("$push", duplicateFileProjection())));

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
                        .append("files", new Document("$push", duplicateFileProjection())));

        AggregationOperation matchDuplicates = context -> new Document("$match",
                new Document("count", new Document("$gt", 1)));

        // Single $facet pipeline for both count and paginated results
        AggregationOperation facetStage = context -> new Document("$facet",
                new Document("metadata", List.of(new Document("$count", "total")))
                        .append("data", List.of(
                                new Document("$sort", new Document("count", -1)),
                                new Document("$skip", skip),
                                new Document("$limit", limit))));

        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("userId").is(userId)
                        .and("title").ne(null).ne("")
                        .and("artist").ne(null).ne("")),
                groupStage,
                matchDuplicates,
                facetStage
        );

        List<Document> facetResults = mongoTemplate.aggregate(
                aggregation, "music_files", Document.class
        ).getMappedResults();

        long total = 0;
        List<Document> data = List.of();
        if (!facetResults.isEmpty()) {
            Document facet = facetResults.get(0);
            List<Document> metadata = facet.getList("metadata", Document.class);
            if (metadata != null && !metadata.isEmpty()) {
                total = metadata.get(0).getInteger("total", 0);
            }
            data = facet.getList("data", Document.class);
            if (data == null) data = List.of();
        }

        List<Map<String, Object>> duplicates = mapDuplicateResults(data);

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
                        .append("files", new Document("$push", duplicateFileProjection())));

        AggregationOperation matchDuplicates = context -> new Document("$match",
                new Document("count", new Document("$gt", 1)));

        // Single $facet pipeline for both count and paginated results
        AggregationOperation facetStage = context -> new Document("$facet",
                new Document("metadata", List.of(new Document("$count", "total")))
                        .append("data", List.of(
                                new Document("$sort", new Document("count", -1)),
                                new Document("$skip", skip),
                                new Document("$limit", limit))));

        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("userId").is(userId)
                        .and("deleted").ne(true)
                        .and("fileHash").ne(null)),
                groupStage,
                matchDuplicates,
                facetStage
        );

        List<Document> facetResults = mongoTemplate.aggregate(
                aggregation, "music_files", Document.class
        ).getMappedResults();

        long total = 0;
        List<Document> data = List.of();
        if (!facetResults.isEmpty()) {
            Document facet = facetResults.get(0);
            List<Document> metadata = facet.getList("metadata", Document.class);
            if (metadata != null && !metadata.isEmpty()) {
                total = metadata.get(0).getInteger("total", 0);
            }
            data = facet.getList("data", Document.class);
            if (data == null) data = List.of();
        }

        List<Map<String, Object>> duplicates = new ArrayList<>();
        for (Document doc : data) {
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
            Integer yearInt = com.stellarideas.grooves.util.YearParser.parse(year);
            if (yearInt != null) {
                base = base.and("year").is(yearInt);
            }
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
        // Single $facet pipeline: one match stage feeds seven parallel branches.
        // Replaced six sequential aggregations + a count() round-trip with one server hit.
        AggregationOperation facet = context -> new Document("$facet", new Document()
                .append("totalTracks", List.of(new Document("$count", "n")))
                .append("genres", List.of(
                        new Document("$group", new Document("_id", "$genre")
                                .append("count", new Document("$sum", 1))),
                        new Document("$sort", new Document("count", -1))))
                .append("topArtists", List.of(
                        new Document("$match", new Document("artist",
                                new Document("$nin", List.of("", null)))),
                        new Document("$group", new Document("_id", "$artist")
                                .append("count", new Document("$sum", 1))),
                        new Document("$sort", new Document("count", -1)),
                        new Document("$limit", 10)))
                .append("totalArtists", List.of(
                        new Document("$match", new Document("artist",
                                new Document("$nin", List.of("", null)))),
                        new Document("$group", new Document("_id", "$artist")),
                        new Document("$count", "n")))
                .append("totalAlbums", List.of(
                        new Document("$match", new Document("album",
                                new Document("$nin", List.of("", null)))),
                        new Document("$group", new Document("_id", "$album")),
                        new Document("$count", "n")))
                .append("decades", List.of(
                        new Document("$match", new Document("year", new Document("$ne", null))),
                        new Document("$group", new Document("_id",
                                new Document("$concat", List.of(
                                        new Document("$toString", new Document("$subtract",
                                                List.of("$year",
                                                        new Document("$mod", List.of("$year", 10))))),
                                        "s")))
                                .append("count", new Document("$sum", 1))),
                        new Document("$sort", new Document("_id", 1))))
                .append("ratingAvg", List.of(
                        new Document("$match", new Document("rating", new Document("$gt", 0))),
                        new Document("$group", new Document("_id", null)
                                .append("avg", new Document("$avg", "$rating"))))));

        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("userId").is(userId).and("deleted").ne(true)),
                facet);

        List<Document> facetResults = mongoTemplate.aggregate(
                aggregation, "music_files", Document.class).getMappedResults();

        Map<String, Object> stats = new LinkedHashMap<>();
        if (facetResults.isEmpty()) {
            return emptyStats();
        }
        Document facetDoc = facetResults.get(0);

        stats.put("totalTracks", scalarFromBranch(facetDoc, "totalTracks", "n"));

        List<Map<String, Object>> genreDist = new ArrayList<>();
        for (Document d : safeBranchList(facetDoc, "genres")) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("genre", d.get("_id") != null ? d.get("_id").toString() : "OTHER");
            entry.put("count", d.getInteger("count", 0));
            genreDist.add(entry);
        }
        stats.put("genreDistribution", genreDist);

        List<Map<String, Object>> topArtists = new ArrayList<>();
        for (Document d : safeBranchList(facetDoc, "topArtists")) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("artist", d.getString("_id"));
            entry.put("count", d.getInteger("count", 0));
            topArtists.add(entry);
        }
        stats.put("topArtists", topArtists);

        stats.put("totalArtists", scalarFromBranch(facetDoc, "totalArtists", "n"));
        stats.put("totalAlbums", scalarFromBranch(facetDoc, "totalAlbums", "n"));

        List<Map<String, Object>> decadeDist = new ArrayList<>();
        for (Document d : safeBranchList(facetDoc, "decades")) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("decade", d.getString("_id"));
            entry.put("count", d.getInteger("count", 0));
            decadeDist.add(entry);
        }
        stats.put("decadeDistribution", decadeDist);

        List<Document> ratingBranch = safeBranchList(facetDoc, "ratingAvg");
        double avgRating = 0.0;
        if (!ratingBranch.isEmpty()) {
            Object avg = ratingBranch.get(0).get("avg");
            if (avg instanceof Number n) avgRating = n.doubleValue();
        }
        stats.put("averageRating", Math.round(avgRating * 100.0) / 100.0);

        return stats;
    }

    private static List<Document> safeBranchList(Document facetDoc, String branch) {
        List<Document> list = facetDoc.getList(branch, Document.class);
        return list != null ? list : List.of();
    }

    /** Reads a single-document scalar from a $facet branch (e.g. {@code [{ n: 42 }]}). */
    private static long scalarFromBranch(Document facetDoc, String branch, String field) {
        List<Document> list = safeBranchList(facetDoc, branch);
        if (list.isEmpty()) return 0L;
        Object v = list.get(0).get(field);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static Map<String, Object> emptyStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalTracks", 0L);
        stats.put("genreDistribution", List.of());
        stats.put("topArtists", List.of());
        stats.put("totalArtists", 0L);
        stats.put("totalAlbums", 0L);
        stats.put("decadeDistribution", List.of());
        stats.put("averageRating", 0.0);
        return stats;
    }
}
