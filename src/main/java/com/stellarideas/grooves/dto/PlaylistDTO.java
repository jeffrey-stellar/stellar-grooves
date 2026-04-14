package com.stellarideas.grooves.dto;

import com.stellarideas.grooves.model.Playlist;

import java.time.Instant;

public class PlaylistDTO {

    private String id;
    private String name;
    private int trackCount;
    private String shareToken;
    private Instant shareTokenExpiresAt;

    public PlaylistDTO() {}

    public static PlaylistDTO from(Playlist playlist) {
        PlaylistDTO dto = new PlaylistDTO();
        dto.id = playlist.getId();
        dto.name = playlist.getName();
        dto.trackCount = playlist.getTrackIds().size();
        dto.shareToken = playlist.getShareToken();
        dto.shareTokenExpiresAt = playlist.getShareTokenExpiresAt();
        return dto;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getTrackCount() { return trackCount; }
    public void setTrackCount(int trackCount) { this.trackCount = trackCount; }

    public String getShareToken() { return shareToken; }
    public void setShareToken(String shareToken) { this.shareToken = shareToken; }

    public Instant getShareTokenExpiresAt() { return shareTokenExpiresAt; }
    public void setShareTokenExpiresAt(Instant shareTokenExpiresAt) { this.shareTokenExpiresAt = shareTokenExpiresAt; }
}
