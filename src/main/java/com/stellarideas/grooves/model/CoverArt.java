package com.stellarideas.grooves.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Stores album cover art as binary data in MongoDB.
 * Keyed by a hash of artist+album so duplicate art is stored only once per user.
 */
@Document(collection = "cover_art")
public class CoverArt {

    @Id
    private String id;

    @Indexed
    private String userId;

    private String artist;
    private String album;
    private String mimeType;
    private byte[] data;

    /** Where the art came from: "embedded" (tag), "folder" (sidecar image), etc. May be null for legacy docs. */
    private String source;

    public CoverArt() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getArtist() { return artist; }
    public void setArtist(String artist) { this.artist = artist; }

    public String getAlbum() { return album; }
    public void setAlbum(String album) { this.album = album; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public byte[] getData() { return data; }
    public void setData(byte[] data) { this.data = data; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CoverArt that = (CoverArt) o;
        return java.util.Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(id);
    }
}
