package com.stellarideas.grooves.dto;

import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

public class MusicFileDTO {

    private String id;
    private String fileName;
    private String artist;
    private String album;
    private String title;
    private String year;
    private String genre;
    private List<String> additionalGenres;
    private int rating;
    private boolean hasCoverArt;
    private List<String> customTags;
    private Instant createdAt;

    public MusicFileDTO() {}

    public static MusicFileDTO from(MusicFile file) {
        MusicFileDTO dto = new MusicFileDTO();
        dto.id = file.getId();
        dto.fileName = file.getFileName();
        dto.artist = file.getArtist();
        dto.album = file.getAlbum();
        dto.title = file.getTitle();
        dto.year = file.getYear();
        dto.genre = file.getGenre() != null ? file.getGenre().name() : null;
        dto.additionalGenres = file.getAdditionalGenres() != null
                ? file.getAdditionalGenres().stream().map(Genre::name).collect(Collectors.toList())
                : null;
        dto.rating = file.getRating();
        dto.hasCoverArt = file.isHasCoverArt();
        dto.customTags = file.getCustomTags();
        dto.createdAt = file.getCreatedAt();
        return dto;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getArtist() { return artist; }
    public void setArtist(String artist) { this.artist = artist; }

    public String getAlbum() { return album; }
    public void setAlbum(String album) { this.album = album; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getYear() { return year; }
    public void setYear(String year) { this.year = year; }

    public String getGenre() { return genre; }
    public void setGenre(String genre) { this.genre = genre; }

    public int getRating() { return rating; }
    public void setRating(int rating) { this.rating = rating; }

    public boolean isHasCoverArt() { return hasCoverArt; }
    public void setHasCoverArt(boolean hasCoverArt) { this.hasCoverArt = hasCoverArt; }

    public List<String> getAdditionalGenres() { return additionalGenres; }
    public void setAdditionalGenres(List<String> additionalGenres) { this.additionalGenres = additionalGenres; }

    public List<String> getCustomTags() { return customTags; }
    public void setCustomTags(List<String> customTags) { this.customTags = customTags; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
