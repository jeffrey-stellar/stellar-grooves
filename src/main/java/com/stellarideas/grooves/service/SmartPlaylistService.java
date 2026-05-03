package com.stellarideas.grooves.service;

import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.Playlist;
import com.stellarideas.grooves.model.SmartPlaylist;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.PlaylistRepository;
import com.stellarideas.grooves.repository.SmartPlaylistRepository;
import com.stellarideas.grooves.repository.UserRepository;
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
import org.springframework.data.mongodb.core.aggregation.AggregationOptions;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Service
public class SmartPlaylistService {

    private final SmartPlaylistRepository repository;
    private final PlaylistRepository playlistRepository;
    private final UserRepository userRepository;
    private final SmartPlaylistQueryParser parser;
    private final SmartPlaylistQueryTranslator translator;
    private final MongoTemplate mongoTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${stellar.grooves.smartPlaylist.materializeMax:5000}")
    private int materializeMax;

    @Value("${stellar.grooves.smartPlaylist.queryTimeoutSeconds:10}")
    private int queryTimeoutSeconds;

    public SmartPlaylistService(SmartPlaylistRepository repository,
                                PlaylistRepository playlistRepository,
                                UserRepository userRepository,
                                SmartPlaylistQueryParser parser,
                                SmartPlaylistQueryTranslator translator,
                                MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.playlistRepository = playlistRepository;
        this.userRepository = userRepository;
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
        return create(userId, name, queryString, null);
    }

    public SmartPlaylist create(String userId, String name, String queryString, String description) {
        parser.parse(queryString); // validate before persisting
        if (repository.existsByUserIdAndName(userId, name)) {
            throw new IllegalArgumentException("A smart playlist named '" + name + "' already exists");
        }
        SmartPlaylist sp = new SmartPlaylist();
        sp.setUserId(userId);
        sp.setName(name);
        sp.setQueryString(queryString);
        sp.setDescription(description);
        return repository.save(sp);
    }

    public SmartPlaylist update(SmartPlaylist existing, String name, String queryString, String description) {
        if (existing.isSubscription()) {
            throw new IllegalStateException("This is a subscribed smart playlist. Fork it first to edit the query.");
        }
        parser.parse(queryString); // validate
        if (!existing.getName().equals(name) && repository.existsByUserIdAndName(existing.getUserId(), name)) {
            throw new IllegalArgumentException("A smart playlist named '" + name + "' already exists");
        }
        existing.setName(name);
        existing.setQueryString(queryString);
        existing.setDescription(description);
        return repository.save(existing);
    }

    /** Subscribers may rename their local copy of a subscription, but cannot edit the query. */
    public SmartPlaylist renameSubscription(SmartPlaylist existing, String newName) {
        if (!existing.isSubscription()) {
            throw new IllegalStateException("Use update() for owned smart playlists");
        }
        if (!existing.getName().equals(newName) && repository.existsByUserIdAndName(existing.getUserId(), newName)) {
            throw new IllegalArgumentException("A smart playlist named '" + newName + "' already exists");
        }
        existing.setName(newName);
        return repository.save(existing);
    }

    public void delete(SmartPlaylist playlist) {
        repository.delete(playlist);
    }

    /** Execute a saved smart playlist and return paginated tracks. */
    public PreviewResult preview(SmartPlaylist playlist, int page, int size) {
        return execute(playlist.getUserId(), resolveQueryString(playlist), page, size);
    }

    /**
     * Resolve the active query string. For owner-rows this is just the row's queryString;
     * for subscriptions, it is read live from the source — that's the whole point of the
     * subscribe model (curator iterates, subscribers see updates).
     *
     * @throws IllegalStateException if this is a subscription whose source has been deleted
     */
    public String resolveQueryString(SmartPlaylist playlist) {
        if (!playlist.isSubscription()) {
            return playlist.getQueryString();
        }
        SmartPlaylist source = repository.findById(playlist.getSubscribedFromId())
                .orElseThrow(() -> new IllegalStateException(
                        "The original smart playlist for this subscription is no longer available"));
        return source.getQueryString();
    }

    /** Resolve description analogously to {@link #resolveQueryString}. */
    public String resolveDescription(SmartPlaylist playlist) {
        if (!playlist.isSubscription()) {
            return playlist.getDescription();
        }
        return repository.findById(playlist.getSubscribedFromId())
                .map(SmartPlaylist::getDescription)
                .orElse(null);
    }

