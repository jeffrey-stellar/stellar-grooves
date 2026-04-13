package com.stellarideas.grooves.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void handleValidationErrorsReturns400WithFieldErrors() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "username", "must not be blank"));
        bindingResult.addError(new FieldError("request", "email", "must be a valid email"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);

        ResponseEntity<ProblemDetail> response = handler.handleValidationErrors(ex);

        assertEquals(400, response.getStatusCode().value());
        ProblemDetail body = response.getBody();
        assertEquals("Validation failed", body.getDetail());
        assertEquals("Validation failed", body.getProperties().get("error"));
        @SuppressWarnings("unchecked")
        Map<String, String> fields = (Map<String, String>) body.getProperties().get("fields");
        assertEquals("must not be blank", fields.get("username"));
        assertEquals("must be a valid email", fields.get("email"));
    }

    @Test
    void handleIllegalArgumentReturns400() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid genre: BLUES");

        ResponseEntity<ProblemDetail> response = handler.handleIllegalArgument(ex);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Invalid genre: BLUES", response.getBody().getDetail());
        assertEquals("Invalid genre: BLUES", response.getBody().getProperties().get("error"));
    }

    @Test
    void handleBadCredentialsReturns401WithGenericMessage() {
        BadCredentialsException ex = new BadCredentialsException("Bad credentials");

        ResponseEntity<ProblemDetail> response = handler.handleBadCredentials(ex);

        assertEquals(401, response.getStatusCode().value());
        assertEquals("Invalid username or password", response.getBody().getDetail());
    }

    @Test
    void handleUsernameNotFoundReturns401WithGenericMessage() {
        UsernameNotFoundException ex = new UsernameNotFoundException("User not found: admin");

        ResponseEntity<ProblemDetail> response = handler.handleUsernameNotFound(ex);

        assertEquals(401, response.getStatusCode().value());
        // Must NOT reveal whether the username exists
        assertEquals("Invalid username or password", response.getBody().getDetail());
    }

    @Test
    void handleAccessDeniedReturns403() {
        AccessDeniedException ex = new AccessDeniedException("Forbidden");

        ResponseEntity<ProblemDetail> response = handler.handleAccessDenied(ex);

        assertEquals(403, response.getStatusCode().value());
        assertEquals("Access denied", response.getBody().getDetail());
    }

    @Test
    void handleGenericExceptionReturns500WithoutDetails() {
        Exception ex = new RuntimeException("Database connection failed");

        ResponseEntity<ProblemDetail> response = handler.handleGenericException(ex);

        assertEquals(500, response.getStatusCode().value());
        // Must NOT expose internal error details
        assertEquals("An unexpected error occurred", response.getBody().getDetail());
        assertFalse(response.getBody().getDetail().contains("Database"));
    }
}
