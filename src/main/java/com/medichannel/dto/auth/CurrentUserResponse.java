package com.medichannel.dto.auth;

public record CurrentUserResponse(
        String userId,
        String email,
        String role) {
}
