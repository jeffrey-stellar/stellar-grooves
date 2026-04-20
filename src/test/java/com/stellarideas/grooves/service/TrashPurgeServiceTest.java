package com.stellarideas.grooves.service;

import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.Playlist;
import com.stellarideas.grooves.repository.MusicFileRepository;
import com.stellarideas.grooves.repository.PlaylistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TrashPurgeServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-20T10:00:00Z");

    private MusicFileRepository musicFileRepository;
    private PlaylistRepository playlistRepository;
    private MongoTemplate mongoTemplate;
    private TrashPurgeService service;

    @BeforeEach
    void setUp() {
        musicFileRepository = mock(MusicFileRepository.class);
        playlistRepository = mock(PlaylistRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        service = new TrashPurgeService(musicFileRepository, playlistRepository, mongoTemplate,
                Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(service, "retentionDays", 30);
    }

    @Test
    void purgeExpiredDeletesMatchingFiles() {
        MusicFile a = MusicFile.builder().id("a").build();
        a.setDeleted(true); a.setDeletedAt(NOW.minusSeconds(40L * 86400)); // 40d old
        MusicFile b = MusicFile.builder().id("b").build();
        b.setDeleted(true); b.setDeletedAt(NOW.minusSeconds(31L * 86400));
        a.setUserId("user1"); b.setUserId("user1");
        when(mongoTemplate.find(any(Query.class), eq(MusicFile.class))).thenReturn(List.of(a, b));
        when(playlistRepository.findByUserId("user1")).thenReturn(List.of());

        TrashPurgeService.PurgeResult r = service.purgeExpired();

        assertEquals(2, r.filesDeleted());
        assertEquals(1, r.usersAffected());
        assertEquals(0, r.playlistsModified());
        verify(musicFileRepository).deleteAll(List.of(a, b));
    }

    @Test
    void purgeExpiredStripsReferencesFromPlaylists() {
        MusicFile file = MusicFile.builder().id("f1").build();
        file.setDeleted(true); file.setDeletedAt(NOW.minusSeconds(35L * 86400));
        file.setUserId("user1");
        when(mongoTemplate.find(any(Query.class), eq(MusicFile.class))).thenReturn(List.of(file));

        Playlist p = new Playlist();
        p.setUserId("user1");
        p.setTrackIds(new ArrayList<>(List.of("f1", "f2")));
        when(playlistRepository.findByUserId("user1")).thenReturn(List.of(p));

        TrashPurgeService.PurgeResult r = service.purgeExpired();

        assertEquals(1, r.filesDeleted());
        assertEquals(1, r.playlistsModified());
        assertEquals(List.of("f2"), p.getTrackIds());
        verify(playlistRepository).saveAll(List.of(p));
    }

    @Test
    void purgeExpiredNoopWhenNothingExpired() {
        when(mongoTemplate.find(any(Query.class), eq(MusicFile.class))).thenReturn(List.of());

        TrashPurgeService.PurgeResult r = service.purgeExpired();

        assertEquals(0, r.filesDeleted());
        verify(musicFileRepository, never()).deleteAll(anyList());
        verify(playlistRepository, never()).findByUserId(anyString());
    }

    @Test
    void purgeExpiredGroupsByUserAndQueriesPerUser() {
        MusicFile a = MusicFile.builder().id("a").build();
        a.setDeleted(true); a.setDeletedAt(NOW.minusSeconds(40L * 86400));
        a.setUserId("user1");
        MusicFile b = MusicFile.builder().id("b").build();
        b.setDeleted(true); b.setDeletedAt(NOW.minusSeconds(40L * 86400));
        b.setUserId("user2");
        when(mongoTemplate.find(any(Query.class), eq(MusicFile.class))).thenReturn(List.of(a, b));
        when(playlistRepository.findByUserId(anyString())).thenReturn(List.of());

        TrashPurgeService.PurgeResult r = service.purgeExpired();

        assertEquals(2, r.usersAffected());
        verify(playlistRepository).findByUserId("user1");
        verify(playlistRepository).findByUserId("user2");
    }

    @Test
    void scheduledPurgeSkipsWhenRetentionDisabled() {
        ReflectionTestUtils.setField(service, "retentionDays", 0);

        service.scheduledPurge();

        verifyNoInteractions(mongoTemplate);
        verifyNoInteractions(musicFileRepository);
    }

    @Test
    void scheduledPurgeSwallowsExceptions() {
        when(mongoTemplate.find(any(Query.class), eq(MusicFile.class)))
                .thenThrow(new RuntimeException("mongo exploded"));

        assertDoesNotThrow(() -> service.scheduledPurge());
    }

    private static String anyString() { return org.mockito.ArgumentMatchers.anyString(); }
    private static <T> List<T> anyList() { return org.mockito.ArgumentMatchers.anyList(); }
}
