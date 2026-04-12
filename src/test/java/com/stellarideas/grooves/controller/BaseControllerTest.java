package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BaseControllerTest {

    private final UserRepository userRepository = mock(UserRepository.class);

    // Concrete subclass for testing
    private final BaseController controller = new BaseController(userRepository) {};

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getCurrentUserThrowsWhenNoAuthentication() {
        SecurityContextHolder.getContext().setAuthentication(null);

        assertThrows(IllegalStateException.class, () -> controller.getCurrentUser());
    }

    @Test
    void getCurrentUserThrowsWhenPrincipalIsNotUserDetails() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("plain-string", null));

        assertThrows(IllegalStateException.class, () -> controller.getCurrentUser());
    }

    @Test
    void getCurrentUserReturnsUserWhenAuthenticated() {
        User expected = new User();
        expected.setUsername("testuser");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(expected));

        var userDetails = org.springframework.security.core.userdetails.User
                .withUsername("testuser")
                .password("irrelevant")
                .authorities(List.of())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));

        User result = controller.getCurrentUser();
        assertEquals("testuser", result.getUsername());
    }

    @Test
    void getCurrentUserThrowsWhenUserNotInDatabase() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        var userDetails = org.springframework.security.core.userdetails.User
                .withUsername("ghost")
                .password("irrelevant")
                .authorities(List.of())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));

        assertThrows(IllegalStateException.class, () -> controller.getCurrentUser());
    }
}
