package com.stellarideas.grooves.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "music_files")
public class MusicFile {
    @Id
    private String id;

    private String filePath;
    private String fileName;
    private String artist;
    private String album;
    private String title;
    private String year;
    
    private Genre primaryGenre;
    
    private boolean multiGenre;
    
    @DBRef
    private User user;

    public MusicFile() {
    }

    public MusicFile(String id, String filePath, String fileName, String artist, String album, String title, String year, Genre primaryGenre, boolean multiGenre, User user) {
        this.id = id;
        this.filePath = filePath;
        this.fileName = fileName;
        this.artist = artist;
        this.album = album;
        this.title = title;
        this.year = year;
        this.primaryGenre = primaryGenre;
        this.multiGenre = multiGenre;
        this.user = user;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getAlbum() {
        return album;
    }

    public void setAlbum(String album) {
        this.album = album;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getYear() {
        return year;
    }

    public void setYear(String year) {
        this.year = year;
    }

    public Genre getPrimaryGenre() {
        return primaryGenre;
    }

    public void setPrimaryGenre(Genre primaryGenre) {
        this.primaryGenre = primaryGenre;
    }

    public boolean isMultiGenre() {
        return multiGenre;
    }

    public void setMultiGenre(boolean multiGenre) {
        this.multiGenre = multiGenre;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

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
        private Genre primaryGenre;
        private boolean multiGenre;
        private User user;

        MusicFileBuilder() {
        }

        public MusicFileBuilder id(String id) {
            this.id = id;
            return this;
        }

        public MusicFileBuilder filePath(String filePath) {
            this.filePath = filePath;
            return this;
        }

        public MusicFileBuilder fileName(String fileName) {
            this.fileName = fileName;
            return this;
        }

        public MusicFileBuilder artist(String artist) {
            this.artist = artist;
            return this;
        }

        public MusicFileBuilder album(String album) {
            this.album = album;
            return this;
        }

        public MusicFileBuilder title(String title) {
            this.title = title;
            return this;
        }

        public MusicFileBuilder year(String year) {
            this.year = year;
            return this;
        }

        public MusicFileBuilder primaryGenre(Genre primaryGenre) {
            this.primaryGenre = primaryGenre;
            return this;
        }

        public MusicFileBuilder multiGenre(boolean multiGenre) {
            this.multiGenre = multiGenre;
            return this;
        }

        public MusicFileBuilder user(User user) {
            this.user = user;
            return this;
        }

        public MusicFile build() {
            return new MusicFile(id, filePath, fileName, artist, album, title, year, primaryGenre, multiGenre, user);
        }
    }
}
