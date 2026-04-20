package com.stellarideas.grooves.service;

import com.stellarideas.grooves.model.CoverArt;
import com.stellarideas.grooves.repository.CoverArtRepository;
import com.stellarideas.grooves.service.scan.CoverArtHandler;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Tests cover-art edge cases directly against {@link CoverArtHandler}: null tags,
 * corrupt data, oversized images, duplicate detection, and mime-type fallback.
 */
class CoverArtExtractionTest {

    private CoverArtHandler handler;
    private CoverArtRepository coverArtRepository;
    private CoverArtHandler.Budget budget;

    @BeforeEach
    void setUp() {
        coverArtRepository = mock(CoverArtRepository.class);
        handler = ScannerTestFactory.newCoverArtHandler(coverArtRepository, 10_485_760, 524_288_000L);
        when(coverArtRepository.getTotalCoverArtSizeByUserId("user1")).thenReturn(0L);
        budget = handler.newBudget("user1");
    }

    @Test
    void nullTagYieldsNoCover() {
        assertFalse(handler.process(null, "user1", "Artist", "Album", budget));
        verify(coverArtRepository, never()).save(any());
    }

    @Test
    void tagWithNoArtworkYieldsNoCover() {
        Tag tag = mock(Tag.class);
        when(tag.getFirstArtwork()).thenReturn(null);

        assertFalse(handler.process(tag, "user1", "Artist", "Album", budget));
        verify(coverArtRepository, never()).save(any());
    }

    @Test
    void emptyBinaryDataYieldsNoCover() {
        Tag tag = mock(Tag.class);
        Artwork artwork = mock(Artwork.class);
        when(tag.getFirstArtwork()).thenReturn(artwork);
        when(artwork.getBinaryData()).thenReturn(new byte[0]);

        assertFalse(handler.process(tag, "user1", "Artist", "Album", budget));
        verify(coverArtRepository, never()).save(any());
    }

    @Test
    void nullBinaryDataYieldsNoCover() {
        Tag tag = mock(Tag.class);
        Artwork artwork = mock(Artwork.class);
        when(tag.getFirstArtwork()).thenReturn(artwork);
        when(artwork.getBinaryData()).thenReturn(null);

        assertFalse(handler.process(tag, "user1", "Artist", "Album", budget));
        verify(coverArtRepository, never()).save(any());
    }

    @Test
    void oversizedImageRejected() {
        Tag tag = mock(Tag.class);
        Artwork artwork = mock(Artwork.class);
        when(tag.getFirstArtwork()).thenReturn(artwork);
        byte[] oversized = new byte[11 * 1024 * 1024];
        when(artwork.getBinaryData()).thenReturn(oversized);

        assertFalse(handler.process(tag, "user1", "Artist", "Album", budget));
        verify(coverArtRepository, never()).save(any());
    }

    @Test
    void existingAlbumArtSkipsWrite() {
        Tag tag = mock(Tag.class);
        Artwork artwork = mock(Artwork.class);
        when(tag.getFirstArtwork()).thenReturn(artwork);
        when(artwork.getBinaryData()).thenReturn(new byte[]{1, 2, 3});
        when(coverArtRepository.findByUserIdAndArtistAndAlbum("user1", "Artist", "Album"))
                .thenReturn(Optional.of(new CoverArt()));

        assertTrue(handler.process(tag, "user1", "Artist", "Album", budget),
                "Should return true when album art already exists");
        verify(coverArtRepository, never()).save(any());
    }

    @Test
    void validArtworkIsSaved() {
        Tag tag = mock(Tag.class);
        Artwork artwork = mock(Artwork.class);
        when(tag.getFirstArtwork()).thenReturn(artwork);
        when(artwork.getBinaryData()).thenReturn(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        when(artwork.getMimeType()).thenReturn("image/jpeg");
        when(coverArtRepository.findByUserIdAndArtistAndAlbum("user1", "Artist", "Album"))
                .thenReturn(Optional.empty());
        when(coverArtRepository.getTotalCoverArtSizeByUserId("user1")).thenReturn(0L);

        assertTrue(handler.process(tag, "user1", "Artist", "Album", budget));
        verify(coverArtRepository).save(argThat(art -> {
            CoverArt ca = (CoverArt) art;
            return "user1".equals(ca.getUserId())
                    && "Artist".equals(ca.getArtist())
                    && "Album".equals(ca.getAlbum())
                    && "image/jpeg".equals(ca.getMimeType())
                    && ca.getData().length == 3;
        }));
    }

    @Test
    void missingMimeTypeDefaultsToJpeg() {
        Tag tag = mock(Tag.class);
        Artwork artwork = mock(Artwork.class);
        when(tag.getFirstArtwork()).thenReturn(artwork);
        when(artwork.getBinaryData()).thenReturn(new byte[]{1, 2, 3});
        when(artwork.getMimeType()).thenReturn(null);
        when(coverArtRepository.findByUserIdAndArtistAndAlbum("user1", "Artist", "Album"))
                .thenReturn(Optional.empty());

        assertTrue(handler.process(tag, "user1", "Artist", "Album", budget));
        verify(coverArtRepository).save(argThat(art ->
                "image/jpeg".equals(((CoverArt) art).getMimeType())));
    }

    @Test
    void repositoryWriteExceptionIsSwallowed() {
        Tag tag = mock(Tag.class);
        Artwork artwork = mock(Artwork.class);
        when(tag.getFirstArtwork()).thenReturn(artwork);
        when(artwork.getBinaryData()).thenReturn(new byte[]{1, 2, 3});
        when(coverArtRepository.findByUserIdAndArtistAndAlbum("user1", "Artist", "Album"))
                .thenReturn(Optional.empty());
        when(coverArtRepository.save(any())).thenThrow(new RuntimeException("DB write failed"));

        assertFalse(handler.process(tag, "user1", "Artist", "Album", budget),
                "Should return false when save throws");
    }

    @Test
    void deduplicatesWithinSingleScan() {
        Tag tag = mock(Tag.class);
        Artwork artwork = mock(Artwork.class);
        when(tag.getFirstArtwork()).thenReturn(artwork);
        when(artwork.getBinaryData()).thenReturn(new byte[]{1, 2, 3});
        when(coverArtRepository.findByUserIdAndArtistAndAlbum(any(), any(), any()))
                .thenReturn(Optional.empty());

        assertTrue(handler.process(tag, "user1", "Artist", "Album", budget));
        assertTrue(handler.process(tag, "user1", "Artist", "Album", budget),
                "Second call for same album should return true without re-saving");
        verify(coverArtRepository, times(1)).save(any());
    }
}
