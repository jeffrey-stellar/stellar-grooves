package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.dto.LoginRequest;
import com.stellarideas.grooves.dto.SignupRequest;
import com.stellarideas.grooves.repository.BlacklistedTokenRepository;
import com.stellarideas.grooves.repository.UserRepository;
import com.stellarideas.grooves.security.JwtUtils;
import com.stellarideas.grooves.service.AuditService;
import com.stellarideas.grooves.service.LoginAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private AuthController controller;
    private UserRepository userRepository;
    private AuthenticationManager authenticationManager;
    private PasswordEncoder passwordEncoder;
    private JwtUtils jwtUtils;
    private LoginAttemptService loginAttemptService;
    private AuditService auditService;
    private BlacklistedTokenRepository blacklistedTokenRepository;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        authenticationManager = mock(AuthenticationManager.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtUtils = mock(JwtUtils.class);
        loginAttemptService = mock(LoginAttemptService.class);
        auditService = mock(AuditService.class);
        blacklistedTokenRepository = mock(BlacklistedTokenRepository.class);

        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        com.stellarideas.grooves.service.MessageHelper msgHelper = new com.stellarideas.grooves.service.MessageHelper(messageSource);

        com.stellarideas.grooves.repository.RefreshTokenRepository refreshTokenRepository = mock(com.stellarideas.grooves.repository.RefreshTokenRepository.class);
        com.stellarideas.grooves.repository.PasswordResetTokenRepository passwordResetTokenRepository = mock(com.stellarideas.grooves.repository.PasswordResetTokenRepository.class);
        controller = new AuthController(authenticationManager, userRepository, passwordEncoder, jwtUtils,
                msgHelper, loginAttemptService, auditService, blacklistedTokenRepository, refreshTokenRepository, passwordResetTokenRepository);
    }

    private SignupRequest signupRequest(String username, String email, String password) {
        SignupRequest req = new SignupRequest();
        req.setUsername(username);
        req.setEmail(email);
        req.setPassword(password);
        return req;
    }

    @Test
    void signupSucceeds() {
        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(userRepository.existsByEmail("new@test.com")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("encoded");

        ResponseEntity<?> response = controller.registerUser(signupRequest("newuser", "new@test.com", "password123"));

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("User registered successfully", body.get("message"));
    }

    @Test
    void signupRejectsDuplicateUsername() {
        when(userRepository.existsByUsername("taken")).thenReturn(true);

        ResponseEntity<?> response = controller.registerUser(signupRequest("taken", "a@b.com", "password123"));

        assertEquals(400, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("Username or email is already registered", body.get("error"));
    }

    @Test
    void signupRejectsDuplicateEmail() {
        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("taken@test.com")).thenReturn(true);

        ResponseEntity<?> response = controller.registerUser(signupRequest("newuser", "taken@test.com", "password123"));

        assertEquals(400, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("Username or email is already registered", body.get("error"));
    }

    @Test
    void signinRejectsLockedAccount() {
        when(loginAttemptService.isLockedOut("lockeduser")).thenReturn(true);

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("lockeduser");
        loginRequest.setPassword("password123");

        ResponseEntity<?> response = controller.authenticateUser(loginRequest);

        assertEquals(403, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertTrue(body.get("error").toString().contains("locked"));
    }

    @Test
    void signinRecordsFailedAttempt() {
        when(loginAttemptService.isLockedOut("baduser")).thenReturn(false);
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("baduser");
        loginRequest.setPassword("wrongpassword");

        ResponseEntity<?> response = controller.authenticateUser(loginRequest);

        assertEquals(401, response.getStatusCode().value());
        verify(loginAttemptService).loginFailed("baduser");
    }

    @Test
    void signinResetsAttemptsOnSuccess() {
        when(loginAttemptService.isLockedOut("testuser")).thenReturn(false);

        var userDetails = org.springframework.security.core.userdetails.User
                .withUsername("testuser")
                .password("irrelevant")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();
        Authentication auth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(jwtUtils.generateJwtToken(any())).thenReturn("mock-jwt-token");

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("testuser");
        loginRequest.setPassword("password123");

        controller.authenticateUser(loginRequest);

        verify(loginAttemptService).loginSucceeded("testuser");
    }

    @Test
    void signinReturnsToken() {
        when(loginAttemptService.isLockedOut("testuser")).thenReturn(false);

        var userDetails = org.springframework.security.core.userdetails.User
                .withUsername("testuser")
                .password("irrelevant")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();
        Authentication auth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(jwtUtils.generateJwtToken(any())).thenReturn("mock-jwt-token");

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("testuser");
        loginRequest.setPassword("password123");

        ResponseEntity<?> response = controller.authenticateUser(loginRequest);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("mock-jwt-token", body.get("token"));
        assertEquals("testuser", body.get("username"));
    }
}
