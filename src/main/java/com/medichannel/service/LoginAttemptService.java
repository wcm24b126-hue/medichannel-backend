package com.medichannel.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.entity.User;
import com.medichannel.exception.InvalidCredentialsException;
import com.medichannel.repository.UserRepository;

@Service
public class LoginAttemptService {

    private static final int MAXIMUM_ATTEMPTS = 5;
    private static final long LOCK_DURATION_MINUTES = 15;

    private final UserRepository userRepository;

    public LoginAttemptService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public Optional<User> findUserForLogin(String email) {
        String normalizedEmail = normalizeEmail(email);

        Optional<User> optionalUser =
                userRepository.findByEmailForAuthentication(normalizedEmail);

        if (optionalUser.isEmpty()) {
            return Optional.empty();
        }

        User user = optionalUser.get();
        Instant now = Instant.now();

        if (user.getLockedUntil() != null && !user.isLockedAt(now)) {
            user.clearExpiredLock(now);
        }

        return Optional.of(user);
    }

    @Transactional
    public void recordFailedAttempt(String email) {
        String normalizedEmail = normalizeEmail(email);

        userRepository.findByEmailForAuthentication(normalizedEmail)
                .ifPresent(user -> {
                    Instant now = Instant.now();

                    if (user.getLockedUntil() != null
                            && !user.isLockedAt(now)) {
                        user.clearExpiredLock(now);
                    }

                    if (!"ACTIVE".equals(user.getAccountStatus())
                            || !user.isEmailVerified()
                            || user.isLockedAt(now)) {
                        return;
                    }

                    user.recordFailedLoginAttempt(
                            MAXIMUM_ATTEMPTS,
                            now.plus(
                                    LOCK_DURATION_MINUTES,
                                    ChronoUnit.MINUTES
                            )
                    );
                });
    }

    @Transactional
    public User recordSuccessfulAttempt(String email) {
        String normalizedEmail = normalizeEmail(email);

        User user = userRepository
                .findByEmailForAuthentication(normalizedEmail)
                .orElseThrow(InvalidCredentialsException::new);

        Instant now = Instant.now();

        if (!"ACTIVE".equals(user.getAccountStatus())
                || !user.isEmailVerified()
                || user.isLockedAt(now)) {
            throw new InvalidCredentialsException();
        }

        user.recordSuccessfulLogin(now);

        return user;
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
