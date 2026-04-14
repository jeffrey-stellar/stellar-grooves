package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.dto.LoginRequest;
import com.stellarideas.grooves.dto.PasswordResetExecuteDTO;
import com.stellarideas.grooves.dto.PasswordResetRequestDTO;
import com.stellarideas.grooves.dto.RefreshTokenRequest;
import com.stellarideas.grooves.dto.SignupRequest;
import com.stellarideas.grooves.model.BlacklistedToken;
import com.stellarideas.grooves.model.PasswordResetToken;
import com.stellarideas.grooves.model.RefreshToken;
import com.stellarideas.grooves.model.Role;
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
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PasswordEncoder encoder;
    private final JwtUtils jwtUtils;
    private final MessageHelper msg;
    private final LoginAttemptService loginAttemptService;
    private final AuditService auditService;
    private final BlacklistedTokenRepository blacklistedTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordResetMailService passwordResetMailService;

    public AuthController(AuthenticationManager authenticationManager, UserRepository userRepository,
                          PasswordEncoder encoder, JwtUtils jwtUtils, MessageHelper msg,
                          LoginAttemptService loginAttemptService, AuditService auditService,
                          BlacklistedTokenRepository blacklistedTokenRepository,
                          RefreshTokenRepository refreshTokenRepository,
                          PasswordResetTokenRepository passwordResetTokenRepository,
                          PasswordResetMailService passwordResetMailService) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.encoder = encoder;
        this.jwtUtils = jwtUtils;
        this.msg = msg;
        this.loginAttemptService = loginAttemptService;
        this.auditService = auditService;
        this.blacklistedTokenRepository = blacklistedTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordResetMailService = passwordResetMailService;
    }

    @PostMapping("/signin")
    public ResponseEntity<?> authenticateUser(@Valid @RequestBody LoginRequest loginRequest) {
        String username = loginRequest.getUsername();

        if (loginAttemptService.isLockedOut(username)) {
            logger.warn("Login attempt for locked account '{}'", username);
            // Return 401 (not 403) to avoid leaking account lock status
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(GlobalExceptionHandler.problem(HttpStatus.UNAUTHORIZED, msg.msg("auth.bad_credentials")));
        }

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, loginRequest.getPassword()));

            SecurityContextHolder.getContext().setAuthentication(authentication);
            String jwt = jwtUtils.generateJwtToken(authentication);

            loginAttemptService.loginSucceeded(username);
            auditService.log(username, AuditService.Action.LOGIN_SUCCESS);

            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new BadCredentialsException("User not found after authentication"));
            RefreshToken refreshToken = new RefreshToken(
                    user.getId(),
                    jwtUtils.getRefreshTokenExpirationMs());
            refreshTokenRepository.save(refreshToken);

            return ResponseEntity.ok(Map.of(
                    "token", jwt,
                    "refreshToken", refreshToken.getToken(),
                    "username", username));
        } catch (BadCredentialsException e) {
            loginAttemptService.loginFailed(username);
            auditService.log(username, AuditService.Action.LOGIN_FAILED);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(GlobalExceptionHandler.problem(HttpStatus.UNAUTHORIZED, msg.msg("auth.bad_credentials")));
        } catch (LockedException e) {
            auditService.log(username, AuditService.Action.LOGIN_LOCKED);
            // Return 401 (not 403) to avoid leaking account lock status
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(GlobalExceptionHandler.problem(HttpStatus.UNAUTHORIZED, msg.msg("auth.bad_credentials")));
        }
    }

    @PostMapping("/signup")
    public ResponseEntity<?> registerUser(@Valid @RequestBody SignupRequest signUpRequest) {
        String normalizedUsername = signUpRequest.getUsername().toLowerCase(java.util.Locale.ROOT);
        String normalizedEmail = signUpRequest.getEmail().toLowerCase(java.util.Locale.ROOT);
        if (userRepository.existsByUsernameIgnoreCase(normalizedUsername)
                || userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(GlobalExceptionHandler.problem(HttpStatus.CONFLICT,
                            "Unable to complete registration. If you already have an account, try signing in."));
        }

        User user = User.builder()
                .username(normalizedUsername)
                .email(normalizedEmail)
                .password(encoder.encode(signUpRequest.getPassword()))
                .build();

        Set<Role> roles = new HashSet<>();
        roles.add(Role.ROLE_USER);
        user.setRoles(roles);
        userRepository.save(user);

        auditService.log(normalizedUsername, AuditService.Action.SIGNUP);
        return ResponseEntity.ok(Map.of("message", msg.msg("auth.registered")));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        return refreshTokenRepository.findByToken(request.getRefreshToken())
                .filter(rt -> rt.getExpiresAt().isAfter(Instant.now()))
                .map(rt -> {
                    // Look up user to generate new JWT
                    User user = userRepository.findById(rt.getUserId()).orElse(null);
                    if (user == null) {
                        refreshTokenRepository.delete(rt);
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                .body((Object) GlobalExceptionHandler.problem(HttpStatus.UNAUTHORIZED, "Refresh token not found or expired"));
                    }

                    // Generate new JWT with the user's actual roles
                    com.stellarideas.grooves.security.UserDetailsImpl principal =
                            com.stellarideas.grooves.security.UserDetailsImpl.build(user);
                    Authentication authentication = new UsernamePasswordAuthenticationToken(
                            principal, null, principal.getAuthorities());
                    String newJwt = jwtUtils.generateJwtToken(authentication);

                    // Create new refresh token, then delete old one
                    RefreshToken newRefreshToken = new RefreshToken(
                            user.getId(), jwtUtils.getRefreshTokenExpirationMs());
                    refreshTokenRepository.save(newRefreshToken);
                    refreshTokenRepository.delete(rt);

                    auditService.log(user.getUsername(), AuditService.Action.TOKEN_REFRESH);
                    return ResponseEntity.ok((Object) Map.of(
                            "token", newJwt,
                            "refreshToken", newRefreshToken.getToken()));
                })
                .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(GlobalExceptionHandler.problem(HttpStatus.UNAUTHORIZED, "Refresh token not found or expired")));
    }

    @PostMapping("/password-reset/request")
    public ResponseEntity<?> requestPasswordReset(@Valid @RequestBody PasswordResetRequestDTO resetRequest) {
        String email = resetRequest.getEmail().toLowerCase(java.util.Locale.ROOT);
        Optional<User> userOpt = userRepository.findByEmail(email);

        if (userOpt.isPresent()) {
            User user = userOpt.get();
            PasswordResetToken resetToken = new PasswordResetToken(user.getId());
            passwordResetTokenRepository.save(resetToken);

            passwordResetMailService.sendResetEmail(user.getEmail(), user.getUsername(), resetToken.getToken());
            logger.info("Password reset token generated for user '{}'", user.getUsername());
            auditService.log(user.getUsername(), AuditService.Action.PASSWORD_RESET_REQUEST);
        }

        return ResponseEntity.ok(Map.of("message", msg.msg("auth.reset.requested")));
    }

    @PostMapping("/password-reset/execute")
    public ResponseEntity<?> executePasswordReset(@Valid @RequestBody PasswordResetExecuteDTO executeRequest) {
        Optional<PasswordResetToken> tokenOpt = passwordResetTokenRepository.findByToken(executeRequest.getToken());

        if (tokenOpt.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST, msg.msg("auth.reset.invalid")));
        }

        PasswordResetToken resetToken = tokenOpt.get();

        if (resetToken.isUsed() || resetToken.getExpiresAt().isBefore(Instant.now())) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST, msg.msg("auth.reset.invalid")));
        }

        Optional<User> userOpt = userRepository.findById(resetToken.getUserId());
        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST, msg.msg("auth.reset.invalid")));
        }

        User user = userOpt.get();
        user.setPassword(encoder.encode(executeRequest.getNewPassword()));
        userRepository.save(user);

        resetToken.setUsed(true);
        passwordResetTokenRepository.save(resetToken);

        auditService.log(user.getUsername(), AuditService.Action.PASSWORD_RESET_COMPLETE);
        return ResponseEntity.ok(Map.of("message", msg.msg("auth.reset.success")));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logoutUser(HttpServletRequest request) {
        String headerAuth = request.getHeader("Authorization");
        if (!StringUtils.hasText(headerAuth) || !headerAuth.startsWith("Bearer ")) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST, "Authorization token is required for logout"));
        }

        String token = headerAuth.substring(7);
        if (!jwtUtils.validateJwtToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(GlobalExceptionHandler.problem(HttpStatus.UNAUTHORIZED, "Invalid or expired token"));
        }

        String jti = jwtUtils.getJtiFromToken(token);
        Instant expiration = jwtUtils.getExpirationFromToken(token);
        String username = jwtUtils.getUserNameFromJwtToken(token);

        if (jti != null && expiration != null) {
            blacklistedTokenRepository.save(new BlacklistedToken(jti, expiration, username));

            // Delete all refresh tokens for this user
            userRepository.findByUsername(username).ifPresent(
                    user -> refreshTokenRepository.deleteByUserId(user.getId()));

            auditService.log(username, AuditService.Action.LOGOUT);
        }
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }
}
