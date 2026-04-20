package com.stellarideas.grooves.service;

import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.Playlist;
import com.stellarideas.grooves.repository.MusicFileRepository;
import com.stellarideas.grooves.repository.PlaylistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Permanently deletes soft-deleted {@link MusicFile}s whose {@code deletedAt} is older than
 * the configured retention window. Also prunes any references to purged tracks from playlists.
 *
 * <p>Configured via {@code stellar.grooves.trash.retentionDays} (default 30) and
 * {@code stellar.grooves.trash.purgeCron} (default 3am daily). Setting retentionDays to 0
 * disables automatic purging.
 */
@Service
public class TrashPurgeService {

    private static final Logger logger = LoggerFactory.getLogger(TrashPurgeService.class);

    private final MusicFileRepository musicFileRepository;
    private final PlaylistRepository playlistRepository;
    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    @Value("${stellar.grooves.trash.retentionDays:30}")
    private int retentionDays;

    public TrashPurgeService(MusicFileRepository musicFileRepository,
                             PlaylistRepository playlistRepository,
                             MongoTemplate mongoTemplate) {
        this(musicFileRepository, playlistRepository, mongoTemplate, Clock.systemUTC());
    }

    TrashPurgeService(MusicFileRepository musicFileRepository,
                      PlaylistRepository playlistRepository,
                      MongoTemplate mongoTemplate,
                      Clock clock) {
        this.musicFileRepository = musicFileRepository;
        this.playlistRepository = playlistRepository;
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    /**
     * Entry point for the scheduled trigger. Errors are logged but not rethrown so one
     * bad run doesn't block future invocations of other @Scheduled tasks.
     */
    @Scheduled(cron = "${stellar.grooves.trash.purgeCron:0 0 3 * * *}")
    public void scheduledPurge() {
        if (retentionDays <= 0) {
            logger.debug("Trash purge disabled (retentionDays={})", retentionDays);
            return;
        }
        try {
            MDC.put("correlationId", UUID.randomUUID().toString());
            PurgeResult result = purgeExpired();
            if (result.filesDeleted() > 0 || result.playlistsModified() > 0) {
                logger.info("Trash purge complete: {} files deleted across {} users, {} playlists cleaned",
                        result.filesDeleted(), result.usersAffected(), result.playlistsModified());
            }
        } catch (Exception e) {
            logger.error("Trash purge failed: {}", e.getMessage(), e);
        } finally {
            MDC.clear();
        }
    }

    /**
     * Permanently delete every {@link MusicFile} marked deleted whose {@code deletedAt}
     * is at or before {@code now - retentionDays}, and strip references from playlists.
     * Idempotent and safe to call ad-hoc (e.g. from an admin endpoint or test).
     */
    @Transactional
    public PurgeResult purgeExpired() {
        Instant cutoff = Instant.now(clock).minus(Duration.ofDays(retentionDays));
        Query query = new Query(Criteria.where("deleted").is(true).and("deletedAt").lte(cutoff));
        List<MusicFile> expired = mongoTemplate.find(query, MusicFile.class);
        if (expired.isEmpty()) return PurgeResult.empty();

        Map<String, List<String>> byUser = new HashMap<>();
        for (MusicFile f : expired) {
            byUser.computeIfAbsent(f.getUserId(), k -> new ArrayList<>()).add(f.getId());
        }

        int playlistsModified = 0;
        for (Map.Entry<String, List<String>> e : byUser.entrySet()) {
            playlistsModified += stripFromPlaylists(e.getKey(), Set.copyOf(e.getValue()));
        }

        musicFileRepository.deleteAll(expired);
        return new PurgeResult(expired.size(), byUser.size(), playlistsModified);
    }

    private int stripFromPlaylists(String userId, Set<String> fileIds) {
        List<Playlist> playlists = playlistRepository.findByUserId(userId);
        List<Playlist> modified = new ArrayList<>();
        for (Playlist p : playlists) {
            if (p.getTrackIds() != null && p.getTrackIds().removeAll(fileIds)) {
                modified.add(p);
            }
        }
        if (!modified.isEmpty()) playlistRepository.saveAll(modified);
        return modified.size();
    }

    public record PurgeResult(int filesDeleted, int usersAffected, int playlistsModified) {
        public static PurgeResult empty() { return new PurgeResult(0, 0, 0); }
    }
}
