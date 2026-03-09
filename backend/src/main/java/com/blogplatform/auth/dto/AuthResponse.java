package com.blogplatform.auth.dto;

public record AuthResponse(
        Long accountId,
        String username,
        String email,
        String role,
        boolean isVip,
        boolean emailVerified
) {}
