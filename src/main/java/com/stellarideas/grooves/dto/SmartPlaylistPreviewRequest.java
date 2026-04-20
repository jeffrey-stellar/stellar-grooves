package com.stellarideas.grooves.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class SmartPlaylistPreviewRequest {

    @NotBlank
    @Size(max = 1000)
    private String queryString;

    public String getQueryString() { return queryString; }
    public void setQueryString(String queryString) { this.queryString = queryString; }
}
