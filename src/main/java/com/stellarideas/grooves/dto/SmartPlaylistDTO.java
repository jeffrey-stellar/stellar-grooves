package com.stellarideas.grooves.dto;

import com.stellarideas.grooves.model.SmartPlaylist;

import java.time.Instant;

public class SmartPlaylistDTO {

    private String id;
    private String name;
    private String queryString;
    private Instant createdAt;
    private Instant updatedAt;

    public static SmartPlaylistDTO from(SmartPlaylist sp) {
        SmartPlaylistDTO dto = new SmartPlaylistDTO();
        dto.id = sp.getId();
        dto.name = sp.getName();
        dto.queryString = sp.getQueryString();
        dto.createdAt = sp.getCreatedAt();
        dto.updatedAt = sp.getUpdatedAt();
        return dto;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getQueryString() { return queryString; }
    public void setQueryString(String queryString) { this.queryString = queryString; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
