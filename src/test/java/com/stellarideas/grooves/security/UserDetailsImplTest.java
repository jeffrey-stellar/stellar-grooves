package com.stellarideas.grooves.security;

import com.stellarideas.grooves.model.Role;
import com.stellarideas.grooves.model.User;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UserDetailsImplTest {

    @Test
    void buildFromUserMapsAllFields() {
        User user = User.builder()
                .id("id1")
                .username("testuser")
                .email("test@test.com")
                .password("encoded")
                .roles(Set.of(Role.ROLE_USER))
                .accountLocked(false)
                .enabled(true)
                .build();

        UserDetailsImpl details = UserDetailsImpl.build(user);

        assertEquals("id1", details.getId());
        assertEquals("testuser", details.getUsername());
        assertEquals("test@test.com", details.getEmail());
        assertEquals("encoded", details.getPassword());
        assertTrue(details.isAccountNonLocked());
        assertTrue(details.isEnabled());
        assertTrue(details.isAccountNonExpired());
        assertTrue(details.isCredentialsNonExpired());
        assertEquals(1, details.getAuthorities().size());
    }

    @Test
    void buildReflectsLockedAccount() {
        User user = User.builder()
                .id("id2").username("locked").email("l@t.com").password("p")
                .roles(Set.of(Role.ROLE_USER)).accountLocked(true).build();

        UserDetailsImpl details = UserDetailsImpl.build(user);

        assertFalse(details.isAccountNonLocked());
    }

    @Test
    void buildReflectsDisabledAccount() {
        User user = User.builder()
                .id("id3").username("disabled").email("d@t.com").password("p")
                .roles(Set.of(Role.ROLE_USER)).enabled(false).build();

        UserDetailsImpl details = UserDetailsImpl.build(user);

        assertFalse(details.isEnabled());
    }

    @Test
    void buildMapsMultipleRoles() {
        User user = User.builder()
                .id("id4").username("admin").email("a@t.com").password("p")
                .roles(Set.of(Role.ROLE_USER, Role.ROLE_ADMIN)).build();

        UserDetailsImpl details = UserDetailsImpl.build(user);

        assertEquals(2, details.getAuthorities().size());
    }

    @Test
    void equalityBasedOnId() {
        UserDetailsImpl a = new UserDetailsImpl("same-id", "user1", "a@t.com", "p", false, true, java.util.Collections.emptyList());
        UserDetailsImpl b = new UserDetailsImpl("same-id", "user2", "b@t.com", "q", false, true, java.util.Collections.emptyList());
        UserDetailsImpl c = new UserDetailsImpl("diff-id", "user1", "a@t.com", "p", false, true, java.util.Collections.emptyList());

        assertEquals(a, b);
        assertNotEquals(a, c);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void notEqualToNull() {
        UserDetailsImpl details = new UserDetailsImpl("id", "user", "e@t.com", "p", false, true, java.util.Collections.emptyList());

        assertNotEquals(null, details);
        assertNotEquals("string", details);
    }
}
