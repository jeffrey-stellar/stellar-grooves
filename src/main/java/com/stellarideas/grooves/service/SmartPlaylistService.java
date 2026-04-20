package com.stellarideas.grooves.service;

import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.Playlist;
import com.stellarideas.grooves.model.SmartPlaylist;
import com.stellarideas.grooves.repository.PlaylistRepository;
import com.stellarideas.grooves.repository.SmartPlaylistRepository;
import com.stellarideas.grooves.smartplaylist.ParsedQuery;
import com.stellarideas.grooves.smartplaylist.SmartPlaylistQueryParser;
import com.stellarideas.grooves.smartplaylist.SmartPlaylistQueryTranslator;
import com.stellarideas.grooves.smartplaylist.SortSpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class SmartPlaylistService {

    private final SmartPlaylistRepository repository;
    private final PlaylistRepository playlistRepository;
    private final SmartPlaylistQueryParser parser;
    private final SmartPlaylistQueryTranslator translator;
    private final MongoTemplate mongoTemplate;

    @Value("${stellar.grooves.smartPlaylist.materializeMax:5000}")
    private int materializeMax;

    public SmartPlaylistService(SmartPlaylistRepository repository,
                                PlaylistRepository playlistRepository,
                                SmartPlaylistQueryParser parser,
                                SmartPlaylistQueryTranslator translator,
                                MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.playlistRepository = playlistRepository;
        this.parser = parser;
        this.translator = translator;
        this.mongoTemplate = mongoTemplate;
    }

    public List<SmartPlaylist> list(String userId) {
        return repository.findByUserIdOrderByNameAsc(userId);
    }

    public Optional<SmartPlaylist> findByIdAndUserId(String id, String userId) {
        return repository.findByIdAndUserId(id, userId);
    }

    public SmartPlaylist create(String userId, String name, String queryString) {
        parser.parse(queryString); // validate before persisting
        if (repository.existsByUserIdAndName(userId, name)) {
            throw new IllegalArgumentException("A smart playlist named '" + name + "' already exists");
        }
        SmartPlaylist sp = new SmartPlaylist();
        sp.setUserId(userId);
        sp.setName(name);
        sp.setQueryString(queryString);
        return repository.save(sp);
    }

    public SmartPlaylist update(SmartPlaylist existing, String name, String queryString) {
        parser.parse(queryString); // validate
        if (!existing.getName().equals(name) && repository.existsByUserIdAndName(existing.getUserId(), name)) {
            throw new IllegalArgumentException("A smart playlist named '" + name + "' already exists");
        }
        existing.setName(name);
        existing.setQueryString(queryString);
        return repository.save(existing);
    }

    public void delete(SmartPlaylist playlist) {
        repository.delete(playlist);
    }

    /** Execute a saved smart playlist and return paginated tracks. */
    public Page<MusicFile> preview(SmartPlaylist playlist, int page, int size) {
        return execute(playlist.getUserId(), playlist.getQueryString(), page, size);
    }

    /** Dry-run: parse and execute a query without persisting. */
    public Page<MusicFile> execute(String userId, String queryString, int page, int size) {
        ParsedQuery parsed = parser.parse(queryString);
        Criteria criteria = translator.translate(parsed.expression(), userId);

        if (parsed.sort().map(SortSpec::isRandom).orElse(false)) {
            int sampleSize = parsed.limit().orElseThrow();
            List<MusicFile> sampled = sampleRandom(criteria, sampleSize);
            return new PageImpl<>(sampled, PageRequest.of(0, Math.max(size, 1)), sampled.size());
        }

        Query query = new Query(criteria).with(sortFor(parsed.sort()));
        long rawTotal = mongoTemplate.count(query, MusicFile.class);
        long effectiveTotal = parsed.limit().isPresent()
                ? Math.min(rawTotal, parsed.limit().getAsInt())
                : rawTotal;

        int pageStart = page * size;
        int pageEndExclusive = (int) Math.min((long) pageStart + size, effectiveTotal);
        int pageSize = Math.max(0, pageEndExclusive - pageStart);

        List<MusicFile> content = pageSize == 0
                ? List.of()
                : mongoTemplate.find(query.skip(pageStart).limit(pageSize), MusicFile.class);

        return new PageImpl<>(content, PageRequest.of(page, size), effectiveTotal);
    }

    /** Count matches for a saved smart playlist. */
    public long count(SmartPlaylist playlist) {
        return count(playlist.getUserId(), playlist.getQueryString());
    }

    /** Count matches for an ad-hoc query (dry-run). Applies the query's limit when present. */
    public long count(String userId, String queryString) {
        ParsedQuery parsed = parser.parse(queryString);
        Criteria criteria = translator.translate(parsed.expression(), userId);
        long raw = mongoTemplate.count(new Query(criteria), MusicFile.class);
        return parsed.limit().isPresent() ? Math.min(raw, parsed.limit().getAsInt()) : raw;
    }

    /**
     * Snapshot a smart playlist's current results into a new static {@link Playlist}.
     * Result size is bounded by the query's {@code limit:} (if present) and always
     * capped by {@code stellar.grooves.smartPlaylist.materializeMax} (default 5000).
     * {@code sort:} determines track order; {@code sort:random} uses {@code $sample}.
     */
    public MaterializeResult materialize(SmartPlaylist source, String name) {
        ParsedQuery parsed = parser.parse(source.getQueryString());
        Criteria criteria = translator.translate(parsed.expression(), source.getUserId());

        int userLimit = parsed.limit().orElse(Integer.MAX_VALUE);
        int effectiveCap = Math.min(userLimit, materializeMax);

        List<String> trackIds;
        if (parsed.sort().map(SortSpec::isRandom).orElse(false)) {
            trackIds = sampleRandom(criteria, effectiveCap).stream()
                    .map(MusicFile::getId)
                    .toList();
        } else {
            Query query = new Query(criteria).with(sortFor(parsed.sort())).limit(effectiveCap);
            trackIds = mongoTemplate.find(query, MusicFile.class).stream()
                    .map(MusicFile::getId)
                    .toList();
        }

        Playlist playlist = new Playlist();
        playlist.setUserId(source.getUserId());
        playlist.setName(name.trim());
        playlist.setTrackIds(new ArrayList<>(trackIds));
        Playlist saved = playlistRepository.save(playlist);

        // Truncated only when the server-side hard cap clipped results the user did not ask us to clip.
        boolean truncated = trackIds.size() >= materializeMax
                && (parsed.limit().isEmpty() || userLimit > materializeMax);
        return new MaterializeResult(saved, trackIds.size(), truncated);
    }

    private List<MusicFile> sampleRandom(Criteria criteria, int size) {
        List<AggregationOperation> stages = List.of(
                Aggregation.match(criteria),
                Aggregation.sample(size));
        AggregationResults<MusicFile> results =
                mongoTemplate.aggregate(Aggregation.newAggregation(stages), MusicFile.class, MusicFile.class);
        return results.getMappedResults();
    }

    private static Sort sortFor(Optional<SortSpec> spec) {
        if (spec.isEmpty()) {
            return Sort.by(Sort.Direction.ASC, "artist", "album", "title");
        }
        SortSpec s = spec.get();
        Sort.Direction dir = s.direction() == SortSpec.Direction.DESC
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
        String field = mongoFieldFor(s.field());
        return Sort.by(dir, field);
    }

    private static String mongoFieldFor(SortSpec.Field field) {
        return switch (field) {
            case RATING      -> "rating";
            case YEAR        -> "year";
            case PLAY_COUNT  -> "playCount";
            case LAST_PLAYED -> "lastPlayedAt";
            case ARTIST      -> "artist";
            case ALBUM       -> "album";
            case TITLE       -> "title";
            case RANDOM -> throw new IllegalStateException("RANDOM has no Mongo field");
        };
    }

    public record MaterializeResult(Playlist playlist, int trackCount, boolean truncated) {}
}
