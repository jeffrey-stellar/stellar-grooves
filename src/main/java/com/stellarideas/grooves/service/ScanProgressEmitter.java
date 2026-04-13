package com.stellarideas.grooves.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages SSE connections for per-user scan progress streaming.
 * Each user can have one active emitter. The scanner service pushes events
 * through this component; the controller creates and returns emitters.
 */
@Component
public class ScanProgressEmitter {

    private static final Logger logger = LoggerFactory.getLogger(ScanProgressEmitter.class);
    private static final long EMITTER_TIMEOUT = 10 * 60 * 1000L; // 10 minutes

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter createEmitter(String userId) {
        // Close any existing emitter for this user
        SseEmitter existing = emitters.remove(userId);
        if (existing != null) {
            existing.complete();
        }

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT);
        emitter.onCompletion(() -> emitters.remove(userId, emitter));
        emitter.onTimeout(() -> emitters.remove(userId, emitter));
        emitter.onError(e -> emitters.remove(userId, emitter));
        emitters.put(userId, emitter);
        return emitter;
    }

    public void sendEvent(String userId, String eventType, Object data) {
        SseEmitter emitter = emitters.get(userId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event()
                    .name(eventType)
                    .data(data));
        } catch (IOException e) {
            logger.debug("SSE send failed for user '{}': {}", userId, e.getMessage());
            emitters.remove(userId);
        }
    }

    public void sendProgress(String userId, int saved, int skipped, int errors, String currentFile) {
        sendEvent(userId, "progress", Map.of(
                "saved", saved,
                "skipped", skipped,
                "errors", errors,
                "currentFile", currentFile != null ? currentFile : ""
        ));
    }

    public void sendComplete(String userId, int saved, int skipped, int errors) {
        sendEvent(userId, "complete", Map.of(
                "saved", saved,
                "skipped", skipped,
                "errors", errors
        ));
        SseEmitter emitter = emitters.remove(userId);
        if (emitter != null) {
            emitter.complete();
        }
    }

    public void sendError(String userId, String message) {
        sendEvent(userId, "error", Map.of("message", message));
        SseEmitter emitter = emitters.remove(userId);
        if (emitter != null) {
            emitter.complete();
        }
    }
}
