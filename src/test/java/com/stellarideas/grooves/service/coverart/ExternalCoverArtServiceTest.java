package com.stellarideas.grooves.service.coverart;

import com.stellarideas.grooves.model.CoverArtMiss;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.repository.CoverArtMissRepository;
import com.stellarideas.grooves.repository.MusicFileRepository;
import com.stellarideas.grooves.service.scan.CoverArtHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExternalCoverArtServiceTest {

    private AlbumArtProvider mb;
    private AlbumArtProvider itunes;
    private MusicFileRepository musicRepo;
    private CoverArtMissRepository missRepo;
    private CoverArtHandler handler;
    private ExternalCoverArtService service;

    private static final byte[] IMG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2};

    @BeforeEach
    void setUp() {
        mb = mock(AlbumArtProvider.class);
        itunes = mock(AlbumArtProvider.class);
        when(mb.name()).thenReturn("musicbrainz");
        when(itunes.name()).thenReturn("itunes");
        musicRepo = mock(MusicFileRepository.class);
        missRepo = mock(CoverArtMissRepository.class);
        handler = mock(CoverArtHandler.class);
        service = new ExternalCoverArtService(List.of(mb, itunes), musicRepo, missRepo, handler);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "providerOrder", "musicbrainz,itunes");
        ReflectionTestUtils.setField(service, "rateLimitMs", 0L);
        ReflectionTestUtils.setField(service, "maxAlbumsPerRun", 200);
        ReflectionTestUtils.setField(service, "retryAfterDays", 30);
        when(missRepo.findByUserIdAndArtistAndAlbum(any(), any(), any())).thenReturn(Optional.empty());
    }

    private MusicFile file(String artist, String album, boolean hasArt) {
        return MusicFile.builder().userId("u").artist(artist).album(album).hasCoverArt(hasArt).build();
    }

    @Test
    void fetchesStoresAndFlagsMissingAlbum() {
        when(musicRepo.findByUserIdAndDeletedFalse("u")).thenReturn(List.of(
                file("Metallica", "Master of Puppets", false),
                file("Metallica", "Master of Puppets", false),
                file("Dio", "Holy Diver", true)));   // already has art -> ignored
        when(musicRepo.findByUserIdAndArtistAndAlbumAndDeletedFalse("u", "Metallica", "Master of Puppets"))
                .thenReturn(List.of(file("Metallica", "Master of Puppets", false)));
        when(mb.fetch("Metallica", "Master of Puppets")).thenReturn(Optional.of(new FetchedArt(IMG, "image/jpeg")));

        ExternalCoverArtService.Status s = service.fetchMissing("u");

        assertEquals(1, s.fetched);
        assertEquals(1, s.processed);
        verify(handler).storeFetchedCover("u", "Metallica", "Master of Puppets", IMG, "image/jpeg", "musicbrainz");
        verify(musicRepo).saveAll(anyList());
        verify(missRepo).deleteByUserIdAndArtistAndAlbum("u", "Metallica", "Master of Puppets");
        verify(itunes, never()).fetch(any(), any());   // first provider hit, no fallback
        verifyNoMoreInteractions(handler);
    }

    @Test
    void fallsBackToSecondProvider() {
        when(musicRepo.findByUserIdAndDeletedFalse("u")).thenReturn(List.of(file("A", "B", false)));
        when(musicRepo.findByUserIdAndArtistAndAlbumAndDeletedFalse("u", "A", "B")).thenReturn(List.of(file("A", "B", false)));
        when(mb.fetch("A", "B")).thenReturn(Optional.empty());
        when(itunes.fetch("A", "B")).thenReturn(Optional.of(new FetchedArt(IMG, "image/png")));

        ExternalCoverArtService.Status s = service.fetchMissing("u");

        assertEquals(1, s.fetched);
        verify(handler).storeFetchedCover("u", "A", "B", IMG, "image/png", "itunes");
    }

    @Test
    void recordsMissWhenNoProviderFindsArt() {
        when(musicRepo.findByUserIdAndDeletedFalse("u")).thenReturn(List.of(file("A", "B", false)));
        when(mb.fetch("A", "B")).thenReturn(Optional.empty());
        when(itunes.fetch("A", "B")).thenReturn(Optional.empty());

        ExternalCoverArtService.Status s = service.fetchMissing("u");

        assertEquals(0, s.fetched);
        verify(missRepo).save(argThat(m -> "A".equals(((CoverArtMiss) m).getArtist())
                && ((CoverArtMiss) m).getAttempts() == 1
                && ((CoverArtMiss) m).getLastAttemptAt() != null));
        verify(handler, never()).storeFetchedCover(any(), any(), any(), any(), any(), any());
    }

    @Test
    void skipsAlbumWithRecentMiss() {
        when(musicRepo.findByUserIdAndDeletedFalse("u")).thenReturn(List.of(file("A", "B", false)));
        CoverArtMiss recent = new CoverArtMiss();
        recent.setLastAttemptAt(Instant.now());
        when(missRepo.findByUserIdAndArtistAndAlbum("u", "A", "B")).thenReturn(Optional.of(recent));

        ExternalCoverArtService.Status s = service.fetchMissing("u");

        assertEquals(1, s.skipped);
        assertEquals(0, s.processed);
        verify(mb, never()).fetch(any(), any());
        verify(itunes, never()).fetch(any(), any());
    }

    @Test
    void orderedProvidersFollowsConfig() {
        ReflectionTestUtils.setField(service, "providerOrder", "itunes,musicbrainz");
        List<AlbumArtProvider> ordered = service.orderedProviders();
        assertEquals(List.of("itunes", "musicbrainz"), ordered.stream().map(AlbumArtProvider::name).toList());
    }

    @Test
    void unknownProviderNamesIgnored() {
        ReflectionTestUtils.setField(service, "providerOrder", "itunes,bogus");
        List<AlbumArtProvider> ordered = service.orderedProviders();
        assertEquals(List.of("itunes"), ordered.stream().map(AlbumArtProvider::name).toList());
    }
}
