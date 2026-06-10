package com.stellarideas.grooves.service;

import com.stellarideas.grooves.model.CoverArt;
import com.stellarideas.grooves.repository.CoverArtRepository;
import com.stellarideas.grooves.service.scan.CoverArtHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Tests {@link CoverArtHandler#storeManualCover}: validation, upsert/replace, and
 * quota accounting (which must subtract the replaced art's size).
 */
class ManualCoverArtTest {

    private CoverArtRepository repo;
    private CoverArtHandler handler;

    @BeforeEach
    void setUp() {
        repo = mock(CoverArtRepository.class);
        handler = ScannerTestFactory.newCoverArtHandler(repo, 10_485_760, 524_288_000L);
        when(repo.findByUserIdAndArtistAndAlbum(any(), any(), any())).thenReturn(Optional.empty());
        when(repo.getTotalCoverArtSizeByUserId("user1")).thenReturn(0L);
    }

    @Test
    void storesNewManualCover() {
        handler.storeManualCover("user1", "Artist", "Album", new byte[]{1, 2, 3}, "image/png");
        verify(repo).save(argThat(o -> {
            CoverArt ca = (CoverArt) o;
            return "manual".equals(ca.getSource())
                    && "image/png".equals(ca.getMimeType())
                    && ca.getData().length == 3;
        }));
    }

    @Test
    void replacesExistingAlbumArtInPlace() {
        CoverArt existing = new CoverArt();
        existing.setId("art1");
        existing.setUserId("user1");
        existing.setArtist("Artist");
        existing.setAlbum("Album");
        existing.setData(new byte[]{0, 0});
        existing.setSource("folder");
        when(repo.findByUserIdAndArtistAndAlbum("user1", "Artist", "Album")).thenReturn(Optional.of(existing));

        handler.storeManualCover("user1", "Artist", "Album", new byte[]{5, 5, 5, 5}, "image/jpeg");

        // Same document reused (id preserved), data + source replaced.
        verify(repo).save(argThat(o -> {
            CoverArt ca = (CoverArt) o;
            return "art1".equals(ca.getId())
                    && "manual".equals(ca.getSource())
                    && ca.getData().length == 4;
        }));
    }

    @Test
    void rejectsEmptyImage() {
        assertThrows(IllegalArgumentException.class,
                () -> handler.storeManualCover("user1", "Artist", "Album", new byte[0], "image/png"));
        verify(repo, never()).save(any());
    }

    @Test
    void rejectsBlankArtistOrAlbum() {
        assertThrows(IllegalArgumentException.class,
                () -> handler.storeManualCover("user1", "", "Album", new byte[]{1}, "image/png"));
        assertThrows(IllegalArgumentException.class,
                () -> handler.storeManualCover("user1", "Artist", " ", new byte[]{1}, "image/png"));
        verify(repo, never()).save(any());
    }

    @Test
    void rejectsImageOverPerImageCap() {
        CoverArtHandler smallCap = ScannerTestFactory.newCoverArtHandler(repo, 10, 524_288_000L);
        when(repo.getTotalCoverArtSizeByUserId("user1")).thenReturn(0L);
        assertThrows(IllegalArgumentException.class,
                () -> smallCap.storeManualCover("user1", "Artist", "Album", new byte[20], "image/png"));
        verify(repo, never()).save(any());
    }

    @Test
    void quotaCheckSubtractsReplacedArtSize() {
        // Per-user cap 100; current usage already 100, but 40 of it is this album's old art.
        CoverArtHandler capped = ScannerTestFactory.newCoverArtHandler(repo, 10_485_760, 100L);
        CoverArt existing = new CoverArt();
        existing.setData(new byte[40]);
        when(repo.findByUserIdAndArtistAndAlbum("user1", "Artist", "Album")).thenReturn(Optional.of(existing));
        when(repo.getTotalCoverArtSizeByUserId("user1")).thenReturn(100L);

        // Replacing 40 bytes with 30 → 100 - 40 + 30 = 90 ≤ 100: allowed.
        assertDoesNotThrow(() ->
                capped.storeManualCover("user1", "Artist", "Album", new byte[30], "image/png"));
        verify(repo).save(any());
    }

    @Test
    void quotaExceededWhenReplacementGrowsPastCap() {
        CoverArtHandler capped = ScannerTestFactory.newCoverArtHandler(repo, 10_485_760, 100L);
        CoverArt existing = new CoverArt();
        existing.setData(new byte[40]);
        when(repo.findByUserIdAndArtistAndAlbum("user1", "Artist", "Album")).thenReturn(Optional.of(existing));
        when(repo.getTotalCoverArtSizeByUserId("user1")).thenReturn(100L);

        // 100 - 40 + 80 = 140 > 100: rejected.
        assertThrows(IllegalArgumentException.class, () ->
                capped.storeManualCover("user1", "Artist", "Album", new byte[80], "image/png"));
        verify(repo, never()).save(any());
    }
}
