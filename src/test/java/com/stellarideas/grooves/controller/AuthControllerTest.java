package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.dto.LoginRequest;
import com.stellarideas.grooves.dto.PasswordResetExecuteDTO;
import com.stellarideas.grooves.dto.PasswordResetRequestDTO;
import com.stellarideas.grooves.dto.RefreshTokenRequest;
import com.stellarideas.grooves.dto.SignupRequest;
import com.stellarideas.grooves.model.EmailVerificationToken;
import com.stellarideas.grooves.model.PasswordResetToken;
import com.stellarideas.grooves.model.RefreshToken;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.BlacklistedTokenRepository;
import com.stellarideas.grooves.repository.PasswordResetTokenRepository;
import com.stellarideas.grooves.repository.RefreshTokenRepository;
import com.stellarideas.grooves.repository.UserRepository;
import com.stellarideas.grooves.security.JwtUtils;
import com.stellarideas.grooves.service.AuditService;
import com.stellarideas.grooves.service.LoginAttemptService;
import com.stellarideas.grooves.service.MessageHelper;
import com.stellarideas.grooves.service.PasswordResetMailService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AuthControllerTest {

    private AuthController controller;
    private UserRepository userRepository;
    private AuthenticationManager authenticationManager;
    private PasswordEncoder passwordEncoder;
    private JwtUtils jwtUtils;
    private LoginAttemptService loginAttemptService;
    private AuditService auditService;
    private BlacklistedTokenRepository blacklistedTokenRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private PasswordResetTokenRepository passwordResetTokenRepository;
    private PasswordResetMailService passwordResetMailService;
    private com.stellarideas.grooves.repository.EmailVerificationTokenRepository emailVerificationTokenRepository;
    private com.stellarideas.grooves.service.EmailVerificationService emailVerificationService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        authenticationManager = mock(AuthenticationManager.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtUtils = mock(JwtUtils.class);
        loginAttemptService = mock(LoginAttemptService.class);
        auditService = mock(AuditService.class);
        blacklistedTokenRepository = mock(BlacklistedTokenRepository.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        passwordResetTokenRepository = mock(PasswordResetTokenRepository.class);
        passwordResetMailService = mock(PasswordResetMailService.class);
        emailVerificationTokenRepository = mock(com.stellarideas.grooves.repository.EmailVerificationTokenRepository.class);
        emailVerificationService = mock(com.stellarideas.grooves.service.EmailVerificationService.class);

        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        MessageHelper msgHelper = new MessageHelper(messageSource);

        controller = new AuthController(authenticationManager, userRepository, passwordEncoder, jwtUtils,
                msgHelper, loginAttemptService, auditService, blacklistedTokenRepository,
                refreshTokenRepository, passwordResetTokenRepository, passwordResetMailService,
                emailVerificationTokenRepository, emailVerificationService);
    }

    private SignupRequest signupRequest(String username, String email, String password) {
        SignupRequest req = new SignupRequest();
        req.setUsername(username);
        req.setEmail(email);
        req.setPassword(password);
        return req;
    }

    private LoginRequest loginRequest(String username, String password) {
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword(password);
        return req;
    }

    private void mockUserLookup() {
        User user = new User();
        user.setId("user1");
        user.setUsername("testuser");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));
    }

    private Authentication mockAuthentication(String username) {
        var userDetails = org.springframework.security.core.userdetails.User
                .withUsername(username)
                .password("irrelevant")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();
        return new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
    }

    // --- Signup tests ---

    @Test
    void signupSucceeds() {
        when(userRepository.existsByUsernameIgnoreCase("newuser")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("new@test.com")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("encoded");

        ResponseEntity<?> response = controller.registerUser(signupRequest("newuser", "new@test.com", "password123"));

        assertEquals(200, response.getStatusCode().value());
        // save called twice: initial create + setting emailVerified=true (verification disabled by default)
        verify(userRepository, times(2)).save(any(User.class));
        verify(auditService).log(eq("newuser"), eq(AuditService.Action.SIGNUP));
    }

    @Test
    void signupRejectsDuplicateUsername() {
        when(userRepository.existsByUsernameIgnoreCase("taken")).thenReturn(true);

        ResponseEntity<?> response = controller.registerUser(signupRequest("taken", "a@b.com", "password123"));

        assertEquals(409, response.getStatusCode().value());
    }

    @Test
    void signupRejectsDuplicateEmail() {
        when(userRepository.existsByUsernameIgnoreCase("newuser")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("taken@test.com")).thenReturn(true);

        ResponseEntity<?> response = controller.registerUser(signupRequest("newuser", "taken@test.com", "password123"));

        assertEquals(409, response.getStatusCode().value());
    }

    // --- Signin tests ---

    @Test
    void signinRejectsLockedAccount() {
        when(loginAttemptService.isLockedOut("lockeduser")).thenReturn(true);

        ResponseEntity<?> response = controller.authenticateUser(loginRequest("lockeduser", "password123"));

        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void signinRecordsFailedAttempt() {
        when(loginAttemptService.isLockedOut("baduser")).thenReturn(false);
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

        ResponseEntity<?> response = controller.authenticateUser(loginRequest("baduser", "wrongpassword"));

        assertEquals(401, response.getStatusCode().value());
        verify(loginAttemptService).loginFailed("baduser");
    }

    @Test
    void signinHandlesLockedException() {
        when(loginAttemptService.isLockedOut("lockuser")).thenReturn(false);
        when(authenticationManager.authenticate(any())).thenThrow(new LockedException("locked"));

        ResponseEntity<?> response = controller.authenticateUser(loginRequest("lockuser", "pass"));

        assertEquals(401, response.getStatusCode().value());
        verify(auditService).log(eq("lockuser"), eq(AuditService.Action.LOGIN_LOCKED));
    }

    @Test
    void signinResetsAttemptsOnSuccess() {
        when(loginAttemptService.isLockedOut("testuser")).thenReturn(false);
        mockUserLookup();
        when(authenticationManager.authenticate(any())).thenReturn(mockAuthentication("testuser"));
        when(jwtUtils.generateJwtToken(any())).thenReturn("mock-jwt-token");

        controller.authenticateUser(loginRequest("testuser", "password123"));

        verify(loginAttemptService).loginSucceeded("testuser");
    }

    @Test
    void signinReturnsTokenAndRefreshToken() {
        when(loginAttemptService.isLockedOut("testuser")).thenReturn(false);
        mockUserLookup();
        when(authenticationManager.authenticate(any())).thenReturn(mockAuthentication("testuser"));
        when(jwtUtils.generateJwtToken(any())).thenReturn("mock-jwt-token");

        ResponseEntity<?> response = controller.authenticateUser(loginRequest("testuser", "password123"));

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("mock-jwt-token", body.get("token"));
        assertNotNull(body.get("refreshToken"));
        assertEquals("testuser", body.get("username"));
    }

    // --- Refresh token tests ---

    @Test
    void refreshTokenSucceeds() {
        RefreshToken rt = new RefreshToken("user1", 604800000);
        when(refreshTokenRepository.findByToken(rt.getToken())).thenReturn(Optional.of(rt));

        User user = new User();
        user.setId("user1");
        user.setUsername("testuser");
        when(userRepository.findById("user1")).thenReturn(Optional.of(user));
        when(jwtUtils.generateJwtToken(any())).thenReturn("new-jwt");

        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken(rt.getToken());

        ResponseEntity<?> response = controller.refreshToken(request);

        assertEquals(200, response.getStatusCode().value());
        verify(refreshTokenRepository).delete(rt);
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void refreshTokenRejectsExpired() {
        RefreshToken rt = new RefreshToken();
        rt.setToken("expired-token");
        rt.setExpiresAt(Instant.now().minusSeconds(3600));
        rt.setUserId("user1");
        when(refreshTokenRepository.findByToken("expired-token")).thenReturn(Optional.of(rt));

        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("expired-token");

        ResponseEntity<?> response = controller.refreshToken(request);

        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void refreshTokenRejectsUnknown() {
        when(refreshTokenRepository.findByToken("unknown")).thenReturn(Optional.empty());

        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("unknown");

        ResponseEntity<?> response = controller.refreshToken(request);

        assertEquals(401, response.getStatusCode().value());
    }

    // --- Password reset tests ---

    @Test
    void passwordResetRequestAlwaysReturns200() {
        when(userRepository.findByEmail("notfound@test.com")).thenReturn(Optional.empty());

        PasswordResetRequestDTO request = new PasswordResetRequestDTO();
        request.setEmail("notfound@test.com");

        ResponseEntity<?> response = controller.requestPasswordReset(request);

        assertEquals(200, response.getStatusCode().value());
        verify(passwordResetMailService, never()).sendResetEmail(anyString(), anyString(), anyString());
    }

    @Test
    void passwordResetRequestSendsEmailForExistingUser() {
        User user = new User();
        user.setId("user1");
        user.setUsername("testuser");
        user.setEmail("test@test.com");
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));

        PasswordResetRequestDTO request = new PasswordResetRequestDTO();
        request.setEmail("test@test.com");

        ResponseEntity<?> response = controller.requestPasswordReset(request);

        assertEquals(200, response.getStatusCode().value());
        verify(passwordResetMailService).sendResetEmail(eq("test@test.com"), eq("testuser"), anyString());
        verify(passwordResetTokenRepository).save(any(PasswordResetToken.class));
    }

    @Test
    void executePasswordResetSucceeds() {
        PasswordResetToken token = new PasswordResetToken("user1");
        String rawToken = token.getRawToken();
        when(passwordResetTokenRepository.findByTokenHash(token.getTokenHash())).thenReturn(Optional.of(token));

        User user = new User();
        user.setId("user1");
        user.setUsername("testuser");
        when(userRepository.findById("user1")).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newPassword")).thenReturn("encoded");

        PasswordResetExecuteDTO request = new PasswordResetExecuteDTO();
        request.setToken(rawToken);
        request.setNewPassword("newPassword");

        ResponseEntity<?> response = controller.executePasswordReset(request);

        assertEquals(200, response.getStatusCode().value());
        verify(userRepository).save(user);
        assertTrue(token.isUsed());
    }

    @Test
    void executePasswordResetRejectsUsedToken() {
        PasswordResetToken token = new PasswordResetToken("user1");
        String rawToken = token.getRawToken();
        token.setUsed(true);
        when(passwordResetTokenRepository.findByTokenHash(token.getTokenHash())).thenReturn(Optional.of(token));

        PasswordResetExecuteDTO request = new PasswordResetExecuteDTO();
        request.setToken(rawToken);
        request.setNewPassword("newPassword");

        ResponseEntity<?> response = controller.executePasswordReset(request);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void executePasswordResetRejectsExpiredToken() {
        PasswordResetToken token = new PasswordResetToken("user1");
        String rawToken = token.getRawToken();
        token.setUsed(false);
        token.setExpiresAt(Instant.now().minusSeconds(3600));
        when(passwordResetTokenRepository.findByTokenHash(token.getTokenHash())).thenReturn(Optional.of(token));

        PasswordResetExecuteDTO request = new PasswordResetExecuteDTO();
        request.setToken(rawToken);
        request.setNewPassword("newPassword");

        ResponseEntity<?> response = controller.executePasswordReset(request);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void executePasswordResetRejectsUnknownToken() {
        String unknownHash = PasswordResetToken.hashToken("unknown");
        when(passwordResetTokenRepository.findByTokenHash(unknownHash)).thenReturn(Optional.empty());

        PasswordResetExecuteDTO request = new PasswordResetExecuteDTO();
        request.setToken("unknown");
        request.setNewPassword("newPassword");

        ResponseEntity<?> response = controller.executePasswordReset(request);

        assertEquals(400, response.getStatusCode().value());
    }

    // --- Logout tests ---

    @Test
    void logoutBlacklistsToken() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer mock-jwt-token");
        when(jwtUtils.validateJwtToken("mock-jwt-token")).thenReturn(true);
        when(jwtUtils.getJtiFromToken("mock-jwt-token")).thenReturn("jti-123");
        when(jwtUtils.getExpirationFromToken("mock-jwt-token")).thenReturn(Instant.now().plusSeconds(300));
        when(jwtUtils.getUserNameFromJwtToken("mock-jwt-token")).thenReturn("testuser");

        User user = new User();
        user.setId("user1");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        ResponseEntity<?> response = controller.logoutUser(request);

        assertEquals(200, response.getStatusCode().value());
        verify(blacklistedTokenRepository).save(any());
        verify(refreshTokenRepository).deleteByUserId("user1");
    }

    @Test
    void logoutWithoutTokenReturnsBadRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn(null);

        ResponseEntity<?> response = controller.logoutUser(request);

        assertEquals(400, response.getStatusCode().value());
        verify(blacklistedTokenRepository, never()).save(any());
    }

    // --- Email verification tests ---

    @Test
    void verifyEmailSucceeds() {
        EmailVerificationToken token = new EmailVerificationToken("user1");
        String rawToken = token.getRawToken();
        when(emailVerificationTokenRepository.findByTokenHash(token.getTokenHash()))
                .thenReturn(Optional.of(token));

        User user = new User();
        user.setId("user1");
        user.setUsername("testuser");
        user.setEmailVerified(false);
        when(userRepository.findById("user1")).thenReturn(Optional.of(user));

        ResponseEntity<?> response = controller.verifyEmail(rawToken);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(user.isEmailVerified());
        verify(userRepository).save(user);
        verify(emailVerificationTokenRepository).deleteByUserId("user1");
        verify(auditService).log(eq("testuser"), eq(AuditService.Action.EMAIL_VERIFIED));
    }

    @Test
    void verifyEmailRejectsUnknownToken() {
        String unknownHash = PasswordResetToken.hashToken("unknown");
        when(emailVerificationTokenRepository.findByTokenHash(unknownHash))
                .thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.verifyEmail("unknown");

        assertEquals(400, response.getStatusCode().value());
        verify(userRepository, never()).save(any());
    }

    @Test
    void verifyEmailRejectsExpiredToken() {
        EmailVerificationToken token = new EmailVerificationToken("user1");
        String rawToken = token.getRawToken();
        token.setExpiresAt(Instant.now().minusSeconds(3600));
        when(emailVerificationTokenRepository.findByTokenHash(token.getTokenHash()))
                .thenReturn(Optional.of(token));

        ResponseEntity<?> response = controller.verifyEmail(rawToken);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void verifyEmailRejectsMissingUser() {
        EmailVerificationToken token = new EmailVerificationToken("missing");
        String rawToken = token.getRawToken();
        when(emailVerificationTokenRepository.findByTokenHash(token.getTokenHash()))
                .thenReturn(Optional.of(token));
        when(userRepository.findById("missing")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.verifyEmail(rawToken);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void resendVerificationAlwaysReturns200() {
        // Even for non-existent emails, return 200 to prevent enumeration
        when(userRepository.findByEmail("nobody@test.com")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.resendVerification(Map.of("email", "nobody@test.com"));

        assertEquals(200, response.getStatusCode().value());
        verify(emailVerificationService, never()).sendVerificationEmail(anyString(), anyString(), anyString());
    }

    @Test
    void resendVerificationSendsEmailForUnverifiedUser() {
        User user = new User();
        user.setId("user1");
        user.setUsername("testuser");
        user.setEmail("test@test.com");
        user.setEmailVerified(false);
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));

        ResponseEntity<?> response = controller.resendVerification(Map.of("email", "test@test.com"));

        assertEquals(200, response.getStatusCode().value());
        verify(emailVerificationTokenRepository).deleteByUserId("user1");
        verify(emailVerificationTokenRepository).save(any(EmailVerificationToken.class));
        verify(emailVerificationService).sendVerificationEmail(eq("test@test.com"), eq("testuser"), anyString());
    }

    @Test
    void resendVerificationSkipsAlreadyVerifiedUser() {
        User user = new User();
        user.setId("user1");
        user.setUsername("testuser");
        user.setEmail("test@test.com");
        user.setEmailVerified(true);
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));

        ResponseEntity<?> response = controller.resendVerification(Map.of("email", "test@test.com"));

        assertEquals(200, response.getStatusCode().value());
        verify(emailVerificationService, never()).sendVerificationEmail(anyString(), anyString(), anyString());
    }

    @Test
    void signupWithVerificationEnabledSendsEmail() {
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "emailVerificationRequired", true);

        when(userRepository.existsByUsernameIgnoreCase("newuser")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("new@test.com")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("encoded");

        ResponseEntity<?> response = controller.registerUser(signupRequest("newuser", "new@test.com", "password123"));

        assertEquals(200, response.getStatusCode().value());
        verify(emailVerificationTokenRepository).save(any(EmailVerificationToken.class));
        verify(emailVerificationService).sendVerificationEmail(eq("new@test.com"), eq("newuser"), anyString());
        // Should only save once (not set emailVerified=true)
        verify(userRepository, times(1)).save(any(User.class));

        // Reset for other tests
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "emailVerificationRequired", false);
    }

    @Test
    void signinBlocksUnverifiedUserWhenVerificationRequired() {
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "emailVerificationRequired", true);

        when(loginAttemptService.isLockedOut("testuser")).thenReturn(false);
        when(authenticationManager.authenticate(any())).thenReturn(mockAuthentication("testuser"));
        when(jwtUtils.generateJwtToken(any())).thenReturn("mock-jwt-token");

        User user = new User();
        user.setId("user1");
        user.setUsername("testuser");
        user.setEmailVerified(false);
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        ResponseEntity<?> response = controller.authenticateUser(loginRequest("testuser", "password123"));

        assertEquals(403, response.getStatusCode().value());

        org.springframework.test.util.ReflectionTestUtils.setField(controller, "emailVerificationRequired", false);
    }
}
