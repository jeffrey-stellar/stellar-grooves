package com.stellarideas.grooves.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "play_events")
@CompoundIndexes({
    @CompoundIndex(name = "user_file_time", def = "{'userId': 1, 'musicFileId': 1, 'playedAt': -1}"),
    @CompoundIndex(name = "user_time", def = "{'userId': 1, 'playedAt': -1}")
})
public class PlayEvent {

    @Id
    private String id;

    private String userId;
    private String musicFileId;
    private Instant playedAt;
    private int listenedMs;
    private boolean completed;

    public PlayEvent() {}

    public PlayEvent(String userId, String musicFileId, Instant playedAt, int listenedMs, boolean completed) {
        this.userId = userId;
        this.musicFileId = musicFileId;
        this.playedAt = playedAt;
        this.listenedMs = listenedMs;
        this.completed = completed;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getMusicFileId() { return musicFileId; }
    public void setMusicFileId(String musicFileId) { this.musicFileId = musicFileId; }

    public Instant getPlayedAt() { return playedAt; }
    public void setPlayedAt(Instant playedAt) { this.playedAt = playedAt; }

    public int getListenedMs() { return listenedMs; }
    public void setListenedMs(int listenedMs) { this.listenedMs = listenedMs; }

    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }
}
