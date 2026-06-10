package com.stellarideas.grooves.service.scan;

import com.stellarideas.grooves.model.CoverArt;
import com.stellarideas.grooves.repository.CoverArtRepository;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Handles extraction and persistence of cover art during a scan, enforcing per-user
 * byte quotas and per-album deduplication.
 *
 * <p>Art is resolved in priority order: embedded tag artwork first, then — when the
 * track has no embedded art — a "sidecar" image file sitting next to the track in
 * the same directory (cover.jpg / folder.jpg / front.jpg …), which is how most
 * lossless rips ship cover art. Sidecar lookup is local-only (no network) and can
 * be disabled via {@code stellar.grooves.coverArt.folderImageEnabled=false}.
 *
 * <p>Stateless across scans; each scan allocates a fresh {@link Budget} to track
 * running byte usage and which albums have already been handled. Quota is checked
 * twice — once against the running budget, once against the repository total right
 * before persisting — to avoid racing with a concurrent scan for the same user.
 */
@Component
public class CoverArtHandler {

    private static final Logger logger = LoggerFactory.getLogger(CoverArtHandler.class);

    /** Preferred sidecar base names, in descending priority (lower index wins). */
    private static final List<String> PREFERRED_NAMES = List.of(
            "cover", "folder", "front", "albumart", "album", "artwork", "thumb", "default");

