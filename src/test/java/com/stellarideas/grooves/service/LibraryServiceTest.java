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
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
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
        com.stellarideas.grooves.service.scan.CoverArtHandler coverArtHandler = mock(com.stellarideas.grooves.service.scan.CoverArtHandler.class);
        com.stellarideas.grooves.repository.CoverArtMissRepository coverArtMissRepository = mock(com.stellarideas.grooves.repository.CoverArtMissRepository.class);
        service = new LibraryService(musicFileRepository, playlistRepository, coverArtRepository, playbackQueueRepository, playEventRepository, catalogService, mongoTemplate, new LibraryStatsCache(), coverArtHandler, coverArtMissRepository);
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
    void normalizeTagsTrimsLowercasesAndDedupes() {
        List<String> normalized = LibraryService.normalizeTags(
                List.of("  Acoustic  ", "acoustic", "Live", "LIVE", "Demo"));

        assertEquals(List.of("acoustic", "live", "demo"), normalized);
    }

    @Test
    void normalizeTagsCollapsesInternalWhitespace() {
        List<String> normalized = LibraryService.normalizeTags(List.of("early\tdemo   take"));

        assertEquals(List.of("early demo take"), normalized);
    }

    @Test
    void normalizeTagsHandlesNullAndBlanks() {
        List<String> normalized = LibraryService.normalizeTags(
                java.util.Arrays.asList(null, "", "   ", "keeper"));

        assertEquals(List.of("keeper"), normalized);
    }

    @Test
    void normalizeTagsReturnsEmptyForNullInput() {
        assertEquals(List.of(), LibraryService.normalizeTags(null));
    }

    @Test
    void normalizeTagsRejectsOverLongTag() {
        String tooLong = "x".repeat(LibraryService.MAX_TAG_LENGTH + 1);

        assertThrows(IllegalArgumentException.class,
                () -> LibraryService.normalizeTags(List.of(tooLong)));
    }

    @Test
    void normalizeTagsRejectsTooManyTags() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i <= LibraryService.MAX_TAGS_PER_TRACK; i++) many.add("tag-" + i);

        assertThrows(IllegalArgumentException.class, () -> LibraryService.normalizeTags(many));
    }

    @Test
    void updateTagsPersistsNormalized() {
        MusicFile file = MusicFile.builder().id("f1").build();
        when(musicFileRepository.save(any(MusicFile.class))).thenAnswer(inv -> inv.getArgument(0));

        MusicFile saved = service.updateTags(file, List.of("Acoustic", "acoustic", "Live"));

        assertEquals(List.of("acoustic", "live"), saved.getCustomTags());
        verify(musicFileRepository).save(file);
    }

    @Test
    void updateTagsWithEmptyListClearsTags() {
        MusicFile file = MusicFile.builder().id("f1").customTags(List.of("old")).build();
        when(musicFileRepository.save(any(MusicFile.class))).thenAnswer(inv -> inv.getArgument(0));

        MusicFile saved = service.updateTags(file, List.of());

        assertNull(saved.getCustomTags());
    }

    @Test
    void listTagsWithCountsProjectsAggregateResults() {
        @SuppressWarnings("unchecked")
        AggregationResults<Document> results = mock(AggregationResults.class);
        when(results.getMappedResults()).thenReturn(List.of(
                new Document("tag", "acoustic").append("count", 3),
                new Document("tag", "live").append("count", 7)));
        when(mongoTemplate.aggregate(any(Aggregation.class), eq(MusicFile.class), eq(Document.class)))
                .thenReturn(results);

        List<LibraryService.TagCount> out = service.listTagsWithCounts("user1");

        assertEquals(2, out.size());
        assertEquals("acoustic", out.get(0).tag());
        assertEquals(3, out.get(0).count());
        assertEquals("live", out.get(1).tag());
        assertEquals(7, out.get(1).count());
    }

    @Test
    void listTagsWithCountsSkipsBlankTagKeys() {
        @SuppressWarnings("unchecked")
        AggregationResults<Document> results = mock(AggregationResults.class);
        when(results.getMappedResults()).thenReturn(List.of(
                new Document("tag", "").append("count", 1),
                new Document("tag", "live").append("count", 2)));
        when(mongoTemplate.aggregate(any(Aggregation.class), eq(MusicFile.class), eq(Document.class)))
                .thenReturn(results);

        List<LibraryService.TagCount> out = service.listTagsWithCounts("user1");

        assertEquals(1, out.size());
        assertEquals("live", out.get(0).tag());
    }

    @Test
    void bulkUpdateTagsAddsNormalizedTags() {
        MusicFile f1 = MusicFile.builder().id("f1").customTags(new ArrayList<>(List.of("rock"))).build();
        MusicFile f2 = MusicFile.builder().id("f2").customTags(null).build();
        when(mongoTemplate.find(any(Query.class), eq(MusicFile.class))).thenReturn(List.of(f1, f2));
        when(musicFileRepository.save(any(MusicFile.class))).thenAnswer(inv -> inv.getArgument(0));

        LibraryService.BulkTagResult result = service.bulkUpdateTags(
                "user1", List.of("f1", "f2"), List.of("LIVE", "acoustic"), List.of());

        assertEquals(2, result.modified());
        assertEquals(0, result.notFound());
        assertEquals(List.of("rock", "live", "acoustic"), f1.getCustomTags());
        assertEquals(List.of("live", "acoustic"), f2.getCustomTags());
    }

    @Test
    void bulkUpdateTagsRemovesTagsCaseInsensitively() {
        MusicFile f1 = MusicFile.builder().id("f1")
                .customTags(new ArrayList<>(List.of("rock", "live", "demo"))).build();
        when(mongoTemplate.find(any(Query.class), eq(MusicFile.class))).thenReturn(List.of(f1));
        when(musicFileRepository.save(any(MusicFile.class))).thenAnswer(inv -> inv.getArgument(0));

        LibraryService.BulkTagResult result = service.bulkUpdateTags(
                "user1", List.of("f1"), List.of(), List.of("Live"));

        assertEquals(1, result.modified());
        assertEquals(List.of("rock", "demo"), f1.getCustomTags());
    }

    @Test
    void bulkUpdateTagsClearsTagsWhenAllRemoved() {
        MusicFile f1 = MusicFile.builder().id("f1")
                .customTags(new ArrayList<>(List.of("only"))).build();
        when(mongoTemplate.find(any(Query.class), eq(MusicFile.class))).thenReturn(List.of(f1));
        when(musicFileRepository.save(any(MusicFile.class))).thenAnswer(inv -> inv.getArgument(0));

        service.bulkUpdateTags("user1", List.of("f1"), List.of(), List.of("only"));

        assertNull(f1.getCustomTags(), "empty tag list should be stored as null");
    }

    @Test
    void bulkUpdateTagsSkipsUnchangedFiles() {
        MusicFile f1 = MusicFile.builder().id("f1")
                .customTags(new ArrayList<>(List.of("live"))).build();
        when(mongoTemplate.find(any(Query.class), eq(MusicFile.class))).thenReturn(List.of(f1));

        LibraryService.BulkTagResult result = service.bulkUpdateTags(
                "user1", List.of("f1"), List.of("live"), List.of());

        assertEquals(0, result.modified(), "adding an already-present tag should not trigger a save");
        verify(musicFileRepository, never()).save(any());
    }

    @Test
    void bulkUpdateTagsReportsNotFoundCount() {
        MusicFile f1 = MusicFile.builder().id("f1").customTags(null).build();
        when(mongoTemplate.find(any(Query.class), eq(MusicFile.class))).thenReturn(List.of(f1));
        when(musicFileRepository.save(any(MusicFile.class))).thenAnswer(inv -> inv.getArgument(0));

        LibraryService.BulkTagResult result = service.bulkUpdateTags(
                "user1", List.of("f1", "missing-a", "missing-b"), List.of("live"), List.of());

        assertEquals(1, result.modified());
        assertEquals(2, result.notFound());
    }

    @Test
    void bulkUpdateTagsRejectsWhenResultWouldExceedCap() {
        List<String> existing = new ArrayList<>();
        for (int i = 0; i < 19; i++) existing.add("tag" + i);
        MusicFile f1 = MusicFile.builder().id("f1")
                .customTags(new ArrayList<>(existing)).build();
        when(mongoTemplate.find(any(Query.class), eq(MusicFile.class))).thenReturn(List.of(f1));

        assertThrows(IllegalArgumentException.class, () -> service.bulkUpdateTags(
                "user1", List.of("f1"), List.of("twenty", "twenty-one"), List.of()));
        verify(musicFileRepository, never()).save(any());
    }

    @Test
    void bulkUpdateTagsNoopWhenNothingToDo() {
        LibraryService.BulkTagResult result = service.bulkUpdateTags(
                "user1", List.of("f1"), List.of(), List.of());

        assertEquals(0, result.modified());
        verify(mongoTemplate, never()).find(any(Query.class), eq(MusicFile.class));
    }

    @Test
    void bulkUpdateTagsNoopWhenFileIdsEmpty() {
        LibraryService.BulkTagResult result = service.bulkUpdateTags(
                "user1", List.of(), List.of("live"), List.of());

        assertEquals(0, result.modified());
        verify(mongoTemplate, never()).find(any(Query.class), eq(MusicFile.class));
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
