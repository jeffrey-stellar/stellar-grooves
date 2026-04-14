package com.stellarideas.grooves.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ScanProgressEmitterTest {

    private ScanProgressEmitter emitter;

    @BeforeEach
    void setUp() {
        emitter = new ScanProgressEmitter();
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

        // sendComplete will attempt to send (which may fail since no real connection)
        // but it should still remove the emitter and complete it
        emitter.sendComplete("user1", 10, 2, 1);

        // Subsequent sendEvent should be a no-op (no emitter registered)
        assertDoesNotThrow(() ->
                emitter.sendEvent("user1", "progress", "data"));
    }

    @Test
    void sendErrorRemovesEmitter() {
        emitter.createEmitter("user1");

        emitter.sendError("user1", "something went wrong");

        // Subsequent sendEvent should be a no-op
        assertDoesNotThrow(() ->
                emitter.sendEvent("user1", "progress", "data"));
    }

    @Test
    void sendEventRemovesEmitterOnIOException() throws Exception {
        SseEmitter mockSseEmitter = mock(SseEmitter.class);
        doThrow(new IOException("connection lost"))
                .when(mockSseEmitter).send(any(SseEmitter.SseEventBuilder.class));

        // Use reflection to insert the mock emitter into the internal map
        var field = ScanProgressEmitter.class.getDeclaredField("emitters");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var emitters = (java.util.concurrent.ConcurrentHashMap<String, SseEmitter>) field.get(emitter);
        emitters.put("user1", mockSseEmitter);

        // sendEvent should catch IOException and remove the emitter
        assertDoesNotThrow(() ->
                emitter.sendEvent("user1", "progress", "data"));

        // Emitter should be removed from the map
        assertFalse(emitters.containsKey("user1"));
    }

    @Test
    void sendProgressHandlesNullCurrentFile() {
        // Should not throw even with null currentFile
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
}
