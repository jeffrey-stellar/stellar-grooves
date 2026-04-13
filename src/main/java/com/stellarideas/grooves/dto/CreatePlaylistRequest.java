package com.stellarideas.grooves.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CreatePlaylistRequest {

    @NotBlank(message = "Playlist name is required")
    @Size(max = 80, message = "Playlist name must be 80 characters or less")
    private String name;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
