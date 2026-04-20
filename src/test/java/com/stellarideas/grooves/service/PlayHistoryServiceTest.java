package com.stellarideas.grooves.service;

import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.PlayEvent;
import com.stellarideas.grooves.repository.MusicFileRepository;
import com.stellarideas.grooves.repository.PlayEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PlayHistoryServiceTest {

    private PlayHistoryService service;
    private PlayEventRepository playEventRepository;
    private MusicFileRepository musicFileRepository;
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        playEventRepository = mock(PlayEventRepository.class);
        musicFileRepository = mock(MusicFileRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        service = new PlayHistoryService(playEventRepository, musicFileRepository, mongoTemplate);
    }

    @Test
    void recordPlaySucceedsForOwnedFile() {
        MusicFile file = MusicFile.builder().id("f1").userId("user1").build();
        when(musicFileRepository.findByIdAndUserIdAndDeletedFalse("f1", "user1"))
                .thenReturn(Optional.of(file));

        boolean result = service.recordPlay("user1", "f1", 120_000, true);

        assertTrue(result);

        ArgumentCaptor<PlayEvent> evCaptor = ArgumentCaptor.forClass(PlayEvent.class);
        verify(playEventRepository).save(evCaptor.capture());
        PlayEvent saved = evCaptor.getValue();
        assertEquals("user1", saved.getUserId());
        assertEquals("f1", saved.getMusicFileId());
        assertEquals(120_000, saved.getListenedMs());
        assertTrue(saved.isCompleted());
        assertNotNull(saved.getPlayedAt());

        ArgumentCaptor<Query> qCaptor = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> uCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(qCaptor.capture(), uCaptor.capture(), eq(MusicFile.class));

        // Query should scope to both id and userId
        org.bson.Document qDoc = qCaptor.getValue().getQueryObject();
        assertEquals("f1", qDoc.get("_id"));
        assertEquals("user1", qDoc.get("userId"));

        // Update should $inc playCount and $set lastPlayedAt
        org.bson.Document uDoc = uCaptor.getValue().getUpdateObject();
        org.bson.Document inc = (org.bson.Document) uDoc.get("$inc");
        org.bson.Document set = (org.bson.Document) uDoc.get("$set");
        assertNotNull(inc);
        assertEquals(1, inc.get("playCount"));
        assertNotNull(set);
        assertTrue(set.get("lastPlayedAt") instanceof java.time.Instant);
    }

    @Test
    void recordPlayReturnsFalseForMissingFile() {
        when(musicFileRepository.findByIdAndUserIdAndDeletedFalse("missing", "user1"))
                .thenReturn(Optional.empty());

        boolean result = service.recordPlay("user1", "missing", 60_000, false);

        assertFalse(result);
        verify(playEventRepository, never()).save(any());
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(MusicFile.class));
    }

    @Test
    void recordPlayRejectsCrossUserAccess() {
        // File exists but belongs to another user — the ownership-scoped query returns empty
        when(musicFileRepository.findByIdAndUserIdAndDeletedFalse("f1", "intruder"))
                .thenReturn(Optional.empty());

        boolean result = service.recordPlay("intruder", "f1", 100_000, true);

        assertFalse(result);
        verify(playEventRepository, never()).save(any());
    }

    @Test
    void recordPlayClampsNegativeListenedMs() {
        MusicFile file = MusicFile.builder().id("f1").userId("user1").build();
        when(musicFileRepository.findByIdAndUserIdAndDeletedFalse("f1", "user1"))
                .thenReturn(Optional.of(file));

        service.recordPlay("user1", "f1", -500, false);

        ArgumentCaptor<PlayEvent> evCaptor = ArgumentCaptor.forClass(PlayEvent.class);
        verify(playEventRepository).save(evCaptor.capture());
        assertEquals(0, evCaptor.getValue().getListenedMs());
    }
}