    /** Look up the source row for a subscription. Empty if not a subscription or source deleted. */
    public Optional<SmartPlaylist> findSubscriptionSource(SmartPlaylist playlist) {
        if (!playlist.isSubscription()) return Optional.empty();
        return repository.findById(playlist.getSubscribedFromId());
    }

    /**
     * Dry-run: parse and execute a query without persisting.
     *
     * <p>Results are always capped at {@code stellar.grooves.smartPlaylist.materializeMax}
     * (default 5000) even when the query specifies no {@code limit:} — this protects
     * against pathological queries returning the entire library. The returned
     * {@link PreviewResult#truncated()} flag is set when the cap clipped the result.
     */
    public PreviewResult execute(String userId, String queryString, int page, int size) {
        ParsedQuery parsed = parser.parse(queryString);
        Criteria criteria = translator.translate(parsed.expression(), userId);
        Duration timeout = Duration.ofSeconds(Math.max(1, queryTimeoutSeconds));

        if (parsed.sort().map(SortSpec::isRandom).orElse(false)) {
            int sampleSize = parsed.limit().orElseThrow();
            List<MusicFile> sampled = sampleRandom(criteria, sampleSize);
            Page<MusicFile> p = new PageImpl<>(sampled, PageRequest.of(0, Math.max(size, 1)), sampled.size());
            return new PreviewResult(p, false);
        }

        Query countQuery = new Query(criteria).maxTime(timeout);
        Query pageQuery = new Query(criteria).with(sortFor(parsed.sort())).maxTime(timeout);
        long rawTotal = mongoTemplate.count(countQuery, MusicFile.class);

        int userLimit = parsed.limit().orElse(Integer.MAX_VALUE);
        int effectiveCap = Math.min(userLimit, materializeMax);
        long effectiveTotal = Math.min(rawTotal, effectiveCap);
        boolean truncated = rawTotal > effectiveTotal;

        int pageStart = page * size;
        int pageEndExclusive = (int) Math.min((long) pageStart + size, effectiveTotal);
        int pageSize = Math.max(0, pageEndExclusive - pageStart);

        List<MusicFile> content = pageSize == 0
                ? List.of()
                : mongoTemplate.find(pageQuery.skip(pageStart).limit(pageSize), MusicFile.class);

        Page<MusicFile> p = new PageImpl<>(content, PageRequest.of(page, size), effectiveTotal);
        return new PreviewResult(p, truncated);
    }

    /** Count matches for a saved smart playlist (subscriptions resolve query from source). */
    public long count(SmartPlaylist playlist) {
        return count(playlist.getUserId(), resolveQueryString(playlist));
    }

    /** Count matches for an ad-hoc query (dry-run). Applies the query's limit when present. */
    public long count(String userId, String queryString) {
        ParsedQuery parsed = parser.parse(queryString);
        Criteria criteria = translator.translate(parsed.expression(), userId);
        Duration timeout = Duration.ofSeconds(Math.max(1, queryTimeoutSeconds));
        long raw = mongoTemplate.count(new Query(criteria).maxTime(timeout), MusicFile.class);
        return parsed.limit().isPresent() ? Math.min(raw, parsed.limit().getAsInt()) : raw;
    }

