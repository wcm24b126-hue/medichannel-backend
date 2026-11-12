package com.medichannel.security;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.medichannel.exception.ApiErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper
    ) throws Exception {

        return http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(
                                        request,
                                        response,
                                        objectMapper,
                                        HttpServletResponse.SC_UNAUTHORIZED,
                                        "UNAUTHORIZED",
                                        "Authentication is required."
                                )
                        )
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(
                                        request,
                                        response,
                                        objectMapper,
                                        HttpServletResponse.SC_FORBIDDEN,
                                        "FORBIDDEN",
                                        "You do not have permission to perform this action."
                                )
                        )
                )
                .authorizeHttpRequests(auth -> auth

                        // Public health endpoint.
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**"
                        ).permitAll()

                        // WebSocket handshake. Browsers cannot send an
                        // Authorization header while opening a WebSocket, so
                        // the JWT is verified on the STOMP CONNECT frame by
                        // WebSocketJwtInterceptor. Destinations are denied by
                        // default in WebSocketAuthorizationInterceptor.
                        .requestMatchers(
                                HttpMethod.GET,
                                "/ws",
                                "/ws/**"
                        ).permitAll()

                        // Public authentication endpoints.
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/auth/register",
                                "/api/v1/auth/login"
                        ).permitAll()

                        // Customer's own patient profile.
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/patients/me"
                        ).hasRole("CUSTOMER")
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/patients/me"
                        ).hasRole("CUSTOMER")
                        .requestMatchers(
                                HttpMethod.PUT,
                                "/api/v1/patients/me"
                        ).hasRole("CUSTOMER")

                        // Appointment operations: CUSTOMER only.
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/appointments"
                        ).hasRole("CUSTOMER")
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/appointments/me",
                                "/api/v1/appointments/*"
                        ).hasRole("CUSTOMER")
                        .requestMatchers(
                                HttpMethod.PATCH,
                                "/api/v1/appointments/*/cancel",
                                "/api/v1/appointments/*/check-in"
                        ).hasRole("CUSTOMER")

                        // Appointment payments. Patients record their own
                        // payment; staff may also record payments.
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/appointments/*/payments"
                        ).hasAnyRole("CUSTOMER", "ADMIN", "MANAGER")
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/appointments/*/payments",
                                "/api/v1/appointments/*/summary",
                                "/api/v1/appointments/*/chat/messages"
                        ).authenticated()

                        // Consultation summaries are written by the doctor.
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/appointments/*/summary"
                        ).hasRole("DOCTOR")
                        .requestMatchers(
                                HttpMethod.PUT,
                                "/api/v1/appointments/*/summary"
                        ).hasRole("DOCTOR")

                        // Patient feedback.
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/appointments/*/review"
                        ).hasRole("CUSTOMER")
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/reviews/me"
                        ).hasRole("CUSTOMER")

                        // Patient payment history.
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/payments/me"
                        ).hasRole("CUSTOMER")

                        // Every user only ever sees their own notifications.
                        .requestMatchers(
                                "/api/v1/notifications",
                                "/api/v1/notifications/**"
                        ).authenticated()

                        // Doctor consultation portal.
                        .requestMatchers(
                                "/api/v1/doctor",
                                "/api/v1/doctor/**"
                        ).hasRole("DOCTOR")

                        // Administration.
                        .requestMatchers(
                                "/api/v1/admin",
                                "/api/v1/admin/**"
                        ).hasRole("ADMIN")

                        // Public doctor directory and details.
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/doctors",
                                "/api/v1/doctors/**"
                        ).permitAll()

                        // Public active specialisations.
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/specialisations",
                                "/api/v1/specialisations/**"
                        ).permitAll()

                        // Doctor management: Admin or Manager only.
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/doctors"
                        ).hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(
                                HttpMethod.PUT,
                                "/api/v1/doctors/**"
                        ).hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(
                                HttpMethod.PATCH,
                                "/api/v1/doctors/**"
                        ).hasAnyRole("ADMIN", "MANAGER")

                        // Specialisation management: Admin or Manager only.
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/specialisations"
                        ).hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(
                                HttpMethod.PUT,
                                "/api/v1/specialisations/**"
                        ).hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(
                                HttpMethod.PATCH,
                                "/api/v1/specialisations/**"
                        ).hasAnyRole("ADMIN", "MANAGER")

                        // Public session discovery.
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/sessions",
                                "/api/v1/sessions/*/queue"
                        ).permitAll()

                        // Doctor session creation: Admin or Manager only.
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/sessions"
                        ).hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(
                                HttpMethod.PUT,
                                "/api/v1/sessions/*"
                        ).hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(
                                HttpMethod.PATCH,
                                "/api/v1/sessions/*/open",
                                "/api/v1/sessions/*/start",
                                "/api/v1/sessions/*/complete",
                                "/api/v1/sessions/*/cancel",
                                "/api/v1/sessions/*/delay"
                        ).hasAnyRole("ADMIN", "MANAGER")

                        // Room management: Admin or Manager only.
                        .requestMatchers(
                                "/api/v1/rooms",
                                "/api/v1/rooms/**"
                        ).hasAnyRole("ADMIN", "MANAGER")

                        // Everything else requires authentication.
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(
                                        jwtAuthenticationConverter()
                                )
                        )
                )
                .build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {

        JwtGrantedAuthoritiesConverter authoritiesConverter =
                new JwtGrantedAuthoritiesConverter();

        authoritiesConverter.setAuthoritiesClaimName("role");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter authenticationConverter =
                new JwtAuthenticationConverter();

        authenticationConverter.setJwtGrantedAuthoritiesConverter(
                authoritiesConverter
        );

        return authenticationConverter;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${APP_FRONTEND_ORIGIN:http://localhost:3000}")
            String allowedOrigins
    ) {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();

        if (origins.isEmpty() || origins.contains("*")) {
            throw new IllegalStateException(
                    "APP_FRONTEND_ORIGIN must list explicit origins, not '*'."
            );
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of(
                "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
        ));
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept"
        ));
        configuration.setExposedHeaders(List.of("X-Request-ID"));
        // The API uses Bearer tokens, not cookies.
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * Decoder used by the REST resource server. WebSocket tickets are
     * rejected here so a ticket can never be replayed against the API.
     */
    @Bean
    @Primary
    public JwtDecoder jwtDecoder(
            @Value("${JWT_SECRET}") String encodedSecret
    ) {
        NimbusJwtDecoder decoder = buildDecoder(encodedSecret);

        OAuth2TokenValidator<Jwt> rejectWebSocketTickets =
                token -> JwtService.WS_SCOPE_VALUE.equals(
                        token.getClaimAsString(JwtService.WS_SCOPE_CLAIM)
                )
                        ? OAuth2TokenValidatorResult.failure(
                                new OAuth2Error(
                                        "invalid_token",
                                        "WebSocket tickets cannot be used "
                                                + "as access tokens.",
                                        null
                                )
                        )
                        : OAuth2TokenValidatorResult.success();

        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(
                                "medichannel-api"
                        ),
                        rejectWebSocketTickets
                )
        );

        return decoder;
    }

    /**
     * Decoder used only by the STOMP handshake interceptor.
     */
    @Bean
    public JwtDecoder webSocketJwtDecoder(
            @Value("${JWT_SECRET}") String encodedSecret
    ) {
        NimbusJwtDecoder decoder = buildDecoder(encodedSecret);

        OAuth2TokenValidator<Jwt> requireWebSocketScope =
                token -> JwtService.WS_SCOPE_VALUE.equals(
                        token.getClaimAsString(JwtService.WS_SCOPE_CLAIM)
                )
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(
                                new OAuth2Error(
                                        "invalid_token",
                                        "A WebSocket ticket is required.",
                                        null
                                )
                        );

        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(
                                "medichannel-api"
                        ),
                        requireWebSocketScope
                )
        );

        return decoder;
    }

    private NimbusJwtDecoder buildDecoder(String encodedSecret) {
        SecretKey secretKey = createSigningKey(encodedSecret);

        return NimbusJwtDecoder
                .withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    public JwtEncoder jwtEncoder(
            @Value("${JWT_SECRET}") String encodedSecret
    ) {
        SecretKey secretKey = createSigningKey(encodedSecret);

        return new NimbusJwtEncoder(
                new ImmutableSecret<>(secretKey)
        );
    }

    private void writeError(
            HttpServletRequest request,
            HttpServletResponse response,
            ObjectMapper objectMapper,
            int status,
            String errorCode,
            String message
    ) throws IOException {
        Object requestId = request.getAttribute(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE
        );

        String referenceId = requestId instanceof String value
                ? value
                : UUID.randomUUID().toString();

        ApiErrorResponse body = new ApiErrorResponse(
                false,
                errorCode,
                message,
                referenceId,
                Instant.now()
        );

        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    private SecretKey createSigningKey(String encodedSecret) {

        try {
            byte[] keyBytes = Base64.getDecoder().decode(encodedSecret);

            if (keyBytes.length < 32) {
                throw new IllegalStateException(
                        "JWT signing key must contain at least 32 bytes."
                );
            }

            return new SecretKeySpec(keyBytes, "HmacSHA256");

        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "JWT_SECRET must be a valid Base64-encoded key.",
                    exception
            );
        }
    }
}
