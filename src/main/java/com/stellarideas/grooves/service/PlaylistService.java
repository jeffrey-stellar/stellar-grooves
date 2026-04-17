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
