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

    private final JwtEncoder jwtEncoder;

    public JwtService(JwtEncoder jwtEncoder) {
        this.jwtEncoder = jwtEncoder;
    }

    public long getAccessTokenLifetimeSeconds() {
        return TOKEN_LIFETIME_MINUTES * 60;
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

        JwsHeader header = JwsHeader
                .with(MacAlgorithm.HS256)
                .build();

        return jwtEncoder.encode(
                JwtEncoderParameters.from(header, claims)
        ).getTokenValue();
    }
}
