package com.stellarideas.grooves.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Structured audit logging for security-sensitive operations.
 * All audit events are written to a dedicated logger ("AUDIT") so they can be
 * routed to a separate log file or log aggregation pipeline.
 *
 * <p>Uses SLF4J MDC to attach structured context (user, action, resource)
 * that log frameworks like Logback can format as JSON or key-value pairs.</p>
 */
@Service
public class AuditService {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    public enum Action {
        LOGIN_SUCCESS,
        LOGIN_FAILED,
        LOGIN_LOCKED,
        LOGOUT,
        TOKEN_REFRESH,
        SIGNUP,
        PASSWORD_RESET_REQUEST,
        PASSWORD_RESET_COMPLETE,
        SCAN_DIRECTORY,
        FILE_DELETE,
        BULK_DELETE,
        LIBRARY_CLEAR,
        GENRE_UPDATE,
        RATING_UPDATE,
        PLAYLIST_CREATE,
        PLAYLIST_DELETE,
        PLAYLIST_TRACK_ADD,
        PLAYLIST_TRACK_REMOVE,
        ADMIN_DELETE_USER,
        ADMIN_VIEW_USERS,
        FILE_RESTORE,
        FILE_PERMANENT_DELETE,
        TRASH_EMPTY
    }

    /**
     * Log an audit event with structured context.
     *
     * @param username the user performing the action
     * @param action   the action being performed
     * @param resource the target resource (file ID, path, username, etc.) — may be null
     * @param detail   additional detail — may be null
     */
    public void log(String username, Action action, String resource, String detail) {
        try {
            MDC.put("audit.user", username != null ? username : "anonymous");
            MDC.put("audit.action", action.name());
            if (resource != null) {
                MDC.put("audit.resource", resource);
            }
            String message = buildMessage(username, action, resource, detail);
            audit.info(message);
        } finally {
            MDC.remove("audit.user");
            MDC.remove("audit.action");
            MDC.remove("audit.resource");
        }
    }

    public void log(String username, Action action, String resource) {
        log(username, action, resource, null);
    }

    public void log(String username, Action action) {
        log(username, action, null, null);
    }

    private String buildMessage(String username, Action action, String resource, String detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("[AUDIT] user=").append(username != null ? username : "anonymous");
        sb.append(" action=").append(action.name());
        if (resource != null) {
            sb.append(" resource=").append(resource);
        }
        if (detail != null) {
            sb.append(" detail=").append(detail);
        }
        return sb.toString();
    }
}
