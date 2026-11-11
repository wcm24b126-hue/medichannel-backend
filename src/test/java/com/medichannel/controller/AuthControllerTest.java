package com.medichannel.controller;

import com.medichannel.dto.auth.CurrentUserResponse;
import com.medichannel.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AuthControllerTest {

    @Test
    void currentUserReturnsOnlyValidatedJwtIdentityClaims() {
        String userId = UUID.randomUUID().toString();
        Jwt jwt = Jwt.withTokenValue("validated-token")
                .header("alg", "HS256")
                .subject(userId)
                .claim("email", "customer@example.com")
                .claim("role", "CUSTOMER")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        AuthController controller = new AuthController(mock(AuthService.class));

        CurrentUserResponse response = controller.currentUser(jwt).getBody();

        assertEquals(userId, response.userId());
        assertEquals("customer@example.com", response.email());
        assertEquals("CUSTOMER", response.role());
    }
}
