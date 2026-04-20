package com.stellarideas.grooves.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class SmartPlaylistRequest {

    @NotBlank
    @Size(min = 1, max = 100)
    private String name;

    @NotBlank
    @Size(max = 1000)
    private String queryString;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getQueryString() { return queryString; }
    public void setQueryString(String queryString) { this.queryString = queryString; }
}
