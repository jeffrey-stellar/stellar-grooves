package com.stellarideas.grooves.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.stellarideas.grooves.dto.PlaybackQueueDTO;
import com.stellarideas.grooves.model.PlaybackQueue;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.PlaybackQueueRepository;
import com.stellarideas.grooves.repository.UserRepository;
import com.stellarideas.grooves.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Edge case tests for the playback queue endpoints:
 * quota enforcement, ownership validation, corrupted data, and boundary conditions.
 */
class QueueEdgeCaseTest {

    private LibraryController controller;
    private LibraryService libraryService;
    private PlaybackQueueRepository playbackQueueRepository;
    private User testUser;

    @BeforeEach
    void setUp() {
        MusicScannerService scannerService = mock(MusicScannerService.class);
        libraryService = mock(LibraryService.class);
        AuditService auditService = mock(AuditService.class);
        UserRepository userRepository = mock(UserRepository.class);
        ScanRateLimiter scanRateLimiter = mock(ScanRateLimiter.class);
        playbackQueueRepository = mock(PlaybackQueueRepository.class);
        ScanProgressEmitter scanProgressEmitter = mock(ScanProgressEmitter.class);
        UserRateLimiter userRateLimiter = mock(UserRateLimiter.class);

        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        MessageHelper msgHelper = new MessageHelper(messageSource);

        when(scanRateLimiter.tryAcquire(anyString())).thenReturn(true);
        when(userRateLimiter.tryAcquire(anyString(), anyString())).thenReturn(true);

        controller = new LibraryController(scannerService, libraryService, msgHelper,
                auditService, userRepository, scanRateLimiter,
                playbackQueueRepository, scanProgressEmitter, userRateLimiter);

        testUser = new User();
        testUser.setId("user1");
        testUser.setUsername("testuser");

        // Default: no maxQueueTracks override — use the @Value default (5000)
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "maxQueueTracks", 5000);
    }

    // ── Quota enforcement ───────────────────────────────────

    @Test
    void saveQueueRejectsWhenExceedingMaxTracks() {
        List<String> oversized = IntStream.range(0, 5001)
                .mapToObj(i -> "track-" + i)
                .collect(Collectors.toList());

        PlaybackQueueDTO dto = new PlaybackQueueDTO();
        dto.setTrackIds(oversized);

        ResponseEntity<?> response = controller.saveQueue(testUser, dto);

        assertEquals(400, response.getStatusCode().value());
        ProblemDetail pd = (ProblemDetail) response.getBody();
        assertNotNull(pd);
        assertTrue(pd.getDetail().contains("5000"));
        verify(playbackQueueRepository, never()).save(any());
    }

    @Test
    void saveQueueAcceptsExactlyMaxTracks() {
        List<String> exact = IntStream.range(0, 5000)
                .mapToObj(i -> "track-" + i)
                .collect(Collectors.toList());
        when(playbackQueueRepository.findByUserId("user1")).thenReturn(Optional.empty());
        when(libraryService.findOwnedTrackIds(exact, "user1"))
                .thenReturn(new HashSet<>(exact));

        PlaybackQueueDTO dto = new PlaybackQueueDTO();
        dto.setTrackIds(exact);

        ResponseEntity<?> response = controller.saveQueue(testUser, dto);

        assertEquals(200, response.getStatusCode().value());
        verify(playbackQueueRepository).save(any());
    }

    // ── Ownership validation ────────────────────────────────

    @Test
    void saveQueueFiltersOutUnownedTracks() {
        List<String> requested = List.of("owned1", "owned2", "foreign1", "foreign2");
        when(playbackQueueRepository.findByUserId("user1")).thenReturn(Optional.empty());
        // Only owned1 and owned2 belong to the user
        when(libraryService.findOwnedTrackIds(requested, "user1"))
                .thenReturn(Set.of("owned1", "owned2"));

        PlaybackQueueDTO dto = new PlaybackQueueDTO();
        dto.setTrackIds(requested);
        dto.setCurrentTrackId("owned1");

        ResponseEntity<?> response = controller.saveQueue(testUser, dto);

        assertEquals(200, response.getStatusCode().value());
        verify(playbackQueueRepository).save(argThat(q -> {
            List<String> saved = q.getTrackIds();
            return saved.size() == 2
                    && saved.contains("owned1")
                    && saved.contains("owned2")
                    && !saved.contains("foreign1");
        }));
    }

    @Test
    void saveQueueClearsCurrentTrackIdWhenNotOwned() {
        List<String> requested = List.of("owned1");
        when(playbackQueueRepository.findByUserId("user1")).thenReturn(Optional.empty());
        when(libraryService.findOwnedTrackIds(requested, "user1"))
                .thenReturn(Set.of("owned1"));

        PlaybackQueueDTO dto = new PlaybackQueueDTO();
        dto.setTrackIds(requested);
        dto.setCurrentTrackId("foreign1"); // not in the owned list

        ResponseEntity<?> response = controller.saveQueue(testUser, dto);

        assertEquals(200, response.getStatusCode().value());
        verify(playbackQueueRepository).save(argThat(q ->
                q.getCurrentTrackId() == null
        ));
    }

    @Test
    void saveQueueKeepsCurrentTrackIdWhenOwned() {
        List<String> requested = List.of("t1", "t2");
        when(playbackQueueRepository.findByUserId("user1")).thenReturn(Optional.empty());
        when(libraryService.findOwnedTrackIds(requested, "user1"))
                .thenReturn(Set.of("t1", "t2"));

        PlaybackQueueDTO dto = new PlaybackQueueDTO();
        dto.setTrackIds(requested);
        dto.setCurrentTrackId("t2");

        controller.saveQueue(testUser, dto);

        verify(playbackQueueRepository).save(argThat(q ->
                "t2".equals(q.getCurrentTrackId())
        ));
    }

    @Test
    void saveQueueWithAllTracksForeignResultsInEmptyQueue() {
        List<String> requested = List.of("foreign1", "foreign2");
        when(playbackQueueRepository.findByUserId("user1")).thenReturn(Optional.empty());
        when(libraryService.findOwnedTrackIds(requested, "user1"))
                .thenReturn(Set.of()); // none owned

        PlaybackQueueDTO dto = new PlaybackQueueDTO();
        dto.setTrackIds(requested);
        dto.setCurrentTrackId("foreign1");

        controller.saveQueue(testUser, dto);

        verify(playbackQueueRepository).save(argThat(q ->
                q.getTrackIds().isEmpty() && q.getCurrentTrackId() == null
        ));
    }

    // ── Null/empty edge cases ───────────────────────────────

    @Test
    void saveQueueWithNullTrackIdsSkipsOwnershipCheck() {
        when(playbackQueueRepository.findByUserId("user1")).thenReturn(Optional.empty());

        PlaybackQueueDTO dto = new PlaybackQueueDTO();
        dto.setTrackIds(null);
        dto.setCurrentTrackId(null);

        ResponseEntity<?> response = controller.saveQueue(testUser, dto);

        assertEquals(200, response.getStatusCode().value());
        // findOwnedTrackIds should not be called for empty lists
        verify(libraryService, never()).findOwnedTrackIds(anyList(), anyString());
        verify(playbackQueueRepository).save(argThat(q ->
                q.getTrackIds().isEmpty()
        ));
    }

    @Test
    void saveQueueWithEmptyTrackIdsSkipsOwnershipCheck() {
        when(playbackQueueRepository.findByUserId("user1")).thenReturn(Optional.empty());

        PlaybackQueueDTO dto = new PlaybackQueueDTO();
        dto.setTrackIds(List.of());

        ResponseEntity<?> response = controller.saveQueue(testUser, dto);

        assertEquals(200, response.getStatusCode().value());
        verify(libraryService, never()).findOwnedTrackIds(anyList(), anyString());
    }

    @Test
    void saveQueueWithNullCurrentTrackIdSavesNull() {
        when(playbackQueueRepository.findByUserId("user1")).thenReturn(Optional.empty());
        when(libraryService.findOwnedTrackIds(List.of("t1"), "user1"))
                .thenReturn(Set.of("t1"));

        PlaybackQueueDTO dto = new PlaybackQueueDTO();
        dto.setTrackIds(List.of("t1"));
        dto.setCurrentTrackId(null);

        controller.saveQueue(testUser, dto);

        verify(playbackQueueRepository).save(argThat(q ->
                q.getCurrentTrackId() == null
        ));
    }

    // ── Queue retrieval edge cases ──────────────────────────

    @Test
    void getQueueReturnsEmptyStructureWhenNoQueue() {
        when(playbackQueueRepository.findByUserId("user1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getQueue(testUser);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(List.of(), body.get("trackIds"));
        assertEquals("", body.get("currentTrackId"));
        assertEquals(false, body.get("shuffle"));
    }

    @Test
    void getQueueReturnsExistingQueueAsDTO() {
        PlaybackQueue queue = new PlaybackQueue();
        queue.setUserId("user1");
        queue.setTrackIds(List.of("a", "b"));
        queue.setCurrentTrackId("b");
        queue.setShuffle(true);
        when(playbackQueueRepository.findByUserId("user1")).thenReturn(Optional.of(queue));

        ResponseEntity<?> response = controller.getQueue(testUser);

        assertEquals(200, response.getStatusCode().value());
        PlaybackQueueDTO dto = (PlaybackQueueDTO) response.getBody();
        assertNotNull(dto);
        assertEquals(List.of("a", "b"), dto.getTrackIds());
        assertEquals("b", dto.getCurrentTrackId());
        assertTrue(dto.isShuffle());
    }

    // ── Clear queue ─────────────────────────────────────────

    @Test
    void clearQueueDeletesAndReturnsMessage() {
        ResponseEntity<?> response = controller.clearQueue(testUser);

        assertEquals(200, response.getStatusCode().value());
        verify(playbackQueueRepository).deleteByUserId("user1");
    }

    // ── Duplicate track IDs ─────────────────────────────────

    @Test
    void saveQueuePreservesDuplicateOwnedTrackIds() {
        // Some playlists allow the same track multiple times
        List<String> requested = List.of("t1", "t2", "t1", "t2", "t1");
        when(playbackQueueRepository.findByUserId("user1")).thenReturn(Optional.empty());
        when(libraryService.findOwnedTrackIds(requested, "user1"))
                .thenReturn(Set.of("t1", "t2"));

        PlaybackQueueDTO dto = new PlaybackQueueDTO();
        dto.setTrackIds(requested);

        controller.saveQueue(testUser, dto);

        verify(playbackQueueRepository).save(argThat(q ->
                q.getTrackIds().size() == 5 // duplicates preserved
        ));
    }
}
