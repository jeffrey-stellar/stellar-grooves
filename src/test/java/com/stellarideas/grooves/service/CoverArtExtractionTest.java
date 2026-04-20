package com.stellarideas.grooves.service;

import com.stellarideas.grooves.model.CoverArt;
import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.CoverArtRepository;
import com.stellarideas.grooves.repository.MusicFileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests cover art extraction edge cases: null tags, corrupt data,
 * oversized images, duplicate detection, and mime type fallback.
 */
class CoverArtExtractionTest {

    private MusicScannerService scannerService;
    private CoverArtRepository coverArtRepository;
    private MusicFileRepository repository;
    private User testUser;

    @TempDir
    Path tempDir;

    private static ScanPathValidator passThroughValidator() {
        ScanPathValidator v = mock(ScanPathValidator.class);
        try {
            when(v.validate(anyString())).thenAnswer(inv ->
                    java.nio.file.Paths.get((String) inv.getArgument(0)).normalize().toAbsolutePath());
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        return v;
    }

    @BeforeEach
    void setUp() {
        repository = mock(MusicFileRepository.class);
        MusicCatalogService catalogService = mock(MusicCatalogService.class);
        coverArtRepository = mock(CoverArtRepository.class);
        ScanProgressEmitter progressEmitter = mock(ScanProgressEmitter.class);
        scannerService = new MusicScannerService(catalogService, repository, coverArtRepository, progressEmitter, passThroughValidator(), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        ReflectionTestUtils.setField(scannerService, "maxDepth", 20);
        ReflectionTestUtils.setField(scannerService, "hardMaxDepth", 50);
        ReflectionTestUtils.setField(scannerService, "batchSize", 200);
        ReflectionTestUtils.setField(scannerService, "maxCoverArtBytes", 10485760);
        ReflectionTestUtils.setField(scannerService, "coverArtQuotaBytes", 524288000L);
        ReflectionTestUtils.setField(scannerService, "fileReaderThreads", 1);
        ReflectionTestUtils.setField(scannerService, "supportedExtensionsConfig", ".mp3,.m4a,.flac");
        scannerService.initExecutor();

        testUser = new User();
        testUser.setId("user1");
        testUser.setUsername("testuser");

        when(repository.findByUserId("user1")).thenReturn(List.of());
        when(catalogService.identifyGenres(any())).thenReturn(Set.of(Genre.OTHER));
    }

    @AfterEach
    void tearDown() {
        scannerService.destroy();
    }

    /**
     * Use reflection to call the private extractCoverArt method directly
     * for isolated unit testing of edge cases. Returns the byte size (0 = no art stored).
     */
    private long callExtractCoverArt(org.jaudiotagger.tag.Tag tag,
                                      String userId, String artist, String album) throws Exception {
        Method method = MusicScannerService.class.getDeclaredMethod(
                "extractCoverArt", org.jaudiotagger.tag.Tag.class, String.class, String.class, String.class);
        method.setAccessible(true);
        return (long) method.invoke(scannerService, tag, userId, artist, album);
    }

    @Test
    void extractCoverArtReturnsZeroForNullTag() throws Exception {
        long result = callExtractCoverArt(null, "user1", "Artist", "Album");
        assertEquals(0, result);
        verify(coverArtRepository, never()).save(any());
    }

    @Test
    void extractCoverArtReturnsZeroForTagWithNoArtwork() throws Exception {
        org.jaudiotagger.tag.Tag tag = mock(org.jaudiotagger.tag.Tag.class);
        when(tag.getFirstArtwork()).thenReturn(null);

        long result = callExtractCoverArt(tag, "user1", "Artist", "Album");
        assertEquals(0, result);
        verify(coverArtRepository, never()).save(any());
    }

    @Test
    void extractCoverArtReturnsZeroForEmptyBinaryData() throws Exception {
        org.jaudiotagger.tag.Tag tag = mock(org.jaudiotagger.tag.Tag.class);
        org.jaudiotagger.tag.images.Artwork artwork = mock(org.jaudiotagger.tag.images.Artwork.class);
        when(tag.getFirstArtwork()).thenReturn(artwork);
        when(artwork.getBinaryData()).thenReturn(new byte[0]);

        long result = callExtractCoverArt(tag, "user1", "Artist", "Album");
        assertEquals(0, result);
        verify(coverArtRepository, never()).save(any());
    }

    @Test
    void extractCoverArtReturnsZeroForNullBinaryData() throws Exception {
        org.jaudiotagger.tag.Tag tag = mock(org.jaudiotagger.tag.Tag.class);
        org.jaudiotagger.tag.images.Artwork artwork = mock(org.jaudiotagger.tag.images.Artwork.class);
        when(tag.getFirstArtwork()).thenReturn(artwork);
        when(artwork.getBinaryData()).thenReturn(null);

        long result = callExtractCoverArt(tag, "user1", "Artist", "Album");
        assertEquals(0, result);
        verify(coverArtRepository, never()).save(any());
    }

    @Test
    void extractCoverArtRejectsOversizedImage() throws Exception {
        org.jaudiotagger.tag.Tag tag = mock(org.jaudiotagger.tag.Tag.class);
        org.jaudiotagger.tag.images.Artwork artwork = mock(org.jaudiotagger.tag.images.Artwork.class);
        when(tag.getFirstArtwork()).thenReturn(artwork);

        // Create data larger than MAX_COVER_ART_BYTES (10 MB)
        byte[] oversized = new byte[11 * 1024 * 1024];
        when(artwork.getBinaryData()).thenReturn(oversized);

        long result = callExtractCoverArt(tag, "user1", "Artist", "Album");
        assertEquals(0, result, "Oversized cover art should be rejected");
        verify(coverArtRepository, never()).save(any());
    }

    @Test
    void extractCoverArtSkipsWhenAlbumArtAlreadyExists() throws Exception {
        org.jaudiotagger.tag.Tag tag = mock(org.jaudiotagger.tag.Tag.class);
        org.jaudiotagger.tag.images.Artwork artwork = mock(org.jaudiotagger.tag.images.Artwork.class);
        when(tag.getFirstArtwork()).thenReturn(artwork);
        when(artwork.getBinaryData()).thenReturn(new byte[]{1, 2, 3});

        // Simulate existing cover art in the repository
        when(coverArtRepository.findByUserIdAndArtistAndAlbum("user1", "Artist", "Album"))
                .thenReturn(Optional.of(new CoverArt()));

        long result = callExtractCoverArt(tag, "user1", "Artist", "Album");
        assertEquals(3, result, "Should return data size when art already exists");
        verify(coverArtRepository, never()).save(any());
    }

    @Test
    void extractCoverArtSavesValidArtwork() throws Exception {
        org.jaudiotagger.tag.Tag tag = mock(org.jaudiotagger.tag.Tag.class);
        org.jaudiotagger.tag.images.Artwork artwork = mock(org.jaudiotagger.tag.images.Artwork.class);
        when(tag.getFirstArtwork()).thenReturn(artwork);
        byte[] imageData = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}; // JPEG magic bytes
        when(artwork.getBinaryData()).thenReturn(imageData);
        when(artwork.getMimeType()).thenReturn("image/jpeg");
        when(coverArtRepository.findByUserIdAndArtistAndAlbum("user1", "Artist", "Album"))
                .thenReturn(Optional.empty());

        long result = callExtractCoverArt(tag, "user1", "Artist", "Album");
        assertEquals(3, result);
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
    void extractCoverArtDefaultsMimeTypeToJpeg() throws Exception {
        org.jaudiotagger.tag.Tag tag = mock(org.jaudiotagger.tag.Tag.class);
        org.jaudiotagger.tag.images.Artwork artwork = mock(org.jaudiotagger.tag.images.Artwork.class);
        when(tag.getFirstArtwork()).thenReturn(artwork);
        when(artwork.getBinaryData()).thenReturn(new byte[]{1, 2, 3});
        when(artwork.getMimeType()).thenReturn(null); // no mime type
        when(coverArtRepository.findByUserIdAndArtistAndAlbum("user1", "Artist", "Album"))
                .thenReturn(Optional.empty());

        long result = callExtractCoverArt(tag, "user1", "Artist", "Album");
        assertEquals(3, result);
        verify(coverArtRepository).save(argThat(art ->
                "image/jpeg".equals(((CoverArt) art).getMimeType())));
    }

    @Test
    void extractCoverArtHandlesRepositorySaveException() throws Exception {
        org.jaudiotagger.tag.Tag tag = mock(org.jaudiotagger.tag.Tag.class);
        org.jaudiotagger.tag.images.Artwork artwork = mock(org.jaudiotagger.tag.images.Artwork.class);
        when(tag.getFirstArtwork()).thenReturn(artwork);
        when(artwork.getBinaryData()).thenReturn(new byte[]{1, 2, 3});
        when(coverArtRepository.findByUserIdAndArtistAndAlbum("user1", "Artist", "Album"))
                .thenReturn(Optional.empty());
        when(coverArtRepository.save(any())).thenThrow(new RuntimeException("DB write failed"));

        long result = callExtractCoverArt(tag, "user1", "Artist", "Album");
        assertEquals(0, result, "Should return 0 when save throws");
    }
}
