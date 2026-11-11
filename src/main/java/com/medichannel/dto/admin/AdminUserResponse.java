package com.medichannel.dto.admin;

import java.time.Instant;
import java.util.UUID;

public record AdminUserResponse(

        UUID userId,
        String email,
        String role,
        String accountStatus,
        boolean emailVerified,
        int failedLoginAttempts,
        Instant lockedUntil,
        Instant lastLoginAt,
        Instant createdAt

) {
}