    /**
     * Snapshot a smart playlist's current results into a new static {@link Playlist}.
     * Result size is bounded by the query's {@code limit:} (if present) and always
     * capped by {@code stellar.grooves.smartPlaylist.materializeMax} (default 5000).
     * {@code sort:} determines track order; {@code sort:random} uses {@code $sample}.
     */
    public MaterializeResult materialize(SmartPlaylist source, String name) {
        ParsedQuery parsed = parser.parse(resolveQueryString(source));
        Criteria criteria = translator.translate(parsed.expression(), source.getUserId());
        Duration timeout = Duration.ofSeconds(Math.max(1, queryTimeoutSeconds));

        int userLimit = parsed.limit().orElse(Integer.MAX_VALUE);
        int effectiveCap = Math.min(userLimit, materializeMax);

        List<String> trackIds;
        if (parsed.sort().map(SortSpec::isRandom).orElse(false)) {
            trackIds = sampleRandom(criteria, effectiveCap).stream()
                    .map(MusicFile::getId)
                    .toList();
        } else {
            Query query = new Query(criteria).with(sortFor(parsed.sort())).limit(effectiveCap).maxTime(timeout);
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
        AggregationOptions options = AggregationOptions.builder()
                .maxTime(Duration.ofSeconds(Math.max(1, queryTimeoutSeconds)))
                .build();
        Aggregation aggregation = Aggregation.newAggregation(stages).withOptions(options);
        AggregationResults<MusicFile> results =
                mongoTemplate.aggregate(aggregation, MusicFile.class, MusicFile.class);
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

    // ─── Sharing / subscriptions ────────────────────────────────────────

    /**
     * Generate (or return existing) public share token for an owned smart playlist.
     * Returns the playlist with token populated.
     *
     * @throws IllegalStateException if called on a subscription
     */
    public SmartPlaylist share(SmartPlaylist playlist) {
        if (playlist.isSubscription()) {
            throw new IllegalStateException("Cannot share a subscribed smart playlist; fork it first.");
        }
        if (playlist.getShareToken() == null) {
            playlist.setShareToken(generateShareToken());
            playlist.setShareTokenExpiresAt(null); // no expiry by default; revoke explicitly
            return repository.save(playlist);
        }
        return playlist;
    }

    /** Clear the share token. Existing subscriptions keep working — they're linked by id, not token. */
    public SmartPlaylist revokeShare(SmartPlaylist playlist) {
        playlist.setShareToken(null);
        playlist.setShareTokenExpiresAt(null);
        return repository.save(playlist);
    }

    public Optional<SmartPlaylist> findByShareToken(String token) {
        return repository.findByShareToken(token);
    }

    /**
     * Create a subscription to {@code source} for {@code subscriberId}. The new row carries
     * its own id and userId but reads queryString/description live from the source. The
     * subscriber gets the source's name; if it collides with one of their own playlists,
     * we suffix until unique.
     *
     * @throws IllegalArgumentException if subscriber would be subscribing to their own playlist
     */
    public SmartPlaylist subscribe(String subscriberId, SmartPlaylist source) {
        if (source.getUserId().equals(subscriberId)) {
            throw new IllegalArgumentException("Cannot subscribe to your own smart playlist");
        }
        // Idempotent: if subscriber already subscribed, return existing
        Optional<SmartPlaylist> existing = repository.findBySubscribedFromId(source.getId()).stream()
                .filter(sp -> sp.getUserId().equals(subscriberId))
                .findFirst();
        if (existing.isPresent()) return existing.get();

        SmartPlaylist sub = new SmartPlaylist();
        sub.setUserId(subscriberId);
        sub.setName(uniqueNameFor(subscriberId, source.getName()));
        sub.setSubscribedFromId(source.getId());
        // Snapshot the query into the row too — keeps the data coherent if the source is
        // later deleted, even though resolveQueryString() always prefers the live source.
        sub.setQueryString(source.getQueryString());
        return repository.save(sub);
    }

    /**
     * Convert a subscription into an independent smart playlist. The current source's
     * query and description are copied in; the subscribedFromId link is cleared.
     * From this point the subscriber owns the row and can edit it normally.
     *
     * <p>If the source has been deleted the subscriber's row keeps its last-known
     * snapshot of the query (set at subscribe time). We unlink against that snapshot
     * rather than failing — losing the curator update stream is better than losing
     * the query entirely.
     */
    public SmartPlaylist fork(SmartPlaylist subscription) {
        if (!subscription.isSubscription()) {
            throw new IllegalStateException("Only subscriptions can be forked");
        }
        Optional<SmartPlaylist> source = findSubscriptionSource(subscription);
        if (source.isPresent()) {
            subscription.setQueryString(source.get().getQueryString());
            subscription.setDescription(source.get().getDescription());
        }
        // else: keep the subscription's own snapshotted queryString/description
        subscription.setSubscribedFromId(null);
        return repository.save(subscription);
    }

    /** Number of users subscribed to a published smart playlist. */
    public long subscriberCount(SmartPlaylist playlist) {
        if (playlist == null || playlist.getId() == null) return 0L;
        return repository.countBySubscribedFromId(playlist.getId());
    }

    private String generateShareToken() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String uniqueNameFor(String userId, String desired) {
        if (!repository.existsByUserIdAndName(userId, desired)) return desired;
        for (int i = 2; i < 100; i++) {
            String candidate = desired + " (" + i + ")";
            if (!repository.existsByUserIdAndName(userId, candidate)) return candidate;
        }
        // Extremely unlikely; fall back to a token suffix
        return desired + " " + Long.toHexString(System.nanoTime());
    }

    /** Resolve curator's username for a subscription (or any playlist). */
    public Optional<String> findOwnerUsername(SmartPlaylist playlist) {
        return userRepository.findById(playlist.getUserId()).map(User::getUsername);
    }

    public record MaterializeResult(Playlist playlist, int trackCount, boolean truncated) {}

    public record PreviewResult(Page<MusicFile> page, boolean truncated) {}
}
