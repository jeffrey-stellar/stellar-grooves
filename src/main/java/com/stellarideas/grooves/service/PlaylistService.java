package com.stellarideas.grooves.service;

import com.stellarideas.grooves.dto.MusicFileDTO;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.Playlist;
import com.stellarideas.grooves.repository.MusicFileRepository;
import com.stellarideas.grooves.repository.PlaylistRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PlaylistService {

    private final PlaylistRepository playlistRepository;
    private final MusicFileRepository musicFileRepository;

    public PlaylistService(PlaylistRepository playlistRepository, MusicFileRepository musicFileRepository) {
        this.playlistRepository = playlistRepository;
        this.musicFileRepository = musicFileRepository;
    }

    public List<Playlist> getPlaylists(String userId) {
        return playlistRepository.findByUserId(userId);
    }

    public Playlist createPlaylist(String name, String userId) {
        Playlist playlist = new Playlist();
        playlist.setName(name.trim());
        playlist.setUserId(userId);
        return playlistRepository.save(playlist);
    }

    public Optional<Playlist> findByIdAndUserId(String id, String userId) {
        return playlistRepository.findByIdAndUserId(id, userId);
    }

    public void deletePlaylist(Playlist playlist) {
        playlistRepository.delete(playlist);
    }

    public boolean addTrack(Playlist playlist, String fileId, String userId) {
        if (musicFileRepository.findByIdAndUserIdAndDeletedFalse(fileId, userId).isEmpty()) {
            return false;
        }
        if (!playlist.getTrackIds().contains(fileId)) {
            playlist.getTrackIds().add(fileId);
            playlistRepository.save(playlist);
        }
        return true;
    }

    public void removeTrack(Playlist playlist, String fileId) {
        playlist.getTrackIds().remove(fileId);
        playlistRepository.save(playlist);
    }

    public Map<String, Object> getPlaylistTracks(Playlist playlist, String userId) {
        List<String> trackIds = playlist.getTrackIds();
        if (trackIds.isEmpty()) {
            return Map.of("tracks", List.of(), "missingTracks", List.of());
        }
        Map<String, MusicFile> byId = musicFileRepository.findByIdInAndUserId(trackIds, userId).stream()
                .collect(Collectors.toMap(MusicFile::getId, f -> f));
        List<MusicFileDTO> tracks = trackIds.stream()
                .filter(byId::containsKey)
                .map(byId::get)
                .map(MusicFileDTO::from)
                .collect(Collectors.toList());
        List<String> missingTracks = trackIds.stream()
                .filter(id -> !byId.containsKey(id))
                .collect(Collectors.toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tracks", tracks);
        result.put("missingTracks", missingTracks);
        return result;
    }

    /**
     * Paginated variant: hydrate only the trackIds in the requested page, preserving
     * the user-defined playlist order. Avoids loading thousands of tracks into memory
     * for large playlists.
     *
     * <p>Returns a map with {@code tracks} (page slice), {@code missingTracks} (ids from
     * this page's slice that no longer exist), {@code totalTracks} (the playlist's full
     * track count), {@code page}, and {@code size}.
     */
    public Map<String, Object> getPlaylistTracks(Playlist playlist, String userId, int page, int size) {
        if (page < 0) page = 0;
        if (size <= 0) size = 50;
        if (size > 500) size = 500;

        List<String> trackIds = playlist.getTrackIds();
        int total = trackIds.size();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalTracks", total);
        result.put("page", page);
        result.put("size", size);

        if (total == 0) {
            result.put("tracks", List.of());
            result.put("missingTracks", List.of());
            return result;
        }

        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        List<String> slice = trackIds.subList(from, to);
        if (slice.isEmpty()) {
            result.put("tracks", List.of());
            result.put("missingTracks", List.of());
            return result;
        }

        Map<String, MusicFile> byId = musicFileRepository.findByIdInAndUserId(slice, userId).stream()
                .collect(Collectors.toMap(MusicFile::getId, f -> f));
        List<MusicFileDTO> tracks = slice.stream()
                .filter(byId::containsKey)
                .map(byId::get)
                .map(MusicFileDTO::from)
                .collect(Collectors.toList());
        List<String> missingTracks = slice.stream()
                .filter(id -> !byId.containsKey(id))
                .collect(Collectors.toList());
        result.put("tracks", tracks);
        result.put("missingTracks", missingTracks);
        return result;
    }

    public boolean reorderTracks(Playlist playlist, List<String> newOrder) {
        Set<String> existing = new HashSet<>(playlist.getTrackIds());
        Set<String> incoming = new HashSet<>(newOrder);
        if (!existing.equals(incoming)) return false;
        playlist.setTrackIds(new ArrayList<>(newOrder));
        playlistRepository.save(playlist);
        return true;
    }

    /**
     * Generate a share token with an optional expiration.
     * @param playlist the playlist to share
     * @param expirationDays number of days until the token expires, or null for no expiration
     * @return the generated share token
     */
    public String generateShareToken(Playlist playlist, Integer expirationDays) {
        String token = UUID.randomUUID().toString();
        playlist.setShareToken(token);
        if (expirationDays != null && expirationDays > 0) {
            playlist.setShareTokenExpiresAt(Instant.now().plus(Duration.ofDays(expirationDays)));
        } else {
            playlist.setShareTokenExpiresAt(null);
        }
        playlistRepository.save(playlist);
        return token;
    }

    public void revokeShareToken(Playlist playlist) {
        playlist.setShareToken(null);
        playlist.setShareTokenExpiresAt(null);
        playlistRepository.save(playlist);
    }

    public Optional<Playlist> findByShareToken(String shareToken) {
        return playlistRepository.findByShareToken(shareToken);
    }

    public List<MusicFile> getOrderedFiles(Playlist playlist, String userId) {
        List<String> trackIds = playlist.getTrackIds();
        if (trackIds.isEmpty()) return List.of();
        Map<String, MusicFile> byId = musicFileRepository.findByIdInAndUserId(trackIds, userId).stream()
                .collect(Collectors.toMap(MusicFile::getId, f -> f));
        return trackIds.stream()
                .filter(byId::containsKey)
                .map(byId::get)
                .collect(Collectors.toList());
    }
}
