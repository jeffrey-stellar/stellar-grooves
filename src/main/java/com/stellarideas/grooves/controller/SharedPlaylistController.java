package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.dto.MusicFileDTO;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.Playlist;
import com.stellarideas.grooves.service.PlaylistService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/shared/playlists")
public class SharedPlaylistController {

    private final PlaylistService playlistService;

    public SharedPlaylistController(PlaylistService playlistService) {
        this.playlistService = playlistService;
    }

    @GetMapping("/{token}")
    public ResponseEntity<?> getSharedPlaylist(@PathVariable String token) {
        Optional<Playlist> opt = playlistService.findByShareToken(token);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Playlist playlist = opt.get();
        List<MusicFile> files = playlistService.getOrderedFiles(playlist, playlist.getUserId());
        List<Map<String, Object>> tracks = files.stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", f.getTitle());
            m.put("artist", f.getArtist());
            m.put("album", f.getAlbum());
            m.put("year", f.getYear());
            m.put("genre", f.getGenre() != null ? f.getGenre().name() : null);
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", playlist.getName());
        body.put("trackCount", tracks.size());
        body.put("tracks", tracks);
        return ResponseEntity.ok(body);
    }
}
