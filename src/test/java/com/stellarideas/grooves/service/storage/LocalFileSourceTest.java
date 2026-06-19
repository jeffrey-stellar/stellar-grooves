package com.stellarideas.grooves.service.storage;

import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LocalFileSourceTest {

    private final LocalFileSource source = new LocalFileSource();

    @TempDir
    Path musicDir;

    private User userWithMusicDir(String dir) {
        User u = new User();
        u.setId("u1");
        u.setUsername("tester");
        u.setMusicDirectory(dir);
        return u;
    }

    private MusicFile fileAt(String path) {
        return MusicFile.builder().id("f1").fileName("song.mp3").filePath(path).build();
    }

    @Test
    void resolvesOkForFileUnderMusicDirectory() throws IOException {
        Path audio = musicDir.resolve("song.mp3");
        Files.write(audio, new byte[]{1, 2, 3, 4});

        StreamResolution r = source.resolveStream(fileAt(audio.toString()), userWithMusicDir(musicDir.toString()));

        assertEquals(StreamResolution.Status.OK, r.status());
        assertNotNull(r.localPath());
        // resolved to the real (symlink-resolved) path
        assertEquals(audio.toRealPath(), r.localPath());
    }

    @Test
    void notFoundWhenFileMissingOnDisk() throws IOException {
        Path missing = musicDir.resolve("gone.mp3");

        StreamResolution r = source.resolveStream(fileAt(missing.toString()), userWithMusicDir(musicDir.toString()));

        assertEquals(StreamResolution.Status.NOT_FOUND, r.status());
    }

    @Test
    void forbiddenWhenUserHasNoMusicDirectory() throws IOException {
        Path audio = musicDir.resolve("song.mp3");
        Files.write(audio, new byte[]{1, 2, 3, 4});

        StreamResolution r = source.resolveStream(fileAt(audio.toString()), userWithMusicDir(""));

        assertEquals(StreamResolution.Status.FORBIDDEN, r.status());
    }

    @Test
    void forbiddenForPathTraversalOutsideMusicDirectory(@TempDir Path outside) throws IOException {
        Path audio = outside.resolve("hack.mp3");
        Files.write(audio, new byte[]{1, 2, 3, 4});

        StreamResolution r = source.resolveStream(fileAt(audio.toString()), userWithMusicDir(musicDir.toString()));

        assertEquals(StreamResolution.Status.FORBIDDEN, r.status());
    }
}
