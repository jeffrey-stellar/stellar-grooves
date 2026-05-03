package com.stellarideas.grooves.dto;

import com.stellarideas.grooves.model.SmartPlaylistPhrase;

import java.time.Instant;

public class SmartPlaylistPhraseDTO {

    private String id;
    private String name;
    private String body;
    private String description;
    private Instant createdAt;
    private Instant updatedAt;

    public static SmartPlaylistPhraseDTO from(SmartPlaylistPhrase p) {
        SmartPlaylistPhraseDTO dto = new SmartPlaylistPhraseDTO();
        dto.id = p.getId();
        dto.name = p.getName();
        dto.body = p.getBody();
        dto.description = p.getDescription();
        dto.createdAt = p.getCreatedAt();
        dto.updatedAt = p.getUpdatedAt();
        return dto;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
