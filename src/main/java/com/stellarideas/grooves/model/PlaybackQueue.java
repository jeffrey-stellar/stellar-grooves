package com.stellarideas.grooves.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists the user's playback queue so it can be resumed across sessions and devices.
 * One document per user.
 */
@Document(collection = "playback_queues")
public class PlaybackQueue {

    @Id
    private String id;

    @Indexed(unique = true)
    private String userId;

    private List<String> trackIds = new ArrayList<>();

    private String currentTrackId;

    private boolean shuffle;

    @LastModifiedDate
    private Instant updatedAt;

    public PlaybackQueue() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public List<String> getTrackIds() { return trackIds; }
    public void setTrackIds(List<String> trackIds) { this.trackIds = trackIds; }

    public String getCurrentTrackId() { return currentTrackId; }
    public void setCurrentTrackId(String currentTrackId) { this.currentTrackId = currentTrackId; }

    public boolean isShuffle() { return shuffle; }
    public void setShuffle(boolean shuffle) { this.shuffle = shuffle; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlaybackQueue that = (PlaybackQueue) o;
        return java.util.Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(id);
    }
}
