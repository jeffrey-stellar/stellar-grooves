package com.stellarideas.grooves.dto;

import jakarta.validation.constraints.NotBlank;

public class UpdateGenreRequest {

    @NotBlank(message = "Genre is required")
    private String genre;

    public String getGenre() { return genre; }
    public void setGenre(String genre) { this.genre = genre; }
}
