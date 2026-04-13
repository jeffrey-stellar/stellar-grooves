package com.stellarideas.grooves.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Size;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "music_files")
@CompoundIndexes({
    @CompoundIndex(name = "user_genre", def = "{'user.$id': 1, 'genre': 1}"),
    @CompoundIndex(name = "user_filepath", def = "{'user.$id': 1, 'filePath': 1}", unique = true),
    @CompoundIndex(name = "user_title_artist", def = "{'user.$id': 1, 'title': 1, 'artist': 1}")
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

    private Genre genre;

    @JsonIgnore
    @DBRef
    private User user;

    public MusicFile() {}

    public MusicFile(String id, String filePath, String fileName, String artist, String album,
                     String title, String year, Genre genre, User user) {
        this.id = id;
        this.filePath = filePath;
        this.fileName = fileName;
        this.artist = artist;
        this.album = album;
        this.title = title;
        this.year = year;
        this.genre = genre;
        this.user = user;
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
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

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
        private User user;

        MusicFileBuilder() {}

        public MusicFileBuilder id(String id) { this.id = id; return this; }
        public MusicFileBuilder filePath(String filePath) { this.filePath = filePath; return this; }
        public MusicFileBuilder fileName(String fileName) { this.fileName = fileName; return this; }
        public MusicFileBuilder artist(String artist) { this.artist = artist; return this; }
        public MusicFileBuilder album(String album) { this.album = album; return this; }
        public MusicFileBuilder title(String title) { this.title = title; return this; }
        public MusicFileBuilder year(String year) { this.year = year; return this; }
        public MusicFileBuilder genre(Genre genre) { this.genre = genre; return this; }
        public MusicFileBuilder user(User user) { this.user = user; return this; }

        public MusicFile build() {
            return new MusicFile(id, filePath, fileName, artist, album, title, year, genre, user);
        }
    }
}
