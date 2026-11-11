package com.medichannel.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Column(name = "email", nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "account_status", nullable = false, length = 20)
    private String accountStatus = "PENDING";

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
    }

    public User(Role role, String email, String passwordHash) {
        this.role = role;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // Record an unsuccessful login attempt.
    public void recordFailedLoginAttempt(
            int maximumAttempts,
            Instant lockUntil
    ) {
        if (maximumAttempts < 1) {
            throw new IllegalArgumentException(
                    "Maximum attempts must be positive."
            );
        }

        failedLoginAttempts++;

        if (failedLoginAttempts >= maximumAttempts) {
            if (lockUntil == null || !lockUntil.isAfter(Instant.now())) {
                throw new IllegalArgumentException(
                        "Lock expiry must be in the future."
                );
            }

            this.lockedUntil = lockUntil;
            this.accountStatus = "LOCKED";
        }
    }

    // Check whether the account is currently locked.
    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    // Clear an expired temporary lock without changing
    // independently managed account restrictions.
    public void clearExpiredLock(Instant now) {
        if (lockedUntil != null && !lockedUntil.isAfter(now)) {
            lockedUntil = null;
            failedLoginAttempts = 0;

            if ("LOCKED".equals(accountStatus)) {
                accountStatus = emailVerified ? "ACTIVE" : "PENDING";
            }
        }
    }

    // Record a successful login and reset failure tracking.
    public void recordSuccessfulLogin(Instant now) {
        if (!"ACTIVE".equals(accountStatus) || !emailVerified) {
            throw new IllegalStateException(
                    "Only active, verified accounts can log in."
            );
        }

        failedLoginAttempts = 0;
        lockedUntil = null;
        lastLoginAt = now;
    }

    // Activate an account after the verification process.
    public void activateAfterVerification() {
        if ("LOCKED".equals(accountStatus)
                || "SUSPENDED".equals(accountStatus)
                || "DISABLED".equals(accountStatus)) {
            throw new IllegalStateException(
                    "This account cannot be activated."
            );
        }

        this.emailVerified = true;
        this.accountStatus = "ACTIVE";
    }

    public UUID getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getAccountStatus() {
        return accountStatus;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
