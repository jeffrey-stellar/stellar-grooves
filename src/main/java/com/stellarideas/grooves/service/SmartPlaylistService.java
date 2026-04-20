package com.stellarideas.grooves.service;

import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.Playlist;
import com.stellarideas.grooves.model.SmartPlaylist;
import com.stellarideas.grooves.repository.PlaylistRepository;
import com.stellarideas.grooves.repository.SmartPlaylistRepository;
import com.stellarideas.grooves.smartplaylist.QueryPredicate;
import com.stellarideas.grooves.smartplaylist.SmartPlaylistQueryParser;
import com.stellarideas.grooves.smartplaylist.SmartPlaylistQueryTranslator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

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
        List<QueryPredicate> predicates = parser.parse(queryString);
        Criteria criteria = translator.translate(predicates, userId);
        Query query = new Query(criteria)
                .with(Sort.by(Sort.Direction.ASC, "artist", "album", "title"));

        long total = mongoTemplate.count(query, MusicFile.class);
        query.with(PageRequest.of(page, size));
        List<MusicFile> content = mongoTemplate.find(query, MusicFile.class);
        return new PageImpl<>(content, PageRequest.of(page, size), total);
    }

    /** Count matches for a saved smart playlist. */
    public long count(SmartPlaylist playlist) {
        return count(playlist.getUserId(), playlist.getQueryString());
    }

    /** Count matches for an ad-hoc query (dry-run). */
    public long count(String userId, String queryString) {
        List<QueryPredicate> predicates = parser.parse(queryString);
        Criteria criteria = translator.translate(predicates, userId);
        return mongoTemplate.count(new Query(criteria), MusicFile.class);
    }

    /**
     * Snapshot a smart playlist's current results into a new static {@link Playlist}.
     * Capped at {@code stellar.grooves.smartPlaylist.materializeMax} tracks (default 5000).
     * Returns the new playlist along with how many tracks were actually written
     * (may be less than the match count if capped).
     */
    public MaterializeResult materialize(SmartPlaylist source, String name) {
        List<QueryPredicate> predicates = parser.parse(source.getQueryString());
        Criteria criteria = translator.translate(predicates, source.getUserId());
        Query query = new Query(criteria)
                .with(Sort.by(Sort.Direction.ASC, "artist", "album", "title"))
                .limit(materializeMax);

        List<String> trackIds = mongoTemplate.find(query, MusicFile.class).stream()
                .map(MusicFile::getId)
                .toList();

        Playlist playlist = new Playlist();
        playlist.setUserId(source.getUserId());
        playlist.setName(name.trim());
        playlist.setTrackIds(new java.util.ArrayList<>(trackIds));
        Playlist saved = playlistRepository.save(playlist);

        boolean truncated = trackIds.size() >= materializeMax;
        return new MaterializeResult(saved, trackIds.size(), truncated);
    }

    public record MaterializeResult(Playlist playlist, int trackCount, boolean truncated) {}
}
