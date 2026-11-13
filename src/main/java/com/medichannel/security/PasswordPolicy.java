package com.medichannel.security;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Single source of truth for the BR-10 password rule.
 *
 * The assignment requires at least eight characters containing a letter,
 * a number and a symbol. MediChannel keeps its minimum of eight characters and adds the required character-class checks.
 * BCrypt only uses the first 72 bytes, so that limit is enforced too.
 */
public final class PasswordPolicy {

    public static final int MINIMUM_LENGTH = 8;
    public static final int MAXIMUM_BCRYPT_BYTES = 72;

    private static final Pattern HAS_LETTER =
            Pattern.compile("[A-Za-z]");
    private static final Pattern HAS_DIGIT =
            Pattern.compile("[0-9]");
    private static final Pattern HAS_SYMBOL =
            Pattern.compile("[^A-Za-z0-9]");

    private PasswordPolicy() {
    }

    public static void validate(String password) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Password is required.");
        }

        if (password.length() < MINIMUM_LENGTH) {
            throw new IllegalArgumentException(
                    "Password must contain at least "
                            + MINIMUM_LENGTH + " characters."
            );
        }

        if (password.getBytes(StandardCharsets.UTF_8).length
                > MAXIMUM_BCRYPT_BYTES) {
            throw new IllegalArgumentException(
                    "Password must not exceed 72 bytes when encoded as UTF-8."
            );
        }

        if (!HAS_LETTER.matcher(password).find()
                || !HAS_DIGIT.matcher(password).find()
                || !HAS_SYMBOL.matcher(password).find()) {
            throw new IllegalArgumentException(
                    "Password must contain at least one letter, one number "
                            + "and one symbol."
            );
        }
    }
}
