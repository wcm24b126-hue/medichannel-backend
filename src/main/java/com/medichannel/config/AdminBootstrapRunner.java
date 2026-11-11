package com.medichannel.config;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.entity.Role;
import com.medichannel.entity.User;
import com.medichannel.repository.RoleRepository;
import com.medichannel.repository.UserRepository;

/**
 * Optionally provisions the first administrator account.
 *
 * There is deliberately no hard-coded default password. Deployments
 * must provide APP_ADMIN_EMAIL and APP_ADMIN_PASSWORD once. Existing
 * accounts are never modified by this runner.
 */
@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger logger =
            LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private static final int MINIMUM_PASSWORD_LENGTH = 12;
    private static final int MAXIMUM_BCRYPT_PASSWORD_BYTES = 72;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;

    public AdminBootstrapRunner(
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            @Value("${APP_ADMIN_EMAIL:}") String adminEmail,
            @Value("${APP_ADMIN_PASSWORD:}") String adminPassword
    ) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (adminEmail == null || adminEmail.isBlank()) {
            logger.info(
                    "Admin bootstrap skipped: APP_ADMIN_EMAIL is not configured."
            );
            return;
        }

        if (adminPassword == null || adminPassword.isBlank()) {
            logger.warn(
                    "Admin bootstrap skipped: APP_ADMIN_PASSWORD is not configured."
            );
            return;
        }

        int passwordBytes = adminPassword
                .getBytes(StandardCharsets.UTF_8)
                .length;

        if (adminPassword.length() < MINIMUM_PASSWORD_LENGTH
                || passwordBytes > MAXIMUM_BCRYPT_PASSWORD_BYTES) {
            throw new IllegalStateException(
                    "APP_ADMIN_PASSWORD must contain at least 12 characters "
                    + "and must not exceed 72 UTF-8 bytes."
            );
        }

        String normalizedEmail = adminEmail
                .trim()
                .toLowerCase(Locale.ROOT);

        if (userRepository.existsByEmail(normalizedEmail)) {
            logger.info(
                    "Admin bootstrap skipped: the configured account already exists."
            );
            return;
        }

        Role adminRole = roleRepository.findByName("ADMIN")
                .orElseThrow(() -> new IllegalStateException(
                        "The required ADMIN role is not configured."
                ));

        User admin = new User(
                adminRole,
                normalizedEmail,
                passwordEncoder.encode(adminPassword)
        );
        admin.activateByAdministrator();

        userRepository.saveAndFlush(admin);

        logger.info("Admin bootstrap completed for the configured account.");
    }
}
