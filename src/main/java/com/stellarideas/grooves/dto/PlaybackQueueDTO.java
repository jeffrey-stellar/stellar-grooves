package com.stellarideas.grooves.dto;

import com.stellarideas.grooves.model.PlaybackQueue;

import java.util.List;

public class PlaybackQueueDTO {

    private List<String> trackIds;
    private String currentTrackId;
    private boolean shuffle;

    public PlaybackQueueDTO() {}

    public static PlaybackQueueDTO from(PlaybackQueue queue) {
        PlaybackQueueDTO dto = new PlaybackQueueDTO();
        dto.setTrackIds(queue.getTrackIds());
        dto.setCurrentTrackId(queue.getCurrentTrackId());
        dto.setShuffle(queue.isShuffle());
        return dto;
    }

    public List<String> getTrackIds() { return trackIds; }
    public void setTrackIds(List<String> trackIds) { this.trackIds = trackIds; }

    public String getCurrentTrackId() { return currentTrackId; }
    public void setCurrentTrackId(String currentTrackId) { this.currentTrackId = currentTrackId; }

    public boolean isShuffle() { return shuffle; }
    public void setShuffle(boolean shuffle) { this.shuffle = shuffle; }
}
