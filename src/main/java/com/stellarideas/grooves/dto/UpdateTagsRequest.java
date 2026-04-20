package com.stellarideas.grooves.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

public class UpdateTagsRequest {

    @Size(max = 20, message = "A track can have at most 20 tags")
    private List<@Size(max = 50, message = "Each tag must be 50 characters or fewer") String> tags;

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
}
