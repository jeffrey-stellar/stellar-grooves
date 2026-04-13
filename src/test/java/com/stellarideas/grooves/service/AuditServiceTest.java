package com.stellarideas.grooves.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuditServiceTest {

    private final AuditService auditService = new AuditService();

    @Test
    void logWithAllParameters() {
        assertDoesNotThrow(() ->
                auditService.log("admin", AuditService.Action.LOGIN_SUCCESS, "/path", "detail info"));
    }

    @Test
    void logWithResourceOnly() {
        assertDoesNotThrow(() ->
                auditService.log("user1", AuditService.Action.SCAN_DIRECTORY, "/music"));
    }

    @Test
    void logWithActionOnly() {
        assertDoesNotThrow(() ->
                auditService.log("user1", AuditService.Action.LOGOUT));
    }

    @Test
    void logHandlesNullUsername() {
        assertDoesNotThrow(() ->
                auditService.log(null, AuditService.Action.LOGIN_FAILED, null, null));
    }

    @Test
    void logHandlesNullResource() {
        assertDoesNotThrow(() ->
                auditService.log("user1", AuditService.Action.LIBRARY_CLEAR, null, "50 files"));
    }

    @Test
    void allActionsEnumerated() {
        // Ensure all actions can be logged without error
        for (AuditService.Action action : AuditService.Action.values()) {
            assertDoesNotThrow(() -> auditService.log("test", action, "resource", "detail"));
        }
    }
}
