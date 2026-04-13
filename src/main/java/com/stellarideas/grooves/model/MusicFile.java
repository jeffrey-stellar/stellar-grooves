package com.stellarideas.grooves.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Size;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "music_files")
@CompoundIndexes({
    @CompoundIndex(name = "user_genre", def = "{'userId': 1, 'genre': 1}"),
    @CompoundIndex(name = "user_filepath", def = "{'userId': 1, 'filePath': 1}", unique = true),
    @CompoundIndex(name = "user_title_artist", def = "{'userId': 1, 'title': 1, 'artist': 1}"),
    @CompoundIndex(name = "user_rating", def = "{'userId': 1, 'rating': -1}"),
    @CompoundIndex(name = "user_search_title", def = "{'userId': 1, 'title': 1}"),
    @CompoundIndex(name = "user_search_artist", def = "{'userId': 1, 'artist': 1}"),
    @CompoundIndex(name = "user_search_album", def = "{'userId': 1, 'album': 1}"),
    @CompoundIndex(name = "user_deleted", def = "{'userId': 1, 'deleted': 1}")
})
public class MusicFile {
    @Id
    private String id;

    @JsonIgnore
    private String filePath;
    @Size(max = 500)
    private String fileName;
    @Size(max = 200)
    private String artist;
    @Size(max = 200)
    private String album;
    @Size(max = 200)
    private String title;
    @Size(max = 10)
    private String year;

    @Indexed
    private Genre genre;

    @JsonIgnore
    private String userId;

    private int rating; // 0-5, 0 = unrated

    private boolean hasCoverArt;

    private boolean deleted = false;
    private Instant deletedAt;

    public MusicFile() {}

    public MusicFile(String id, String filePath, String fileName, String artist, String album,
                     String title, String year, Genre genre, String userId, int rating, boolean hasCoverArt,
                     boolean deleted, Instant deletedAt) {
        this.id = id;
        this.filePath = filePath;
        this.fileName = fileName;
        this.artist = artist;
        this.album = album;
        this.title = title;
        this.year = year;
        this.genre = genre;
        this.userId = userId;
        this.rating = rating;
        this.hasCoverArt = hasCoverArt;
        this.deleted = deleted;
        this.deletedAt = deletedAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    @JsonIgnore
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

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

    public Genre getGenre() { return genre; }
    public void setGenre(Genre genre) { this.genre = genre; }

    @JsonIgnore
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public int getRating() { return rating; }
    public void setRating(int rating) { this.rating = Math.max(0, Math.min(5, rating)); }

    public boolean isHasCoverArt() { return hasCoverArt; }
    public void setHasCoverArt(boolean hasCoverArt) { this.hasCoverArt = hasCoverArt; }

    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }

    public static MusicFileBuilder builder() {
        return new MusicFileBuilder();
    }

    public static class MusicFileBuilder {
        private String id;
        private String filePath;
        private String fileName;
        private String artist;
        private String album;
        private String title;
        private String year;
        private Genre genre;
        private String userId;
        private int rating;
        private boolean hasCoverArt;
        private boolean deleted = false;
        private Instant deletedAt;

        MusicFileBuilder() {}

        public MusicFileBuilder id(String id) { this.id = id; return this; }
        public MusicFileBuilder filePath(String filePath) { this.filePath = filePath; return this; }
        public MusicFileBuilder fileName(String fileName) { this.fileName = fileName; return this; }
        public MusicFileBuilder artist(String artist) { this.artist = artist; return this; }
        public MusicFileBuilder album(String album) { this.album = album; return this; }
        public MusicFileBuilder title(String title) { this.title = title; return this; }
        public MusicFileBuilder year(String year) { this.year = year; return this; }
        public MusicFileBuilder genre(Genre genre) { this.genre = genre; return this; }
        public MusicFileBuilder userId(String userId) { this.userId = userId; return this; }
        public MusicFileBuilder rating(int rating) { this.rating = rating; return this; }
        public MusicFileBuilder hasCoverArt(boolean hasCoverArt) { this.hasCoverArt = hasCoverArt; return this; }
        public MusicFileBuilder deleted(boolean deleted) { this.deleted = deleted; return this; }
        public MusicFileBuilder deletedAt(Instant deletedAt) { this.deletedAt = deletedAt; return this; }

        public MusicFile build() {
            return new MusicFile(id, filePath, fileName, artist, album, title, year, genre, userId, rating, hasCoverArt, deleted, deletedAt);
        }
    }
}
