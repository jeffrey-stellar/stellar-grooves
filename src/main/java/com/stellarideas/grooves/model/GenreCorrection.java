package com.stellarideas.grooves.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Records a user's genre correction for an artist. When a user updates the genre
 * of a track, the correction is stored here so future scans for the same artist
 * use the corrected genre instead of the static catalog default.
 */
@Document(collection = "genre_corrections")
public class GenreCorrection {

    @Id
    private String id;

    private String artist;

    @Indexed(unique = true)
    private String artistLower;
    private Genre genre;
    private String correctedByUserId;
    private Instant correctedAt;

    public GenreCorrection() {}

    public GenreCorrection(String artist, Genre genre, String correctedByUserId) {
        this.artist = artist;
        this.artistLower = artist != null ? artist.toLowerCase() : "";
        this.genre = genre;
        this.correctedByUserId = correctedByUserId;
        this.correctedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getArtist() { return artist; }
    public void setArtist(String artist) {
        this.artist = artist;
        this.artistLower = artist != null ? artist.toLowerCase() : "";
    }

    public String getArtistLower() { return artistLower; }

    public Genre getGenre() { return genre; }
    public void setGenre(Genre genre) { this.genre = genre; }

    public String getCorrectedByUserId() { return correctedByUserId; }
    public void setCorrectedByUserId(String correctedByUserId) { this.correctedByUserId = correctedByUserId; }

    public Instant getCorrectedAt() { return correctedAt; }
    public void setCorrectedAt(Instant correctedAt) { this.correctedAt = correctedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GenreCorrection that = (GenreCorrection) o;
        return java.util.Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(id);
    }
}
