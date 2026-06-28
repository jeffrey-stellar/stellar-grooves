package com.stellarideas.grooves.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Records that an external cover-art lookup for an album turned up nothing, so repeated
 * "fetch missing art" runs don't re-hammer the third-party APIs for the same album. The
 * orchestrator skips albums with a recent attempt; a successful fetch removes the record.
 */
@Document(collection = "cover_art_miss")
public class CoverArtMiss {

    @Id
    private String id;

    @Indexed
    private String userId;

    private String artist;
    private String album;
    private int attempts;
    private Instant lastAttemptAt;

    public CoverArtMiss() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getArtist() { return artist; }
    public void setArtist(String artist) { this.artist = artist; }

    public String getAlbum() { return album; }
    public void setAlbum(String album) { this.album = album; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public void setLastAttemptAt(Instant lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }
}
