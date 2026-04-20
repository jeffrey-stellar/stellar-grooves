package com.stellarideas.grooves.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages scan progress streaming via both SSE and WebSocket (STOMP).
 * Each user can have one active SSE emitter. WebSocket subscribers receive
 * events on {@code /topic/scan/{userId}}.
 *
 * <p>A scheduled cleanup task runs every 2 minutes to complete and remove
 * emitters that have been open longer than the timeout, protecting against
 * memory leaks from abandoned browser connections.</p>
 */
@Component
public class ScanProgressEmitter {

    private static final Logger logger = LoggerFactory.getLogger(ScanProgressEmitter.class);
    static final long EMITTER_TIMEOUT = 10 * 60 * 1000L; // 10 minutes

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> emitterCreatedAt = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messagingTemplate;

    public ScanProgressEmitter(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public SseEmitter createEmitter(String userId) {
        // Close any existing emitter for this user
        SseEmitter existing = emitters.remove(userId);
        if (existing != null) {
            existing.complete();
        }
        emitterCreatedAt.remove(userId);

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT);
        emitter.onCompletion(() -> {
            emitters.remove(userId, emitter);
            emitterCreatedAt.remove(userId);
        });
        emitter.onTimeout(() -> {
            emitters.remove(userId, emitter);
            emitterCreatedAt.remove(userId);
        });
        emitter.onError(e -> {
            emitters.remove(userId, emitter);
            emitterCreatedAt.remove(userId);
        });
        emitters.put(userId, emitter);
        emitterCreatedAt.put(userId, Instant.now());
        return emitter;
    }

    /**
     * Send a one-shot snapshot event to the emitter for this user. Lets reconnecting
     * clients see the current scan state rather than starting from zero. Safe no-op
     * if no emitter is registered.
     */
    public void sendSnapshot(String userId, int saved, int skipped, int errors,
                             String currentFile, String status) {
        sendEvent(userId, "snapshot", Map.of(
                "saved", saved,
                "skipped", skipped,
                "errors", errors,
                "currentFile", currentFile != null ? currentFile : "",
                "status", status
        ));
    }

    /**
     * Periodically clean up emitters that have exceeded the timeout.
     * This catches cases where the SseEmitter's built-in timeout callback
     * doesn't fire (e.g. abrupt client disconnect without TCP reset).
     */
    @Scheduled(fixedRate = 120_000) // every 2 minutes
    public void cleanupStaleEmitters() {
        Instant cutoff = Instant.now().minusMillis(EMITTER_TIMEOUT);
        int cleaned = 0;
        for (Map.Entry<String, Instant> entry : emitterCreatedAt.entrySet()) {
            if (entry.getValue().isBefore(cutoff)) {
                String userId = entry.getKey();
                SseEmitter stale = emitters.remove(userId);
                emitterCreatedAt.remove(userId);
                if (stale != null) {
                    try {
                        stale.complete();
                    } catch (Exception e) {
                        logger.debug("Error completing stale emitter for user '{}': {}", userId, e.getMessage());
                    }
                    cleaned++;
                }
            }
        }
        if (cleaned > 0) {
            logger.info("Cleaned up {} stale SSE emitter(s)", cleaned);
        }
    }

    int activeEmitterCount() {
        return emitters.size();
    }

    public void sendEvent(String userId, String eventType, Object data) {
        // Send via SSE (for legacy / fallback clients)
        SseEmitter emitter = emitters.get(userId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventType)
                        .data(data));
            } catch (IOException e) {
                logger.debug("SSE send failed for user '{}': {}", userId, e.getMessage());
                emitters.remove(userId);
                emitterCreatedAt.remove(userId);
            }
        }

        // Send via WebSocket STOMP
        Map<String, Object> wsPayload = new LinkedHashMap<>();
        wsPayload.put("type", eventType);
        wsPayload.put("data", data);
        messagingTemplate.convertAndSend("/topic/scan/" + userId, wsPayload);
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
        emitterCreatedAt.remove(userId);
        if (emitter != null) {
            emitter.complete();
        }
    }

    public void sendError(String userId, String message) {
        sendEvent(userId, "error", Map.of("message", message));
        SseEmitter emitter = emitters.remove(userId);
        emitterCreatedAt.remove(userId);
        if (emitter != null) {
            emitter.complete();
        }
    }
}
