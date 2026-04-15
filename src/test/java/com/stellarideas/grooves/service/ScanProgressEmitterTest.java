package com.stellarideas.grooves.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ScanProgressEmitterTest {

    private ScanProgressEmitter emitter;
    private SimpMessagingTemplate messagingTemplate;

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        emitter = new ScanProgressEmitter(messagingTemplate);
    }

    @Test
    void createEmitterReturnsNonNullSseEmitter() {
        SseEmitter result = emitter.createEmitter("user1");

        assertNotNull(result);
    }

    @Test
    void createEmitterClosesExistingEmitterForSameUser() {
        SseEmitter first = emitter.createEmitter("user1");
        SseEmitter second = emitter.createEmitter("user1");

        assertNotNull(second);
        assertNotSame(first, second);
    }

    @Test
    void sendEventDoesNothingWhenNoEmitterRegistered() {
        // Should not throw
        assertDoesNotThrow(() ->
                emitter.sendEvent("nonexistent", "progress", "data"));
    }

    @Test
    void sendProgressDoesNotThrowForNonExistentUser() {
        assertDoesNotThrow(() ->
                emitter.sendProgress("nonexistent", 1, 0, 0, "file.mp3"));
    }

    @Test
    void sendCompleteRemovesEmitter() {
        emitter.createEmitter("user1");

        emitter.sendComplete("user1", 10, 2, 1);

        assertDoesNotThrow(() ->
                emitter.sendEvent("user1", "progress", "data"));
    }

    @Test
    void sendErrorRemovesEmitter() {
        emitter.createEmitter("user1");

        emitter.sendError("user1", "something went wrong");

        assertDoesNotThrow(() ->
                emitter.sendEvent("user1", "progress", "data"));
    }

    @Test
    void sendEventRemovesEmitterOnIOException() throws Exception {
        SseEmitter mockSseEmitter = mock(SseEmitter.class);
        doThrow(new IOException("connection lost"))
                .when(mockSseEmitter).send(any(SseEmitter.SseEventBuilder.class));

        var field = ScanProgressEmitter.class.getDeclaredField("emitters");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var emitters = (ConcurrentHashMap<String, SseEmitter>) field.get(emitter);
        emitters.put("user1", mockSseEmitter);

        assertDoesNotThrow(() ->
                emitter.sendEvent("user1", "progress", "data"));

        assertFalse(emitters.containsKey("user1"));
    }

    @Test
    void sendProgressHandlesNullCurrentFile() {
        assertDoesNotThrow(() ->
                emitter.sendProgress("user1", 5, 1, 0, null));
    }

    @Test
    void createEmitterForDifferentUsersReturnsDifferentEmitters() {
        SseEmitter emitter1 = emitter.createEmitter("user1");
        SseEmitter emitter2 = emitter.createEmitter("user2");

        assertNotNull(emitter1);
        assertNotNull(emitter2);
        assertNotSame(emitter1, emitter2);
    }

    @Test
    void cleanupStaleEmittersRemovesExpiredEntries() {
        emitter.createEmitter("user1");
        emitter.createEmitter("user2");

        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Instant> createdAt =
                (ConcurrentHashMap<String, Instant>) ReflectionTestUtils.getField(emitter, "emitterCreatedAt");
        createdAt.put("user1", Instant.now().minusMillis(ScanProgressEmitter.EMITTER_TIMEOUT + 1000));

        emitter.cleanupStaleEmitters();

        assertEquals(1, emitter.activeEmitterCount());
    }

    @Test
    void cleanupStaleEmittersDoesNothingWhenAllFresh() {
        emitter.createEmitter("user1");
        emitter.createEmitter("user2");

        emitter.cleanupStaleEmitters();

        assertEquals(2, emitter.activeEmitterCount());
    }

    @Test
    void cleanupStaleEmittersHandlesEmptyMap() {
        assertDoesNotThrow(() -> emitter.cleanupStaleEmitters());
        assertEquals(0, emitter.activeEmitterCount());
    }

    @Test
    void sendEventBroadcastsViaWebSocket() {
        emitter.sendEvent("user1", "progress", "data");

        verify(messagingTemplate).convertAndSend(eq("/topic/scan/user1"), any(Object.class));
    }

    @Test
    void sendCompleteAlsoBroadcastsViaWebSocket() {
        emitter.sendComplete("user1", 10, 2, 1);

        verify(messagingTemplate).convertAndSend(eq("/topic/scan/user1"), any(Object.class));
    }
}
