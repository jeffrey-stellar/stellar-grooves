package com.stellarideas.grooves.service;

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

@Service
public class LibraryService {

    private static final int MAX_PAGE_SIZE = 200;

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
        size = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return (genre == null)
                ? musicFileRepository.findByUserIdAndDeletedFalse(userId, PageRequest.of(page, size))
                : musicFileRepository.findByUserIdAndGenreAndDeletedFalse(userId, genre, PageRequest.of(page, size));
    }

    public Page<MusicFile> searchFiles(String userId, String query, int page, int size) {
        size = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        try {
            // Try text search first (uses MongoDB text index with relevance scoring)
            Page<MusicFile> results = musicFileRepository.textSearch(userId, query, PageRequest.of(page, size));
            if (results.getTotalElements() > 0) {
                return results;
            }
        } catch (Exception e) {
            // Text index may not exist yet — fall through to regex
        }
        // Fallback to regex search
        String escaped = query.replaceAll("([\\\\.*+?^${}()|\\[\\]])", "\\\\$1");
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
        size = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return musicFileRepository.findDuplicatesByUserId(userId, page * size, size);
    }

    public Map<String, Object> getStatistics(String userId) {
        return musicFileRepository.getStatistics(userId);
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
