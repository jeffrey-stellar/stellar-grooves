package com.stellarideas.grooves.dto;

import com.stellarideas.grooves.model.MusicFile;

public class MusicFileDTO {

    private String id;
    private String fileName;
    private String artist;
    private String album;
    private String title;
    private String year;
    private String genre;

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
}
