package com.medichannel.security;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private static final String ISSUER = "medichannel-api";

    private static final long TOKEN_LIFETIME_MINUTES = 15;

    // WebSocket tickets are single-purpose and very short lived. The
    // browser needs a credential it can put in the STOMP CONNECT frame,
    // but the 15 minute access token must never be exposed to scripts.
    private static final long WS_TICKET_LIFETIME_SECONDS = 60;

    public static final String WS_SCOPE_CLAIM = "scope";
    public static final String WS_SCOPE_VALUE = "ws";

    private final JwtEncoder jwtEncoder;

    public JwtService(JwtEncoder jwtEncoder) {
        this.jwtEncoder = jwtEncoder;
    }

    public long getAccessTokenLifetimeSeconds() {
        return TOKEN_LIFETIME_MINUTES * 60;
    }

    public long getWebSocketTicketLifetimeSeconds() {
        return WS_TICKET_LIFETIME_SECONDS;
    }

    public String generateAccessToken(
            UUID userId,
            String email,
            String role
    ) {

        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(
                        TOKEN_LIFETIME_MINUTES,
                        ChronoUnit.MINUTES
                ))
                .subject(userId.toString())
                .claim("email", email)
                .claim("role", role)
                .build();

        return encode(claims);
    }

    /**
     * Mint a 60 second ticket that is only accepted by the WebSocket
     * handshake. The REST resource server rejects it.
     */
    public String generateWebSocketTicket(
            UUID userId,
            String role
    ) {

        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(WS_TICKET_LIFETIME_SECONDS))
                .subject(userId.toString())
                .claim("role", role)
                .claim(WS_SCOPE_CLAIM, WS_SCOPE_VALUE)
                .build();

        return encode(claims);
    }

    private String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader
                .with(MacAlgorithm.HS256)
                .build();

        return jwtEncoder.encode(
                JwtEncoderParameters.from(header, claims)
        ).getTokenValue();
    }
}
