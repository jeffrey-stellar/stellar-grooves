package com.stellarideas.grooves.service.coverart;

import com.stellarideas.grooves.model.CoverArtMiss;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.repository.CoverArtMissRepository;
import com.stellarideas.grooves.repository.MusicFileRepository;
import com.stellarideas.grooves.service.scan.CoverArtHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional, opt-in background job that fills in missing album art from external providers
 * (MusicBrainz/Cover Art Archive, then iTunes). OFF by default — enabling it sends album/artist
 * names to third-party services, so it must be explicitly turned on by the operator.
 *
 * <p>For each album with no art it tries the configured providers in order, throttling between
 * lookups to respect rate limits, stores the first confident hit, and records a "miss" for
 * albums that came up empty so repeat runs skip them for a while.
 */
@Service
public class ExternalCoverArtService {

    private static final Logger logger = LoggerFactory.getLogger(ExternalCoverArtService.class);

    private final List<AlbumArtProvider> allProviders;
    private final MusicFileRepository musicFileRepository;
    private final CoverArtMissRepository missRepository;
    private final CoverArtHandler coverArtHandler;

    @Value("${stellar.grooves.coverArt.external.enabled:false}")
    private boolean enabled;

    @Value("${stellar.grooves.coverArt.external.providers:musicbrainz,itunes}")
    private String providerOrder;

    @Value("${stellar.grooves.coverArt.external.rateLimitMs:1100}")
    private long rateLimitMs;

    @Value("${stellar.grooves.coverArt.external.maxAlbumsPerRun:200}")
    private int maxAlbumsPerRun;

    @Value("${stellar.grooves.coverArt.external.retryAfterDays:30}")
    private int retryAfterDays;

    private final Map<String, Status> statusByUser = new ConcurrentHashMap<>();

