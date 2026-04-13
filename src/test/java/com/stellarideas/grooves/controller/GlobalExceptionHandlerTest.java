package com.stellarideas.grooves.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

        ResponseEntity<Map<String, Object>> response = handler.handleValidationErrors(ex);

        assertEquals(400, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertEquals("Validation failed", body.get("error"));
        @SuppressWarnings("unchecked")
        Map<String, String> fields = (Map<String, String>) body.get("fields");
        assertEquals("must not be blank", fields.get("username"));
        assertEquals("must be a valid email", fields.get("email"));
    }

    @Test
    void handleIllegalArgumentReturns400() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid genre: BLUES");

        ResponseEntity<Map<String, String>> response = handler.handleIllegalArgument(ex);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Invalid genre: BLUES", response.getBody().get("error"));
    }

    @Test
    void handleBadCredentialsReturns401WithGenericMessage() {
        BadCredentialsException ex = new BadCredentialsException("Bad credentials");

        ResponseEntity<Map<String, String>> response = handler.handleBadCredentials(ex);

        assertEquals(401, response.getStatusCode().value());
        assertEquals("Invalid username or password", response.getBody().get("error"));
    }

    @Test
    void handleUsernameNotFoundReturns401WithGenericMessage() {
        UsernameNotFoundException ex = new UsernameNotFoundException("User not found: admin");

        ResponseEntity<Map<String, String>> response = handler.handleUsernameNotFound(ex);

        assertEquals(401, response.getStatusCode().value());
        // Must NOT reveal whether the username exists
        assertEquals("Invalid username or password", response.getBody().get("error"));
    }

    @Test
    void handleAccessDeniedReturns403() {
        AccessDeniedException ex = new AccessDeniedException("Forbidden");

        ResponseEntity<Map<String, String>> response = handler.handleAccessDenied(ex);

        assertEquals(403, response.getStatusCode().value());
        assertEquals("Access denied", response.getBody().get("error"));
    }

    @Test
    void handleGenericExceptionReturns500WithoutDetails() {
        Exception ex = new RuntimeException("Database connection failed");

        ResponseEntity<Map<String, String>> response = handler.handleGenericException(ex);

        assertEquals(500, response.getStatusCode().value());
        // Must NOT expose internal error details
        assertEquals("An unexpected error occurred", response.getBody().get("error"));
        assertFalse(response.getBody().toString().contains("Database"));
    }
}
