package com.stellarideas.grooves.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class MusicFileStorageFieldsTest {

    @Test
    void defaultsToLocalSource() {
        assertEquals("local", new MusicFile().getSourceType());
        assertNull(new MusicFile().getStorageKey());
    }

    @Test
    void builderDefaultsToLocal() {
        MusicFile f = MusicFile.builder().id("1").fileName("song.mp3").build();
        assertEquals("local", f.getSourceType());
        assertNull(f.getStorageKey());
    }

    @Test
    void builderSetsObjectStorageFields() {
        MusicFile f =
                MusicFile.builder()
                        .id("1")
                        .sourceType("s3")
                        .storageKey("Artist/Album/song.mp3")
                        .build();
        assertEquals("s3", f.getSourceType());
        assertEquals("Artist/Album/song.mp3", f.getStorageKey());
    }
}
