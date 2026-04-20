package com.stellarideas.grooves.service.scan;

import com.stellarideas.grooves.model.CoverArt;
import com.stellarideas.grooves.repository.CoverArtRepository;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Handles extraction and persistence of cover art during a scan, enforcing per-user
 * byte quotas and per-album deduplication.
 *
 * <p>Stateless across scans; each scan allocates a fresh {@link Budget} to track
 * running byte usage and which albums have already been handled. Quota is checked
 * twice — once against the running budget, once against the repository total right
 * before persisting — to avoid racing with a concurrent scan for the same user.
 */
@Component
public class CoverArtHandler {

    private static final Logger logger = LoggerFactory.getLogger(CoverArtHandler.class);

    @Value("${stellar.grooves.coverArt.maxBytesPerImage:10485760}")
    private int maxBytesPerImage;

    @Value("${stellar.grooves.coverArt.maxBytesPerUser:524288000}")
    private long maxBytesPerUser;

    private final CoverArtRepository repository;

    public CoverArtHandler(CoverArtRepository repository) {
        this.repository = repository;
    }

    /** Create a fresh per-scan quota budget seeded with the user's current usage. */
    public Budget newBudget(String userId) {
        Long usage = repository.getTotalCoverArtSizeByUserId(userId);
        return new Budget(usage != null ? usage : 0L, maxBytesPerUser);
    }

    /**
     * Extract cover art for the given tag/album, persist it if within quota, and return
     * whether the file has cover art (either newly stored or already present for this album).
     */
    public boolean process(Tag tag, String userId, String artist, String album, Budget budget) {
        if (artist == null || artist.isBlank() || album == null || album.isBlank()) return false;

        String key = artist.toLowerCase() + "\0" + album.toLowerCase();
        if (budget.albumsSeen.contains(key)) return true;
        if (budget.exhausted) {
            logger.debug("Cover art quota exhausted for user '{}', skipping", userId);
            return false;
        }

        long stored = extract(tag, userId, artist, album, budget);
        if (stored > 0) {
            budget.albumsSeen.add(key);
            budget.usedBytes += stored;
            if (budget.usedBytes >= maxBytesPerUser) budget.exhausted = true;
            return true;
        }
        return false;
    }

    private long extract(Tag tag, String userId, String artist, String album, Budget budget) {
        try {
            if (tag == null) return 0;
            Artwork artwork = tag.getFirstArtwork();
            if (artwork == null || artwork.getBinaryData() == null || artwork.getBinaryData().length == 0) {
                return 0;
            }
            int len = artwork.getBinaryData().length;
            if (len > maxBytesPerImage) {
                logger.warn("Cover art for '{} - {}' exceeds per-image cap ({} bytes), skipping",
                        artist, album, len);
                return 0;
            }
            // If we already persisted art for this album earlier (other scan, prior run),
            // count the file as having cover art but don't write again.
            if (repository.findByUserIdAndArtistAndAlbum(userId, artist, album).isPresent()) {
                return len;
            }
            // Live quota re-check guards against concurrent scans pushing us over the limit.
            Long live = repository.getTotalCoverArtSizeByUserId(userId);
            if (live != null && live + len > maxBytesPerUser) {
                budget.exhausted = true;
                logger.debug("Cover art quota would overflow for user '{}' (live={} + {}), skipping",
                        userId, live, len);
                return 0;
            }
            CoverArt art = new CoverArt();
            art.setUserId(userId);
            art.setArtist(artist);
            art.setAlbum(album);
            art.setMimeType(artwork.getMimeType() != null ? artwork.getMimeType() : "image/jpeg");
            art.setData(artwork.getBinaryData());
            repository.save(art);
            return len;
        } catch (RuntimeException e) {
            logger.debug("Failed to extract cover art for '{} - {}': {}", artist, album, e.getMessage());
            return 0;
        }
    }

    /** Per-scan quota tracking. Mutated in-place by {@link #process}. */
    public static final class Budget {
        final Set<String> albumsSeen = new HashSet<>();
        long usedBytes;
        boolean exhausted;

        Budget(long initialUsage, long maxBytes) {
            this.usedBytes = initialUsage;
            this.exhausted = initialUsage >= maxBytes;
        }

        public long usedBytes() { return usedBytes; }
        public boolean isExhausted() { return exhausted; }
        public int albumsSeen() { return albumsSeen.size(); }
    }
}
