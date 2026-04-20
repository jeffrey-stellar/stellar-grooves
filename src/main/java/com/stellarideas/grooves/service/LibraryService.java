package com.stellarideas.grooves.service;

import com.stellarideas.grooves.dto.LibraryBackup;
import com.stellarideas.grooves.dto.MusicFileDTO;
import com.stellarideas.grooves.model.*;
import com.stellarideas.grooves.repository.CoverArtRepository;
import com.stellarideas.grooves.repository.MusicFileRepository;
import com.stellarideas.grooves.repository.PlaybackQueueRepository;
import com.stellarideas.grooves.repository.PlayEventRepository;
import com.stellarideas.grooves.repository.PlaylistRepository;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static com.stellarideas.grooves.config.PaginationDefaults.*;

@Service
public class LibraryService {

    private final MusicFileRepository musicFileRepository;
    private final PlaylistRepository playlistRepository;
    private final CoverArtRepository coverArtRepository;
    private final PlaybackQueueRepository playbackQueueRepository;
    private final PlayEventRepository playEventRepository;
    private final MusicCatalogService catalogService;
    private final MongoTemplate mongoTemplate;

    public LibraryService(MusicFileRepository musicFileRepository,
                          PlaylistRepository playlistRepository,
                          CoverArtRepository coverArtRepository,
                          PlaybackQueueRepository playbackQueueRepository,
                          PlayEventRepository playEventRepository,
                          MusicCatalogService catalogService,
                          MongoTemplate mongoTemplate) {
        this.musicFileRepository = musicFileRepository;
        this.playlistRepository = playlistRepository;
        this.coverArtRepository = coverArtRepository;
        this.playbackQueueRepository = playbackQueueRepository;
        this.playEventRepository = playEventRepository;
        this.catalogService = catalogService;
        this.mongoTemplate = mongoTemplate;
    }

    public Page<MusicFile> getFiles(String userId, Genre genre, int page, int size) {
        size = clamp(size, MAX_PAGE_SIZE);
        return (genre == null)
                ? musicFileRepository.findByUserIdAndDeletedFalse(userId, PageRequest.of(page, size))
                : musicFileRepository.findByUserIdAndGenreAndDeletedFalse(userId, genre, PageRequest.of(page, size));
    }

    @org.springframework.beans.factory.annotation.Value("${stellar.grooves.search.maxQueryLength:200}")
    private int maxSearchQueryLength;
    private static final int PATTERN_CACHE_MAX_SIZE = 128;

