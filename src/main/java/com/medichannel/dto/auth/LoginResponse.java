package com.medichannel.dto.auth;

import java.util.UUID;

public record LoginResponse(

        String accessToken,

        String tokenType,

        long expiresIn,

        UUID userId,

        String email,

        String role

) {
}
