package com.medichannel.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Field;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class UserAccountLockTests {

    private User createUser(
            String accountStatus,
            boolean emailVerified
    ) throws Exception {

        User user = new User(null, "test@example.com", "password-hash");

        setField(user, "accountStatus", accountStatus);
        setField(user, "emailVerified", emailVerified);
        setField(user, "failedLoginAttempts", 5);
        setField(user, "lockedUntil", Instant.now().minusSeconds(60));

        return user;
    }

    private void setField(
            User user,
            String fieldName,
            Object value
    ) throws Exception {

        Field field = User.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(user, value);
    }

    @Test
    void expiredLockShouldRestoreVerifiedAccountToActive() throws Exception {
        User user = createUser("LOCKED", true);

        user.clearExpiredLock(Instant.now());

        assertEquals("ACTIVE", user.getAccountStatus());
        assertEquals(0, user.getFailedLoginAttempts());
        assertNull(user.getLockedUntil());
    }

    @Test
    void expiredLockShouldRestoreUnverifiedAccountToPending() throws Exception {
        User user = createUser("LOCKED", false);

        user.clearExpiredLock(Instant.now());

        assertEquals("PENDING", user.getAccountStatus());
        assertEquals(0, user.getFailedLoginAttempts());
        assertNull(user.getLockedUntil());
    }

    @Test
    void expiredLockMustNotReactivateSuspendedAccount() throws Exception {
        User user = createUser("SUSPENDED", true);

        user.clearExpiredLock(Instant.now());

        assertEquals("SUSPENDED", user.getAccountStatus());
        assertEquals(0, user.getFailedLoginAttempts());
        assertNull(user.getLockedUntil());
    }

    @Test
    void expiredLockMustNotReactivateDisabledAccount() throws Exception {
        User user = createUser("DISABLED", true);

        user.clearExpiredLock(Instant.now());

        assertEquals("DISABLED", user.getAccountStatus());
        assertEquals(0, user.getFailedLoginAttempts());
        assertNull(user.getLockedUntil());
    }
}