    public ExternalCoverArtService(List<AlbumArtProvider> allProviders,
                                   MusicFileRepository musicFileRepository,
                                   CoverArtMissRepository missRepository,
                                   CoverArtHandler coverArtHandler) {
        this.allProviders = allProviders;
        this.musicFileRepository = musicFileRepository;
        this.missRepository = missRepository;
        this.coverArtHandler = coverArtHandler;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isRunning(String userId) {
        Status s = statusByUser.get(userId);
        return s != null && s.running;
    }

    public Status getStatus(String userId) {
        return statusByUser.getOrDefault(userId, Status.idle());
    }

    /** Providers selected and ordered per the {@code providers} config; unknown names ignored. */
    List<AlbumArtProvider> orderedProviders() {
        Map<String, AlbumArtProvider> byName = new LinkedHashMap<>();
        for (AlbumArtProvider p : allProviders) byName.put(p.name(), p);
        List<AlbumArtProvider> ordered = new ArrayList<>();
        for (String name : providerOrder.split(",")) {
            AlbumArtProvider p = byName.get(name.trim().toLowerCase());
            if (p != null) ordered.add(p);
        }
        return ordered;
    }

    /** Kick off a run on a background thread. Caller should guard with {@link #isRunning}. */
    @Async("coverArtTaskExecutor")
    public void fetchMissingAsync(String userId) {
        try {
            fetchMissing(userId);
        } catch (RuntimeException e) {
            logger.warn("External cover art run failed for user '{}': {}", userId, e.getMessage());
            Status s = statusByUser.get(userId);
            if (s != null) s.finish();
        }
    }

    /**
     * Synchronous core (also the unit-test entry point): fetch art for every album missing it,
     * up to {@code maxAlbumsPerRun}. Returns the run summary.
     */
    public Status fetchMissing(String userId) {
        List<AlbumArtProvider> providers = orderedProviders();
        List<String[]> missingAlbums = findAlbumsMissingArt(userId);

        Status status = new Status();
        status.running = true;
        status.total = Math.min(missingAlbums.size(), maxAlbumsPerRun);
        status.startedAt = Instant.now();
        statusByUser.put(userId, status);

        Instant retryCutoff = Instant.now().minus(retryAfterDays, ChronoUnit.DAYS);
        int processed = 0;
        try {
            for (String[] album : missingAlbums) {
                if (processed >= maxAlbumsPerRun || providers.isEmpty()) break;
                String artist = album[0], name = album[1];

                if (recentlyMissed(userId, artist, name, retryCutoff)) {
                    status.skipped++;
                    continue;
                }
                if (processed > 0) throttle();
                processed++;
                status.processed = processed;

                Optional<FetchedArt> found = Optional.empty();
                String source = null;
                for (AlbumArtProvider p : providers) {
                    found = safeFetch(p, artist, name);
                    if (found.isPresent()) { source = p.name(); break; }
                }

                if (found.isPresent()) {
                    storeAndMark(userId, artist, name, found.get(), source);
                    status.fetched++;
                } else {
                    recordMiss(userId, artist, name);
                }
            }
        } finally {
            status.finish();
        }
        logger.info("External cover art run for user '{}': {} fetched, {} processed, {} skipped",
                userId, status.fetched, status.processed, status.skipped);
        return status;
    }

    private List<String[]> findAlbumsMissingArt(String userId) {
        List<MusicFile> files = musicFileRepository.findByUserIdAndDeletedFalse(userId);
        Map<String, String[]> rep = new LinkedHashMap<>();
        Map<String, Boolean> hasArt = new LinkedHashMap<>();
        for (MusicFile f : files) {
            String artist = f.getArtist(), album = f.getAlbum();
            if (artist == null || artist.isBlank() || album == null || album.isBlank()) continue;
            String key = artist.toLowerCase() + "\0" + album.toLowerCase();
            rep.putIfAbsent(key, new String[]{artist, album});
            hasArt.merge(key, f.isHasCoverArt(), (a, b) -> a || b);
        }
        List<String[]> missing = new ArrayList<>();
        for (Map.Entry<String, String[]> e : rep.entrySet()) {
            if (!Boolean.TRUE.equals(hasArt.get(e.getKey()))) missing.add(e.getValue());
        }
        return missing;
    }

    private boolean recentlyMissed(String userId, String artist, String album, Instant cutoff) {
        return missRepository.findByUserIdAndArtistAndAlbum(userId, artist, album)
                .map(m -> m.getLastAttemptAt() != null && m.getLastAttemptAt().isAfter(cutoff))
                .orElse(false);
    }

    private Optional<FetchedArt> safeFetch(AlbumArtProvider p, String artist, String album) {
        try {
            return p.fetch(artist, album);
        } catch (RuntimeException e) {
            logger.debug("Provider '{}' threw for '{} - {}': {}", p.name(), artist, album, e.getMessage());
            return Optional.empty();
        }
    }

    private void storeAndMark(String userId, String artist, String album, FetchedArt art, String source) {
        coverArtHandler.storeFetchedCover(userId, artist, album, art.data(), art.mimeType(), source);
        List<MusicFile> albumFiles = musicFileRepository
                .findByUserIdAndArtistAndAlbumAndDeletedFalse(userId, artist, album);
        for (MusicFile f : albumFiles) f.setHasCoverArt(true);
        musicFileRepository.saveAll(albumFiles);
        missRepository.deleteByUserIdAndArtistAndAlbum(userId, artist, album);
    }

    private void recordMiss(String userId, String artist, String album) {
        CoverArtMiss miss = missRepository.findByUserIdAndArtistAndAlbum(userId, artist, album)
                .orElseGet(() -> {
                    CoverArtMiss m = new CoverArtMiss();
                    m.setUserId(userId);
                    m.setArtist(artist);
                    m.setAlbum(album);
                    return m;
                });
        miss.setAttempts(miss.getAttempts() + 1);
        miss.setLastAttemptAt(Instant.now());
        missRepository.save(miss);
    }

    private void throttle() {
        if (rateLimitMs <= 0) return;
        try {
            Thread.sleep(rateLimitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Mutable per-user run state; snapshot is read by the status endpoint. */
    public static final class Status {
        public volatile boolean running;
        public volatile int total;
        public volatile int processed;
        public volatile int fetched;
        public volatile int skipped;
        public volatile Instant startedAt;
        public volatile Instant finishedAt;

        static Status idle() {
            return new Status();
        }

        void finish() {
            this.running = false;
            this.finishedAt = Instant.now();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("running", running);
            m.put("total", total);
            m.put("processed", processed);
            m.put("fetched", fetched);
            m.put("skipped", skipped);
            m.put("startedAt", startedAt);
            m.put("finishedAt", finishedAt);
            return m;
        }
    }
}
