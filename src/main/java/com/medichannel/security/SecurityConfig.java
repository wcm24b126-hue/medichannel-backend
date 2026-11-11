package com.medichannel.security;

import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {

        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(auth -> auth

                        // Public health endpoint.
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**"
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
                                "/api/v1/appointments/*/cancel"
                        ).hasRole("CUSTOMER")

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
                                "/api/v1/sessions"
                        ).permitAll()

                        // Doctor session creation: Admin or Manager only.
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/sessions"
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
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${JWT_SECRET}") String encodedSecret
    ) {
        SecretKey secretKey = createSigningKey(encodedSecret);

        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        decoder.setJwtValidator(
                JwtValidators.createDefaultWithIssuer("medichannel-api")
        );

        return decoder;
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
