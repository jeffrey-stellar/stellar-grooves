package com.stellarideas.grooves.config;

import com.stellarideas.grooves.model.Role;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private Environment env;

    private AdminBootstrap adminBootstrap;

    @BeforeEach
    void setUp() {
        adminBootstrap = new AdminBootstrap(userRepository, passwordEncoder, env);
    }

    @Test
    void doesNothingWhenAdminAlreadyExists() throws Exception {
        when(userRepository.existsByRolesContaining(Role.ROLE_ADMIN)).thenReturn(true);

        adminBootstrap.run();

        verify(userRepository, never()).save(any());
        verify(env, never()).getProperty("ADMIN_PASSWORD");
    }

    @Test
    void doesNothingWhenNoAdminPasswordConfigured() throws Exception {
        when(userRepository.existsByRolesContaining(Role.ROLE_ADMIN)).thenReturn(false);
        when(env.getProperty("ADMIN_PASSWORD")).thenReturn(null);

        adminBootstrap.run();

        verify(userRepository, never()).save(any());
    }

    @Test
    void doesNothingWhenAdminPasswordIsBlank() throws Exception {
        when(userRepository.existsByRolesContaining(Role.ROLE_ADMIN)).thenReturn(false);
        when(env.getProperty("ADMIN_PASSWORD")).thenReturn("   ");

        adminBootstrap.run();

        verify(userRepository, never()).save(any());
    }

    @Test
    void createsAdminUserWithCorrectRolesWhenPasswordIsSet() throws Exception {
        when(userRepository.existsByRolesContaining(Role.ROLE_ADMIN)).thenReturn(false);
        when(env.getProperty("ADMIN_PASSWORD")).thenReturn("secret123");
        when(env.getProperty("ADMIN_USERNAME", "admin")).thenReturn("admin");
        when(env.getProperty("ADMIN_EMAIL", "admin@stellargrooves.local")).thenReturn("admin@stellargrooves.local");
        when(userRepository.existsByUsername("admin")).thenReturn(false);
        when(passwordEncoder.encode("secret123")).thenReturn("encoded-secret");

        adminBootstrap.run();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());

        User saved = captor.getValue();
        assertEquals("admin", saved.getUsername());
        assertEquals("admin@stellargrooves.local", saved.getEmail());
        assertEquals("encoded-secret", saved.getPassword());
        assertTrue(saved.getRoles().contains(Role.ROLE_ADMIN));
        assertTrue(saved.getRoles().contains(Role.ROLE_USER));
    }

    @Test
    void promotesExistingNonAdminUserWhenUsernameAlreadyTaken() throws Exception {
        when(userRepository.existsByRolesContaining(Role.ROLE_ADMIN)).thenReturn(false);
        when(env.getProperty("ADMIN_PASSWORD")).thenReturn("secret123");
        when(env.getProperty("ADMIN_USERNAME", "admin")).thenReturn("admin");
        when(env.getProperty("ADMIN_EMAIL", "admin@stellargrooves.local")).thenReturn("admin@stellargrooves.local");
        when(userRepository.existsByUsername("admin")).thenReturn(true);

        User existingUser = new User();
        existingUser.setUsername("admin");
        existingUser.setRoles(new HashSet<>(Set.of(Role.ROLE_USER)));
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(existingUser));

        adminBootstrap.run();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());

        User saved = captor.getValue();
        assertEquals("admin", saved.getUsername());
        assertTrue(saved.getRoles().contains(Role.ROLE_ADMIN));
        assertTrue(saved.getRoles().contains(Role.ROLE_USER));
    }
}
