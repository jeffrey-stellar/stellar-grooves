package com.stellarideas.grooves.service;

import com.stellarideas.grooves.model.CoverArt;
import com.stellarideas.grooves.repository.CoverArtRepository;
import com.stellarideas.grooves.service.scan.CoverArtHandler;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Tests the sidecar ("folder image") cover-art fallback in {@link CoverArtHandler}:
 * picking the right file, name priority, ignoring arbitrary images, the size cap,
 * embedded-art precedence, and the disable flag. Uses real files in a temp dir.
 */
class FolderCoverArtTest {

    private CoverArtRepository repo;
    private CoverArtHandler handler;
    private CoverArtHandler.Budget budget;

    @TempDir
    Path albumDir;

    @BeforeEach
    void setUp() {
        repo = mock(CoverArtRepository.class);
        handler = ScannerTestFactory.newCoverArtHandler(repo, 10_485_760, 524_288_000L);
        when(repo.getTotalCoverArtSizeByUserId("user1")).thenReturn(0L);
        when(repo.findByUserIdAndArtistAndAlbum(any(), any(), any())).thenReturn(Optional.empty());
        budget = handler.newBudget("user1");
    }

    private Path track() {
        return albumDir.resolve("01 - track.flac"); // need not exist; only its parent is used
    }

    @Test
    void storesSidecarWhenNoEmbeddedArt() throws IOException {
        byte[] img = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x10, 0x20};
        Files.write(albumDir.resolve("cover.jpg"), img);

        assertTrue(handler.process(null, track(), "user1", "Artist", "Album", budget));
        verify(repo).save(argThat(o -> {
            CoverArt ca = (CoverArt) o;
            return "folder".equals(ca.getSource())
                    && "image/jpeg".equals(ca.getMimeType())
                    && ca.getData().length == img.length;
        }));
    }

    @Test
    void prefersHigherPriorityName() throws IOException {
        Files.write(albumDir.resolve("front.jpg"), new byte[]{1});
        Files.write(albumDir.resolve("folder.png"), new byte[]{1, 2});
        Files.write(albumDir.resolve("cover.jpg"), new byte[]{1, 2, 3}); // rank 0 → wins

        assertTrue(handler.process(null, track(), "user1", "Artist", "Album", budget));
        verify(repo).save(argThat(o -> {
            CoverArt ca = (CoverArt) o;
            return "image/jpeg".equals(ca.getMimeType()) && ca.getData().length == 3;
        }));
    }

    @Test
    void ignoresArbitraryImages() throws IOException {
        Files.write(albumDir.resolve("booklet-01.jpg"), new byte[]{1, 2, 3});
        Files.write(albumDir.resolve("scan.png"), new byte[]{4, 5});

        assertFalse(handler.process(null, track(), "user1", "Artist", "Album", budget));
        verify(repo, never()).save(any());
    }

    @Test
    void embeddedArtTakesPrecedenceOverSidecar() throws IOException {
        Files.write(albumDir.resolve("cover.jpg"), new byte[]{9, 9, 9, 9});

        Tag tag = mock(Tag.class);
        Artwork artwork = mock(Artwork.class);
        when(tag.getFirstArtwork()).thenReturn(artwork);
        when(artwork.getBinaryData()).thenReturn(new byte[]{1, 2});
        when(artwork.getMimeType()).thenReturn("image/png");

        assertTrue(handler.process(tag, track(), "user1", "Artist", "Album", budget));
        verify(repo, times(1)).save(argThat(o -> {
            CoverArt ca = (CoverArt) o;
            return "embedded".equals(ca.getSource()) && ca.getData().length == 2;
        }));
    }

    @Test
    void oversizedSidecarRejected() throws IOException {
        CoverArtHandler smallCap = ScannerTestFactory.newCoverArtHandler(repo, 10, 524_288_000L);
        when(repo.getTotalCoverArtSizeByUserId("user1")).thenReturn(0L);
        CoverArtHandler.Budget b = smallCap.newBudget("user1");
        Files.write(albumDir.resolve("cover.jpg"), new byte[20]); // exceeds 10-byte cap

        assertFalse(smallCap.process(null, track(), "user1", "Artist", "Album", b));
        verify(repo, never()).save(any());
    }

    @Test
    void disableFlagSkipsSidecar() throws IOException {
        ReflectionTestUtils.setField(handler, "folderImageEnabled", false);
        Files.write(albumDir.resolve("cover.jpg"), new byte[]{1, 2, 3});

        assertFalse(handler.process(null, track(), "user1", "Artist", "Album", budget));
        verify(repo, never()).save(any());
    }

    @Test
    void detectsPngMimeType() throws IOException {
        Files.write(albumDir.resolve("folder.png"), new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});

        assertTrue(handler.process(null, track(), "user1", "Artist", "Album", budget));
        verify(repo).save(argThat(o -> "image/png".equals(((CoverArt) o).getMimeType())));
    }
}
