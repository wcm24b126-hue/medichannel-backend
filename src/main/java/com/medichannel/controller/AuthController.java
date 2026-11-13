package com.medichannel.controller;

import java.util.UUID;

import com.medichannel.dto.auth.CurrentUserResponse;
import com.medichannel.dto.auth.LoginRequest;
import com.medichannel.dto.auth.LoginResponse;
import com.medichannel.dto.auth.RegisterRequest;
import com.medichannel.dto.auth.RegisterResponse;
import com.medichannel.dto.auth.WebSocketTicketResponse;
import com.medichannel.security.JwtService;
import com.medichannel.service.AuthService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;

    public AuthController(
            AuthService authService,
            JwtService jwtService
    ) {
        this.authService = authService;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(authService.registerCustomer(request));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request
    ) {
        return ResponseEntity.ok(authService.login(request));
    }

    @GetMapping("/me")
    public ResponseEntity<CurrentUserResponse> currentUser(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(new CurrentUserResponse(
                jwt.getSubject(),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("role")));
    }

    /**
     * Exchange the authenticated REST session for a 60 second ticket
     * that the browser may use on the STOMP CONNECT frame. The real
     * access token stays inside the HttpOnly cookie.
     */
    @PostMapping("/ws-ticket")
    public ResponseEntity<WebSocketTicketResponse> webSocketTicket(
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        String role = jwt.getClaimAsString("role");

        String ticket = jwtService.generateWebSocketTicket(userId, role);

        return ResponseEntity.ok(new WebSocketTicketResponse(
                ticket,
                jwtService.getWebSocketTicketLifetimeSeconds()
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal Jwt jwt
    ) {
        if (jwt != null && jwt.getSubject() != null) {
            UUID userId = UUID.fromString(jwt.getSubject());
            authService.logout(userId);
        }
        return ResponseEntity.noContent().build();
    }
}