    /** Recognised sidecar image extensions → mime type. */
    private static final Map<String, String> IMAGE_MIME = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp",
            "gif", "image/gif",
            "bmp", "image/bmp");

    @Value("${stellar.grooves.coverArt.maxBytesPerImage:10485760}")
    private int maxBytesPerImage;

    @Value("${stellar.grooves.coverArt.maxBytesPerUser:524288000}")
    private long maxBytesPerUser;

    /** Global ceiling across all users; 0 disables the check. */
    @Value("${stellar.grooves.coverArt.maxBytesGlobal:10737418240}")
    private long maxBytesGlobal;

    /** Whether to fall back to a sidecar image when a track has no embedded art. */
    @Value("${stellar.grooves.coverArt.folderImageEnabled:true}")
    private boolean folderImageEnabled = true;

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
     * Embedded-art-only entry point (no sidecar fallback). Retained for callers/tests
     * that don't have the track's path; delegates with a null path.
     */
    public boolean process(Tag tag, String userId, String artist, String album, Budget budget) {
        return process(tag, null, userId, artist, album, budget);
    }

    /**
     * Resolve cover art for the given track and persist it if within quota. Tries the
     * embedded tag artwork first, then a sidecar image in {@code filePath}'s directory
     * (when {@code filePath} is non-null and folder fallback is enabled).
     *
     * @return whether the file has cover art (newly stored, or already present for this album)
     */
    public boolean process(Tag tag, Path filePath, String userId, String artist, String album, Budget budget) {
        if (artist == null || artist.isBlank() || album == null || album.isBlank()) return false;

        String key = artist.toLowerCase() + "\0" + album.toLowerCase();
        if (budget.albumsSeen.contains(key)) return true;
        if (budget.exhausted) {
            logger.debug("Cover art quota exhausted for user '{}', skipping", userId);
            return false;
        }

        // Album already has art (an earlier track this scan, or a prior run)? Count the
        // file as covered and skip both the write and any disk read. This also lets a
        // track with no embedded art inherit its album's existing cover.
        if (repository.findByUserIdAndArtistAndAlbum(userId, artist, album).isPresent()) {
            budget.albumsSeen.add(key);
            return true;
        }

        long stored = extractEmbedded(tag, userId, artist, album, budget);
        if (stored == 0 && folderImageEnabled && filePath != null && !budget.exhausted) {
            stored = extractFolderImage(filePath, userId, artist, album, budget);
        }
        if (stored > 0) {
            budget.albumsSeen.add(key);
            budget.usedBytes += stored;
            if (budget.usedBytes >= maxBytesPerUser) budget.exhausted = true;
            return true;
        }
        return false;
    }

    /**
     * Store a manually-supplied cover image for an album, replacing any existing art for it.
     * Unlike scan extraction this always upserts (a curator setting art means "use this one"),
     * and enforces the per-image and per-user/global byte quotas accounting for the replaced
     * art's size. Throws {@link IllegalArgumentException} on an empty image or a quota breach.
     */
    public void storeManualCover(String userId, String artist, String album, byte[] data, String mime) {
        if (artist == null || artist.isBlank() || album == null || album.isBlank()) {
            throw new IllegalArgumentException("Track has no album/artist to attach cover art to");
        }
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Image is empty");
        }
        if (data.length > maxBytesPerImage) {
            throw new IllegalArgumentException("Image exceeds the " + (maxBytesPerImage / (1024 * 1024)) + " MB per-image limit");
        }

        var existing = repository.findByUserIdAndArtistAndAlbum(userId, artist, album);
        long oldSize = existing.map(c -> c.getData() != null ? c.getData().length : 0).orElse(0);

        Long usage = repository.getTotalCoverArtSizeByUserId(userId);
        long current = usage != null ? usage : 0L;
        if (current - oldSize + data.length > maxBytesPerUser) {
            throw new IllegalArgumentException("Cover art storage quota exceeded");
        }
        if (maxBytesGlobal > 0) {
            Long g = repository.getTotalCoverArtSize();
            long globalUsage = g != null ? g : 0L;
            if (globalUsage - oldSize + data.length > maxBytesGlobal) {
                throw new IllegalArgumentException("Global cover art storage quota exceeded");
            }
        }

        CoverArt art = existing.orElseGet(CoverArt::new);
        art.setUserId(userId);
        art.setArtist(artist);
        art.setAlbum(album);
        art.setMimeType(mime);
        art.setData(data);
        art.setSource("manual");
        repository.save(art);
    }

    /** Extract and persist embedded tag artwork. Returns bytes stored, or 0 if none/over quota. */
    private long extractEmbedded(Tag tag, String userId, String artist, String album, Budget budget) {
        try {
            if (tag == null) return 0;
            Artwork artwork = tag.getFirstArtwork();
            if (artwork == null || artwork.getBinaryData() == null || artwork.getBinaryData().length == 0) {
                return 0;
            }
            byte[] data = artwork.getBinaryData();
            if (data.length > maxBytesPerImage) {
                logger.warn("Cover art for '{} - {}' exceeds per-image cap ({} bytes), skipping",
                        artist, album, data.length);
                return 0;
            }
            String mime = artwork.getMimeType() != null ? artwork.getMimeType() : "image/jpeg";
            return persist(userId, artist, album, mime, data, "embedded", budget);
        } catch (RuntimeException e) {
            logger.debug("Failed to extract embedded cover art for '{} - {}': {}", artist, album, e.getMessage());
            return 0;
        }
    }

    /** Look for a sidecar image next to the track and persist it. Returns bytes stored, or 0. */
    private long extractFolderImage(Path filePath, String userId, String artist, String album, Budget budget) {
        try {
            Path dir = filePath.getParent();
            if (dir == null || !Files.isDirectory(dir)) return 0;

            Path chosen = findBestSidecar(dir);
            if (chosen == null) return 0;

            long size = Files.size(chosen);
            if (size <= 0) return 0;
            if (size > maxBytesPerImage) {
                logger.warn("Sidecar cover art '{}' for '{} - {}' exceeds per-image cap ({} bytes), skipping",
                        chosen.getFileName(), artist, album, size);
                return 0;
            }

            byte[] data = Files.readAllBytes(chosen);
            if (data.length == 0 || data.length > maxBytesPerImage) return 0;

            String mime = mimeForExtension(chosen);
            long stored = persist(userId, artist, album, mime, data, "folder", budget);
            if (stored > 0) {
                logger.debug("Stored sidecar cover art '{}' for '{} - {}'", chosen.getFileName(), artist, album);
            }
            return stored;
        } catch (IOException | RuntimeException e) {
            logger.debug("Failed to read sidecar cover art for '{} - {}': {}", artist, album, e.getMessage());
            return 0;
        }
    }

    /**
     * Persist a cover-art image after a live quota re-check. Returns the number of bytes
     * written, or 0 if the write was skipped (quota) or failed. Marks the budget exhausted
     * when a quota ceiling is hit so the rest of the scan stops trying.
     */
    private long persist(String userId, String artist, String album, String mime, byte[] data,
                         String source, Budget budget) {
        try {
            // Live per-user quota re-check guards against concurrent scans pushing us over.
            Long live = repository.getTotalCoverArtSizeByUserId(userId);
            if (live != null && live + data.length > maxBytesPerUser) {
                budget.exhausted = true;
                logger.debug("Cover art quota would overflow for user '{}' (live={} + {}), skipping",
                        userId, live, data.length);
                return 0;
            }
            if (maxBytesGlobal > 0) {
                Long liveGlobal = repository.getTotalCoverArtSize();
                if (liveGlobal != null && liveGlobal + data.length > maxBytesGlobal) {
                    budget.exhausted = true;
                    logger.warn("Global cover art quota reached ({} + {} > {}), skipping further extraction for user '{}'",
                            liveGlobal, data.length, maxBytesGlobal, userId);
                    return 0;
                }
            }
            CoverArt art = new CoverArt();
            art.setUserId(userId);
            art.setArtist(artist);
            art.setAlbum(album);
            art.setMimeType(mime);
            art.setData(data);
            art.setSource(source);
            repository.save(art);
            return data.length;
        } catch (RuntimeException e) {
            logger.debug("Failed to persist cover art for '{} - {}': {}", artist, album, e.getMessage());
            return 0;
        }
    }

    /**
     * Choose the best sidecar image in a directory: the recognised image file whose base
     * name ranks highest in {@link #PREFERRED_NAMES}. Returns null if the directory holds
     * no preferred-named image (we don't grab arbitrary images, to avoid false matches).
     */
    private Path findBestSidecar(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(p -> sidecarRank(p) >= 0)
                    .min(Comparator.comparingInt(this::sidecarRank))
                    .orElse(null);
        }
    }

    /** Priority rank of a path as a sidecar (lower = better); -1 if not a preferred image. */
    private int sidecarRank(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return -1;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!IMAGE_MIME.containsKey(ext)) return -1;
        String base = name.substring(0, dot).toLowerCase(Locale.ROOT);
        return PREFERRED_NAMES.indexOf(base);
    }

    private String mimeForExtension(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return IMAGE_MIME.getOrDefault(ext, "image/jpeg");
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
