package com.stellarideas.grooves.dto;

import com.stellarideas.grooves.model.Playlist;

public class PlaylistDTO {

    private String id;
    private String name;
    private int trackCount;
    private String shareToken;

    public PlaylistDTO() {}

    public static PlaylistDTO from(Playlist playlist) {
        PlaylistDTO dto = new PlaylistDTO();
        dto.id = playlist.getId();
        dto.name = playlist.getName();
        dto.trackCount = playlist.getTrackIds().size();
        dto.shareToken = playlist.getShareToken();
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
}
