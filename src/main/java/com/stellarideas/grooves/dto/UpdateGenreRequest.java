package com.stellarideas.grooves.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class UpdateGenreRequest {

    @NotBlank(message = "Genre is required")
    @Size(max = 50, message = "Genre must not exceed 50 characters")
    private String genre;

    public String getGenre() { return genre; }
    public void setGenre(String genre) { this.genre = genre; }
}
