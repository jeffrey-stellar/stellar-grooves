package com.stellarideas.grooves.service;

import com.mongodb.client.result.UpdateResult;
import com.stellarideas.grooves.model.CoverArt;
import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.Playlist;
import com.stellarideas.grooves.repository.CoverArtRepository;
import com.stellarideas.grooves.repository.MusicFileRepository;
import com.stellarideas.grooves.repository.PlaylistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LibraryServiceTest {

    private LibraryService service;
    private MusicFileRepository musicFileRepository;
    private PlaylistRepository playlistRepository;
    private CoverArtRepository coverArtRepository;
    private MusicCatalogService catalogService;
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        musicFileRepository = mock(MusicFileRepository.class);
        playlistRepository = mock(PlaylistRepository.class);
        coverArtRepository = mock(CoverArtRepository.class);
        catalogService = mock(MusicCatalogService.class);
        mongoTemplate = mock(MongoTemplate.class);
        com.stellarideas.grooves.repository.PlaybackQueueRepository playbackQueueRepository = mock(com.stellarideas.grooves.repository.PlaybackQueueRepository.class);
        com.stellarideas.grooves.repository.PlayEventRepository playEventRepository = mock(com.stellarideas.grooves.repository.PlayEventRepository.class);
        service = new LibraryService(musicFileRepository, playlistRepository, coverArtRepository, playbackQueueRepository, playEventRepository, catalogService, mongoTemplate);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "maxSearchQueryLength", 200);
    }

    @Test
    void getFilesWithoutGenre() {
        MusicFile file = MusicFile.builder().id("f1").title("Song").artist("Artist").genre(Genre.CLASSIC_ROCK).build();
        Page<MusicFile> page = new PageImpl<>(List.of(file));
        when(musicFileRepository.findByUserIdAndDeletedFalse(eq("user1"), any(PageRequest.class))).thenReturn(page);

        Page<MusicFile> result = service.getFiles("user1", null, 0, 50);

        assertEquals(1, result.getTotalElements());
        verify(musicFileRepository).findByUserIdAndDeletedFalse(eq("user1"), any(PageRequest.class));
    }

    @Test
    void getFilesWithGenre() {
        Page<MusicFile> page = new PageImpl<>(List.of());
        when(musicFileRepository.findByUserIdAndGenreAndDeletedFalse(eq("user1"), eq(Genre.HARD_ROCK), any(PageRequest.class))).thenReturn(page);

        Page<MusicFile> result = service.getFiles("user1", Genre.HARD_ROCK, 0, 50);

        assertEquals(0, result.getTotalElements());
        verify(musicFileRepository).findByUserIdAndGenreAndDeletedFalse(eq("user1"), eq(Genre.HARD_ROCK), any(PageRequest.class));
    }

    @Test
    void getFilesClampsSizeToMax() {
        Page<MusicFile> page = new PageImpl<>(List.of());
        when(musicFileRepository.findByUserIdAndDeletedFalse(eq("user1"), any(PageRequest.class))).thenReturn(page);

        service.getFiles("user1", null, 0, 1000);

        verify(musicFileRepository).findByUserIdAndDeletedFalse(eq("user1"), eq(PageRequest.of(0, 200)));
    }

    @Test
    void getFilesClampsSizeToMin() {
        Page<MusicFile> page = new PageImpl<>(List.of());
        when(musicFileRepository.findByUserIdAndDeletedFalse(eq("user1"), any(PageRequest.class))).thenReturn(page);

        service.getFiles("user1", null, 0, -5);

        verify(musicFileRepository).findByUserIdAndDeletedFalse(eq("user1"), eq(PageRequest.of(0, 1)));
    }

    @Test
    void searchFilesEscapesRegex() {
        Page<MusicFile> page = new PageImpl<>(List.of());
        when(musicFileRepository.searchByUserIdAndQuery(eq("user1"), anyString(), any(PageRequest.class))).thenReturn(page);

        service.searchFiles("user1", "AC/DC (Live)", 0, 50);

        // Verify regex special chars are escaped using Pattern.quote
        verify(musicFileRepository).searchByUserIdAndQuery(
                eq("user1"), eq(java.util.regex.Pattern.quote("AC/DC (Live)")), any(PageRequest.class));
    }

    @Test
    void updateGenreRecordsCorrection() {
        MusicFile file = MusicFile.builder().id("f1").artist("Metallica").genre(Genre.OTHER).build();
        when(musicFileRepository.save(any(MusicFile.class))).thenReturn(file);

        service.updateGenre(file, Genre.THRASH_METAL, "user1");

        assertEquals(Genre.THRASH_METAL, file.getGenre());
        verify(catalogService).recordCorrection("Metallica", Genre.THRASH_METAL, "user1");
    }

    @Test
    void updateGenreSkipsCorrectionForBlankArtist() {
        MusicFile file = MusicFile.builder().id("f1").artist("").genre(Genre.OTHER).build();
        when(musicFileRepository.save(any(MusicFile.class))).thenReturn(file);

        service.updateGenre(file, Genre.CLASSIC_ROCK, "user1");

        verify(catalogService, never()).recordCorrection(anyString(), any(), anyString());
    }

    @Test
    void updateRatingClampsValue() {
        MusicFile file = MusicFile.builder().id("f1").build();
        when(musicFileRepository.save(any(MusicFile.class))).thenReturn(file);

        service.updateRating(file, 3);

        assertEquals(3, file.getRating());
        verify(musicFileRepository).save(file);
    }

    @Test
    void bulkDeleteUsesUpdateMulti() {
        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getModifiedCount()).thenReturn(2L);
        when(mongoTemplate.updateMulti(any(Query.class), any(Update.class), eq(MusicFile.class)))
                .thenReturn(updateResult);

        long deleted = service.bulkDelete(List.of("f1", "f2"), "user1");

        assertEquals(2, deleted);
        verify(mongoTemplate).updateMulti(any(Query.class), any(Update.class), eq(MusicFile.class));
    }

    @Test
    void bulkDeleteReturnsZeroForNoMatches() {
        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getModifiedCount()).thenReturn(0L);
        when(mongoTemplate.updateMulti(any(Query.class), any(Update.class), eq(MusicFile.class)))
                .thenReturn(updateResult);

        long deleted = service.bulkDelete(List.of("none"), "user1");

        assertEquals(0, deleted);
    }

    @Test
    void deleteFileSetsDeletedFlag() {
        MusicFile file = MusicFile.builder().id("f1").build();

        service.deleteFile(file, "user1");

        assertTrue(file.isDeleted());
        assertNotNull(file.getDeletedAt());
        verify(musicFileRepository).save(file);
    }

    @Test
    void restoreFileUnsetsDeletedFlag() {
        MusicFile file = MusicFile.builder().id("f1").build();
        file.setDeleted(true);
        when(musicFileRepository.findByIdAndUserId("f1", "user1")).thenReturn(Optional.of(file));

        service.restoreFile("f1", "user1");

        assertFalse(file.isDeleted());
        assertNull(file.getDeletedAt());
    }

    @Test
    void restoreFileThrowsForNonDeletedFile() {
        MusicFile file = MusicFile.builder().id("f1").build();
        file.setDeleted(false);
        when(musicFileRepository.findByIdAndUserId("f1", "user1")).thenReturn(Optional.of(file));

        assertThrows(IllegalArgumentException.class, () -> service.restoreFile("f1", "user1"));
    }

    @Test
    void restoreFileThrowsForMissing() {
        when(musicFileRepository.findByIdAndUserId("missing", "user1")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.restoreFile("missing", "user1"));
    }

    @Test
    void clearLibraryCascades() {
        when(musicFileRepository.deleteByUserId("user1")).thenReturn(10L);

        long count = service.clearLibrary("user1");

        assertEquals(10, count);
        verify(playlistRepository).deleteByUserId("user1");
        verify(coverArtRepository).deleteByUserId("user1");
    }

    @Test
    void getCoverArtDelegatesToRepository() {
        CoverArt art = new CoverArt();
        when(coverArtRepository.findByUserIdAndArtistAndAlbum("user1", "Artist", "Album"))
                .thenReturn(Optional.of(art));

        Optional<CoverArt> result = service.getCoverArt("user1", "Artist", "Album");

        assertTrue(result.isPresent());
    }

    @Test
    void emptyTrashRemovesFromPlaylists() {
        MusicFile trashed = MusicFile.builder().id("f1").build();
        trashed.setDeleted(true);
        when(musicFileRepository.findByUserIdAndDeletedTrue("user1")).thenReturn(List.of(trashed));

        Playlist playlist = new Playlist();
        playlist.setTrackIds(new ArrayList<>(List.of("f1", "f2")));
        when(playlistRepository.findByUserId("user1")).thenReturn(List.of(playlist));

        service.emptyTrash("user1");

        verify(musicFileRepository).deleteAll(List.of(trashed));
        assertEquals(List.of("f2"), playlist.getTrackIds());
        verify(playlistRepository).saveAll(List.of(playlist));
    }

    @Test
    void emptyTrashNoOpWhenEmpty() {
        when(musicFileRepository.findByUserIdAndDeletedTrue("user1")).thenReturn(List.of());

        service.emptyTrash("user1");

        verify(musicFileRepository, never()).deleteAll(anyList());
    }
}
