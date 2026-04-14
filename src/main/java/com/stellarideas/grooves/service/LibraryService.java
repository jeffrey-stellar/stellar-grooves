package com.stellarideas.grooves.service;

import com.stellarideas.grooves.dto.LibraryBackup;
import com.stellarideas.grooves.dto.MusicFileDTO;
import com.stellarideas.grooves.model.*;
import com.stellarideas.grooves.repository.CoverArtRepository;
import com.stellarideas.grooves.repository.MusicFileRepository;
import com.stellarideas.grooves.repository.PlaybackQueueRepository;
import com.stellarideas.grooves.repository.PlaylistRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
    private final MusicCatalogService catalogService;

    public LibraryService(MusicFileRepository musicFileRepository,
                          PlaylistRepository playlistRepository,
                          CoverArtRepository coverArtRepository,
                          PlaybackQueueRepository playbackQueueRepository,
                          MusicCatalogService catalogService) {
        this.musicFileRepository = musicFileRepository;
        this.playlistRepository = playlistRepository;
        this.coverArtRepository = coverArtRepository;
        this.playbackQueueRepository = playbackQueueRepository;
        this.catalogService = catalogService;
    }

    public Page<MusicFile> getFiles(String userId, Genre genre, int page, int size) {
        size = clamp(size, MAX_PAGE_SIZE);
        return (genre == null)
                ? musicFileRepository.findByUserIdAndDeletedFalse(userId, PageRequest.of(page, size))
                : musicFileRepository.findByUserIdAndGenreAndDeletedFalse(userId, genre, PageRequest.of(page, size));
    }

    private static final int MAX_SEARCH_QUERY_LENGTH = 200;

    public Page<MusicFile> searchFiles(String userId, String query, int page, int size) {
        return searchFiles(userId, query, null, null, null, null, page, size);
    }

    public Page<MusicFile> searchFiles(String userId, String query,
                                       Genre genre, String artist, String year, String fileExtension,
                                       int page, int size) {
        size = clamp(size, MAX_PAGE_SIZE);
        boolean hasFilters = genre != null || (artist != null && !artist.isBlank())
                || (year != null && !year.isBlank()) || (fileExtension != null && !fileExtension.isBlank());
        boolean hasQuery = query != null && !query.isBlank() && query.length() <= MAX_SEARCH_QUERY_LENGTH;

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
        String escaped = java.util.regex.Pattern.quote(query);
        return musicFileRepository.searchByUserIdAndQuery(userId, escaped, PageRequest.of(page, size));
    }

    public Optional<MusicFile> findFileByIdAndUserId(String id, String userId) {
        return musicFileRepository.findByIdAndUserIdAndDeletedFalse(id, userId);
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

    @Transactional
    public int bulkDelete(List<String> ids, String userId) {
        List<MusicFile> files = musicFileRepository.findByIdInAndUserId(ids, userId);
        if (files.isEmpty()) return 0;
        Instant now = Instant.now();
        for (MusicFile file : files) {
            file.setDeleted(true);
            file.setDeletedAt(now);
        }
        musicFileRepository.saveAll(files);
        return files.size();
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

        // Build set of existing file paths to avoid duplicates
        Set<String> existingPaths = musicFileRepository.findByUserId(userId).stream()
                .map(MusicFile::getFilePath)
                .collect(Collectors.toSet());

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
            musicFileRepository.saveAll(toSave);
        }

        // Restore playlists — map file names back to IDs
        Map<String, String> fileNameToId = musicFileRepository.findByUserIdAndDeletedFalse(userId).stream()
                .collect(Collectors.toMap(MusicFile::getFileName, MusicFile::getId, (a, b) -> a));

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
