package com.medichannel.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.dto.auth.LoginRequest;
import com.medichannel.dto.auth.LoginResponse;
import com.medichannel.dto.auth.RegisterRequest;
import com.medichannel.dto.auth.RegisterResponse;
import com.medichannel.entity.Role;
import com.medichannel.entity.User;
import com.medichannel.exception.InvalidCredentialsException;
import com.medichannel.repository.RoleRepository;
import com.medichannel.repository.UserRepository;
import com.medichannel.security.JwtService;

@Service
public class AuthService {

    private static final String CUSTOMER_ROLE = "CUSTOMER";
    private static final int MAX_BCRYPT_PASSWORD_BYTES = 72;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttemptService;
    private final boolean autoActivateRegistrations;

    private final String dummyPasswordHash;

    public AuthService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            LoginAttemptService loginAttemptService,
            @Value("${app.registration.auto-activate:true}")
            boolean autoActivateRegistrations
    ) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginAttemptService = loginAttemptService;
        this.autoActivateRegistrations = autoActivateRegistrations;

        // Used to reduce timing differences when an email does not exist.
        this.dummyPasswordHash = passwordEncoder.encode(
                "medichannel-invalid-login-timing-value"
        );
    }

    @Transactional
    public RegisterResponse registerCustomer(RegisterRequest request) {

        if (request == null) {
            throw new IllegalArgumentException(
                    "Registration information is required."
            );
        }

        String normalizedEmail = request.email()
                .trim()
                .toLowerCase(Locale.ROOT);

        if (!request.password().equals(request.confirmPassword())) {
            throw new IllegalArgumentException(
                    "Passwords do not match."
            );
        }

        int passwordBytes = request.password()
                .getBytes(StandardCharsets.UTF_8)
                .length;

        if (passwordBytes > MAX_BCRYPT_PASSWORD_BYTES) {
            throw new IllegalArgumentException(
                    "Password must not exceed 72 bytes when encoded as UTF-8."
            );
        }

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new IllegalArgumentException(
                    "An account with this email already exists."
            );
        }

        Role customerRole = roleRepository.findByName(CUSTOMER_ROLE)
                .orElseThrow(() -> new IllegalStateException(
                        "The required CUSTOMER role is not configured."
                ));

        String passwordHash = passwordEncoder.encode(request.password());

        User user = new User(
                customerRole,
                normalizedEmail,
                passwordHash
        );

        User savedUser = userRepository.saveAndFlush(user);

        if (autoActivateRegistrations) {
            savedUser.activateAfterVerification();
            savedUser = userRepository.saveAndFlush(savedUser);
        }

        String message = autoActivateRegistrations
                ? "Registration successful. You can now sign in."
                : "Registration received. Your account is pending activation.";

        return new RegisterResponse(
                message,
                savedUser.getId(),
                savedUser.getEmail(),
                savedUser.getAccountStatus()
        );
    }

    public LoginResponse login(LoginRequest request) {

        if (request == null) {
            throw new IllegalArgumentException(
                    "Login information is required."
            );
        }

        String normalizedEmail = request.email()
                .trim()
                .toLowerCase(Locale.ROOT);

        String submittedPassword = request.password();

        int passwordBytes = submittedPassword
                .getBytes(StandardCharsets.UTF_8)
                .length;

        if (passwordBytes > MAX_BCRYPT_PASSWORD_BYTES) {
            throw new InvalidCredentialsException();
        }

        Optional<User> optionalUser =
                loginAttemptService.findUserForLogin(normalizedEmail);

        if (optionalUser.isEmpty()) {
            passwordEncoder.matches(
                    submittedPassword,
                    dummyPasswordHash
            );

            throw new InvalidCredentialsException();
        }

        User user = optionalUser.get();

        boolean passwordMatches = passwordEncoder.matches(
                submittedPassword,
                user.getPasswordHash()
        );

        if (!passwordMatches) {
            loginAttemptService.recordFailedAttempt(normalizedEmail);
            throw new InvalidCredentialsException();
        }

        // Rechecks account status and lock state under a database lock.
        User authenticatedUser =
                loginAttemptService.recordSuccessfulAttempt(normalizedEmail);

        String roleName = authenticatedUser.getRole().getName();

        String accessToken = jwtService.generateAccessToken(
                authenticatedUser.getId(),
                authenticatedUser.getEmail(),
                roleName,
                authenticatedUser.getTokenVersion()
        );

        return new LoginResponse(
                accessToken,
                "Bearer",
                jwtService.getAccessTokenLifetimeSeconds(),
                authenticatedUser.getId(),
                authenticatedUser.getEmail(),
                roleName
        );
    }

    @Transactional
    public void logout(UUID userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.incrementTokenVersion();
            userRepository.saveAndFlush(user);
        });
    }
}