    @SuppressWarnings("serial")
    private final Map<String, String> quotedPatternCache = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(PATTERN_CACHE_MAX_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > PATTERN_CACHE_MAX_SIZE;
                }
            });

    public Page<MusicFile> searchFiles(String userId, String query, int page, int size) {
        return searchFiles(userId, query, null, null, null, null, page, size);
    }

    public Page<MusicFile> searchFiles(String userId, String query,
                                       Genre genre, String artist, String year, String fileExtension,
                                       int page, int size) {
        size = clamp(size, MAX_PAGE_SIZE);
        boolean hasFilters = genre != null || (artist != null && !artist.isBlank())
                || (year != null && !year.isBlank()) || (fileExtension != null && !fileExtension.isBlank());
        boolean hasQuery = query != null && !query.isBlank() && query.length() <= maxSearchQueryLength;

        if (!hasQuery && !hasFilters) {
            return Page.empty();
        }

        // When filters are present, use the combined filtered search
        if (hasFilters) {
            return musicFileRepository.filteredSearch(userId, hasQuery ? query : null,
                    genre, artist, year, fileExtension, PageRequest.of(page, size));
        }

        // No filters — use the existing text search with regex fallback
        try {
            Page<MusicFile> results = musicFileRepository.textSearch(userId, query, PageRequest.of(page, size));
            if (results.getTotalElements() > 0) {
                return results;
            }
        } catch (Exception e) {
            // Text index may not exist yet — fall through to regex
        }
        String escaped = quotedPatternCache.computeIfAbsent(query, java.util.regex.Pattern::quote);
        return musicFileRepository.searchByUserIdAndQuery(userId, escaped, PageRequest.of(page, size));
    }

    public Optional<MusicFile> findFileByIdAndUserId(String id, String userId) {
        return musicFileRepository.findByIdAndUserIdAndDeletedFalse(id, userId);
    }

    public Set<String> findOwnedTrackIds(List<String> trackIds, String userId) {
        return musicFileRepository.findByIdInAndUserId(trackIds, userId).stream()
                .map(MusicFile::getId)
                .collect(Collectors.toSet());
    }

    public MusicFile updateGenre(MusicFile file, Genre genre, String userId) {
        file.setGenre(genre);
        musicFileRepository.save(file);
        if (file.getArtist() != null && !file.getArtist().isBlank()) {
            catalogService.recordCorrection(file.getArtist(), genre, userId);
        }
        return file;
    }

    public MusicFile updateRating(MusicFile file, int rating) {
        file.setRating(rating);
        return musicFileRepository.save(file);
    }

    static final int MAX_TAG_LENGTH = 50;
    static final int MAX_TAGS_PER_TRACK = 20;

    /**
     * Normalize tag input: trim, collapse internal whitespace, lowercase,
     * drop blanks, dedupe (order-preserving). A null/empty result clears the track's tags.
     * Individual over-length tags are rejected via IllegalArgumentException.
     */
    public static List<String> normalizeTags(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String t : raw) {
            if (t == null) continue;
            String cleaned = t.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            if (cleaned.isEmpty()) continue;
            if (cleaned.length() > MAX_TAG_LENGTH) {
                throw new IllegalArgumentException("Tag exceeds " + MAX_TAG_LENGTH + " characters: " + cleaned);
            }
            seen.add(cleaned);
            if (seen.size() > MAX_TAGS_PER_TRACK) {
                throw new IllegalArgumentException("A track can have at most " + MAX_TAGS_PER_TRACK + " tags");
            }
        }
        return new ArrayList<>(seen);
    }

    public MusicFile updateTags(MusicFile file, List<String> tags) {
        List<String> normalized = normalizeTags(tags);
        file.setCustomTags(normalized.isEmpty() ? null : normalized);
        return musicFileRepository.save(file);
    }

    public List<String> listDistinctTags(String userId) {
        List<String> values = mongoTemplate.findDistinct(
                new Query(Criteria.where("userId").is(userId).and("customTags").exists(true)),
                "customTags", MusicFile.class, String.class);
        Collections.sort(values);
        return values;
    }

    /** Return all tags the user has in use with per-tag usage counts, sorted alphabetically. */
    public List<TagCount> listTagsWithCounts(String userId) {
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("userId").is(userId)
                        .and("deleted").ne(true)
                        .and("customTags").exists(true)),
                Aggregation.unwind("customTags"),
                Aggregation.group("customTags").count().as("count"),
                Aggregation.project("count").and("_id").as("tag").andExclude("_id"),
                Aggregation.sort(Sort.Direction.ASC, "tag"));

        AggregationResults<Document> results =
                mongoTemplate.aggregate(agg, MusicFile.class, Document.class);

        List<TagCount> out = new ArrayList<>();
        for (Document d : results.getMappedResults()) {
            String tag = d.getString("tag");
            if (tag == null || tag.isBlank()) continue;
            out.add(new TagCount(tag, d.getInteger("count", 0)));
        }
        return out;
    }

    /**
     * Add and/or remove tags across many files owned by the user. {@code add} tags are normalized
     * the same way as {@link #normalizeTags}; {@code remove} tags are compared case-insensitively
     * after trim. Returns a summary of how many files changed and how many were not found.
     *
     * <p>This walks the matching files and rewrites {@code customTags} per file so the per-track
     * tag cap ({@link #MAX_TAGS_PER_TRACK}) is still honored — a bulk add that would push a file
     * over the cap is rejected for the whole batch (no partial writes).
     */
    @Transactional
    public BulkTagResult bulkUpdateTags(String userId, List<String> fileIds,
                                        List<String> addRaw, List<String> removeRaw) {
        if (fileIds == null || fileIds.isEmpty()) return new BulkTagResult(0, 0);

        List<String> addTags = normalizeTagList(addRaw);
        Set<String> removeTags = removeRaw == null ? Set.of()
                : removeRaw.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(s -> s.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toCollection(LinkedHashSet::new));

        if (addTags.isEmpty() && removeTags.isEmpty()) return new BulkTagResult(0, 0);

        Query scope = new Query(Criteria.where("_id").in(fileIds)
                .and("userId").is(userId)
                .and("deleted").ne(true));
        List<MusicFile> files = mongoTemplate.find(scope, MusicFile.class);

        int modified = 0;
        for (MusicFile file : files) {
            LinkedHashSet<String> current = file.getCustomTags() == null
                    ? new LinkedHashSet<>()
                    : new LinkedHashSet<>(file.getCustomTags());
            int before = current.size();

            current.removeAll(removeTags);
            current.addAll(addTags);

            if (current.size() > MAX_TAGS_PER_TRACK) {
                throw new IllegalArgumentException(
                        "A track would exceed " + MAX_TAGS_PER_TRACK + " tags after this update");
            }

            if (current.size() == before
                    && (file.getCustomTags() == null ? List.of() : file.getCustomTags())
                            .equals(new ArrayList<>(current))) {
                continue;
            }
            file.setCustomTags(current.isEmpty() ? null : new ArrayList<>(current));
            musicFileRepository.save(file);
            modified++;
        }

        int notFound = fileIds.size() - files.size();
        return new BulkTagResult(modified, notFound);
    }

    /** Variant of {@link #normalizeTags(List)} that returns an empty list for null input without throwing. */
    private static List<String> normalizeTagList(List<String> raw) {
        return raw == null ? List.of() : normalizeTags(raw);
    }

    public record TagCount(String tag, int count) {}

    public record BulkTagResult(int modified, int notFound) {}

    @Transactional
    public long bulkDelete(List<String> ids, String userId) {
        Query query = new Query(Criteria.where("_id").in(ids)
                .and("userId").is(userId)
                .and("deleted").ne(true));
        Update update = new Update()
                .set("deleted", true)
                .set("deletedAt", Instant.now());
        return mongoTemplate.updateMulti(query, update, MusicFile.class).getModifiedCount();
    }

    @Transactional
    public void deleteFile(MusicFile file, String userId) {
        file.setDeleted(true);
        file.setDeletedAt(Instant.now());
        musicFileRepository.save(file);
    }

    public List<MusicFileDTO> getTrash(String userId) {
        return musicFileRepository.findByUserIdAndDeletedTrue(userId).stream()
                .map(MusicFileDTO::from)
                .collect(Collectors.toList());
    }

    public Page<MusicFileDTO> getTrash(String userId, int page, int size) {
        size = clamp(size, MAX_PAGE_SIZE);
        return musicFileRepository.findByUserIdAndDeletedTrue(userId, PageRequest.of(page, size))
                .map(MusicFileDTO::from);
    }

    @Transactional
    public void restoreFile(String id, String userId) {
        Optional<MusicFile> opt = musicFileRepository.findByIdAndUserId(id, userId);
        if (opt.isEmpty() || !opt.get().isDeleted()) {
            throw new IllegalArgumentException("File not found in trash");
        }
        MusicFile file = opt.get();
        file.setDeleted(false);
        file.setDeletedAt(null);
        musicFileRepository.save(file);
    }

    @Transactional
    public void permanentlyDeleteFile(String id, String userId) {
        Optional<MusicFile> opt = musicFileRepository.findByIdAndUserId(id, userId);
        if (opt.isEmpty() || !opt.get().isDeleted()) {
            throw new IllegalArgumentException("File not found in trash");
        }
        musicFileRepository.delete(opt.get());
        removeFilesFromPlaylists(Set.of(id), userId);
    }

    @Transactional
    public void emptyTrash(String userId) {
        List<MusicFile> trashed = musicFileRepository.findByUserIdAndDeletedTrue(userId);
        if (trashed.isEmpty()) return;
        Set<String> trashedIds = trashed.stream().map(MusicFile::getId).collect(Collectors.toSet());
        musicFileRepository.deleteAll(trashed);
        removeFilesFromPlaylists(trashedIds, userId);
    }

    public List<MusicFile> getAllFiles(String userId) {
        return musicFileRepository.findByUserIdAndDeletedFalse(userId);
    }

    @Transactional
    public long clearLibrary(String userId) {
        long fileCount = musicFileRepository.deleteByUserId(userId);
        playlistRepository.deleteByUserId(userId);
        coverArtRepository.deleteByUserId(userId);
        playbackQueueRepository.deleteByUserId(userId);
        playEventRepository.deleteByUserId(userId);
        return fileCount;
    }

    public Optional<CoverArt> getCoverArt(String userId, String artist, String album) {
        return coverArtRepository.findByUserIdAndArtistAndAlbum(userId, artist, album);
    }

    public List<Map<String, Object>> findDuplicates(String userId) {
        return musicFileRepository.findDuplicatesByUserId(userId);
    }

    public Map<String, Object> findDuplicates(String userId, int page, int size) {
        size = clamp(size, MAX_PAGE_SIZE);
        return musicFileRepository.findDuplicatesByUserId(userId, page * size, size);
    }

    public Map<String, Object> findHashDuplicates(String userId, int page, int size) {
        size = clamp(size, MAX_PAGE_SIZE);
        return musicFileRepository.findHashDuplicatesByUserId(userId, page * size, size);
    }

    public Map<String, Object> getStatistics(String userId) {
        return musicFileRepository.getStatistics(userId);
    }

    public LibraryBackup createBackup(String userId, String username) {
        LibraryBackup backup = new LibraryBackup();
        backup.setExportedAt(Instant.now());
        backup.setUsername(username);

        List<MusicFile> files = musicFileRepository.findByUserIdAndDeletedFalse(userId);
        List<LibraryBackup.TrackBackup> tracks = files.stream().map(f -> {
            LibraryBackup.TrackBackup t = new LibraryBackup.TrackBackup();
            t.setFileName(f.getFileName());
            t.setFilePath(f.getFilePath());
            t.setArtist(f.getArtist());
            t.setAlbum(f.getAlbum());
            t.setTitle(f.getTitle());
            t.setYear(f.getYear());
            t.setGenre(f.getGenre() != null ? f.getGenre().name() : null);
            t.setAdditionalGenres(f.getAdditionalGenres() != null
                    ? f.getAdditionalGenres().stream().map(Genre::name).collect(Collectors.toList()) : null);
            t.setRating(f.getRating());
            t.setFileHash(f.getFileHash());
            return t;
        }).collect(Collectors.toList());
        backup.setTracks(tracks);

        // Map track IDs to file names for playlist portability
        Map<String, String> idToFileName = files.stream()
                .collect(Collectors.toMap(MusicFile::getId, MusicFile::getFileName, (a, b) -> a));

        List<Playlist> playlists = playlistRepository.findByUserId(userId);
        List<LibraryBackup.PlaylistBackup> playlistBackups = playlists.stream().map(p -> {
            LibraryBackup.PlaylistBackup pb = new LibraryBackup.PlaylistBackup();
            pb.setName(p.getName());
            pb.setTrackFileNames(p.getTrackIds().stream()
                    .map(id -> idToFileName.getOrDefault(id, id))
                    .collect(Collectors.toList()));
            return pb;
        }).collect(Collectors.toList());
        backup.setPlaylists(playlistBackups);

        return backup;
    }

    @Transactional
    public Map<String, Object> restoreBackup(LibraryBackup backup, String userId) {
        int tracksRestored = 0;
        int tracksSkipped = 0;
        int playlistsRestored = 0;

        // Fetch all user files once — reused for both dedup and playlist mapping
        List<MusicFile> allExisting = musicFileRepository.findByUserId(userId);
        Set<String> existingPaths = allExisting.stream()
                .map(MusicFile::getFilePath)
                .collect(Collectors.toSet());
        // Build initial fileName->ID map from existing non-deleted files
        Map<String, String> fileNameToId = allExisting.stream()
                .filter(f -> !f.isDeleted())
                .collect(Collectors.toMap(MusicFile::getFileName, MusicFile::getId, (a, b) -> a));

        // Restore tracks
        List<MusicFile> toSave = new ArrayList<>();
        for (LibraryBackup.TrackBackup t : backup.getTracks()) {
            if (t.getFilePath() != null && existingPaths.contains(t.getFilePath())) {
                tracksSkipped++;
                continue;
            }
            Genre genre = Genre.OTHER;
            if (t.getGenre() != null) {
                try { genre = Genre.valueOf(t.getGenre()); } catch (IllegalArgumentException ignored) {}
            }
            List<Genre> additional = null;
            if (t.getAdditionalGenres() != null) {
                additional = t.getAdditionalGenres().stream()
                        .map(g -> { try { return Genre.valueOf(g); } catch (IllegalArgumentException e) { return Genre.OTHER; } })
                        .collect(Collectors.toList());
            }
            MusicFile mf = MusicFile.builder()
                    .userId(userId)
                    .filePath(t.getFilePath())
                    .fileName(t.getFileName())
                    .artist(t.getArtist())
                    .album(t.getAlbum())
                    .title(t.getTitle())
                    .year(t.getYear())
                    .genre(genre)
                    .additionalGenres(additional)
                    .rating(t.getRating())
                    .fileHash(t.getFileHash())
                    .build();
            toSave.add(mf);
            tracksRestored++;
        }
        if (!toSave.isEmpty()) {
            List<MusicFile> saved = musicFileRepository.saveAll(toSave);
            // Add newly saved files to the fileName->ID map for playlist restoration
            for (MusicFile mf : saved) {
                fileNameToId.putIfAbsent(mf.getFileName(), mf.getId());
            }
        }

        if (backup.getPlaylists() != null) {
            for (LibraryBackup.PlaylistBackup pb : backup.getPlaylists()) {
                Playlist playlist = new Playlist();
                playlist.setName(pb.getName());
                playlist.setUserId(userId);
                List<String> trackIds = pb.getTrackFileNames().stream()
                        .map(fn -> fileNameToId.getOrDefault(fn, null))
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toList());
                playlist.setTrackIds(trackIds);
                playlistRepository.save(playlist);
                playlistsRestored++;
            }
        }

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("tracksRestored", tracksRestored);
        result.put("tracksSkipped", tracksSkipped);
        result.put("playlistsRestored", playlistsRestored);
        return result;
    }

    private void removeFilesFromPlaylists(Set<String> fileIds, String userId) {
        List<Playlist> playlists = playlistRepository.findByUserId(userId);
        List<Playlist> modified = new ArrayList<>();
        for (Playlist p : playlists) {
            if (p.getTrackIds().removeAll(fileIds)) {
                modified.add(p);
            }
        }
        if (!modified.isEmpty()) {
            playlistRepository.saveAll(modified);
        }
    }
}
