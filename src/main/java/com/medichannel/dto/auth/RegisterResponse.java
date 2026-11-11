package com.medichannel.dto.auth;

import java.util.UUID;

public record RegisterResponse(

        String message,

        UUID userId,

        String email,

        String accountStatus

) {
}
