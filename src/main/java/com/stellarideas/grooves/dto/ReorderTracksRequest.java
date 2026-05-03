package com.stellarideas.grooves.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public class ReorderTracksRequest {

    @NotEmpty
    @Size(max = 5000, message = "Cannot reorder more than 5000 tracks at once")
    private List<@Size(max = 64, message = "Each trackId must not exceed 64 characters") String> trackIds;

    public List<String> getTrackIds() { return trackIds; }
    public void setTrackIds(List<String> trackIds) { this.trackIds = trackIds; }
}
