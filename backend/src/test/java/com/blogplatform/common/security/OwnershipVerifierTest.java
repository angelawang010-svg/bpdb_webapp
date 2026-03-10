package com.blogplatform.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OwnershipVerifierTest {

    private OwnershipVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new OwnershipVerifier();
    }

    @Test
    void isOwnerOrAdmin_whenOwner_returnsTrue() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "42", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        assertThat(verifier.isOwnerOrAdmin(42L, auth)).isTrue();
    }

    @Test
    void isOwnerOrAdmin_whenAdmin_returnsTrue() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "99", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        assertThat(verifier.isOwnerOrAdmin(42L, auth)).isTrue();
    }

    @Test
    void isOwnerOrAdmin_whenNeitherOwnerNorAdmin_returnsFalse() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "99", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        assertThat(verifier.isOwnerOrAdmin(42L, auth)).isFalse();
    }

    @Test
    void verify_whenNotOwnerOrAdmin_throwsAccessDenied() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "99", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        assertThatThrownBy(() -> verifier.verify(42L, auth))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void isOwnerOrAdmin_nullAuthentication_returnsFalse() {
        assertThat(verifier.isOwnerOrAdmin(42L, null)).isFalse();
    }

    @Test
    void verify_nullAuthentication_throwsAccessDenied() {
        assertThatThrownBy(() -> verifier.verify(42L, null))
                .isInstanceOf(AccessDeniedException.class);
    }
}
