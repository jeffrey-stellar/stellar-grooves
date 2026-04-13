package com.stellarideas.grooves.security;

import com.stellarideas.grooves.model.Role;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserDetailsServiceImplTest {

    private UserDetailsServiceImpl service;
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        service = new UserDetailsServiceImpl(userRepository);
    }

    @Test
    void loadUserByUsernameReturnsUserDetails() {
        User user = User.builder()
                .id("id1")
                .username("testuser")
                .email("test@test.com")
                .password("encoded-password")
                .roles(Set.of(Role.ROLE_USER))
                .build();
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        UserDetails result = service.loadUserByUsername("testuser");

        assertEquals("testuser", result.getUsername());
        assertEquals("encoded-password", result.getPassword());
        assertTrue(result.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
    }

    @Test
    void loadUserByUsernameThrowsForMissingUser() {
        when(userRepository.findByUsername("nouser")).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class, () ->
                service.loadUserByUsername("nouser"));
    }

    @Test
    void loadUserReflectsAdminRole() {
        User user = User.builder()
                .id("id2")
                .username("admin")
                .email("admin@test.com")
                .password("pass")
                .roles(Set.of(Role.ROLE_ADMIN, Role.ROLE_USER))
                .build();
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));

        UserDetails result = service.loadUserByUsername("admin");

        assertEquals(2, result.getAuthorities().size());
        assertTrue(result.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
    }

    @Test
    void loadUserReflectsLockedAccount() {
        User user = User.builder()
                .id("id3")
                .username("locked")
                .email("locked@test.com")
                .password("pass")
                .roles(Set.of(Role.ROLE_USER))
                .accountLocked(true)
                .build();
        when(userRepository.findByUsername("locked")).thenReturn(Optional.of(user));

        UserDetails result = service.loadUserByUsername("locked");

        assertFalse(result.isAccountNonLocked());
    }

    @Test
    void loadUserReflectsDisabledAccount() {
        User user = User.builder()
                .id("id4")
                .username("disabled")
                .email("disabled@test.com")
                .password("pass")
                .roles(Set.of(Role.ROLE_USER))
                .enabled(false)
                .build();
        when(userRepository.findByUsername("disabled")).thenReturn(Optional.of(user));

        UserDetails result = service.loadUserByUsername("disabled");

        assertFalse(result.isEnabled());
    }
}
