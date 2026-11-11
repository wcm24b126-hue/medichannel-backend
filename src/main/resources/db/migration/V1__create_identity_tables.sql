-- =============================================
-- MEDICHANNEL
-- V1: Identity and User Management
-- =============================================

-- 1. ROLES
CREATE TABLE roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(30) NOT NULL UNIQUE,
    description VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_role_name
        CHECK (name IN ('ADMIN', 'MANAGER', 'DOCTOR', 'CUSTOMER'))
);

-- 2. USERS
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    role_id UUID NOT NULL,

    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,

    account_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',

    email_verified BOOLEAN NOT NULL DEFAULT FALSE,

    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,

    last_login_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_users_role
        FOREIGN KEY (role_id)
        REFERENCES roles(id),

    CONSTRAINT uq_users_email
        UNIQUE (email),
    CONSTRAINT chk_users_email_normalized
        CHECK (
            LENGTH(BTRIM(email)) > 0
            AND email = LOWER(BTRIM(email))
        ),

    CONSTRAINT chk_account_status
        CHECK (
            account_status IN (
                'PENDING',
                'ACTIVE',
                'LOCKED',
                'SUSPENDED',
                'DISABLED'
            )
        ),

    CONSTRAINT chk_failed_login_attempts
        CHECK (failed_login_attempts >= 0),

    CONSTRAINT chk_locked_until_status
        CHECK (
            locked_until IS NULL
            OR account_status = 'LOCKED'
        )
);

CREATE INDEX idx_users_role_id
    ON users(role_id);

CREATE INDEX idx_users_account_status
    ON users(account_status);

-- 3. MEDICAL SPECIALISATIONS
CREATE TABLE specialisations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    name VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(500),

    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 4. PATIENT PROFILES
CREATE TABLE patients (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    user_id UUID NOT NULL UNIQUE,

    first_name_encrypted TEXT NOT NULL,
    last_name_encrypted TEXT NOT NULL,

    phone_encrypted TEXT,
    nic_lookup_hash VARCHAR(64),

    date_of_birth_encrypted TEXT,
    gender_encrypted TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_patients_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
        ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_patients_nic_lookup_hash
    ON patients(nic_lookup_hash)
    WHERE nic_lookup_hash IS NOT NULL;

-- 5. DOCTOR PROFILES
CREATE TABLE doctors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    user_id UUID NOT NULL UNIQUE,

    specialisation_id UUID NOT NULL,

    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,

    registration_number VARCHAR(100) NOT NULL UNIQUE,
    qualification VARCHAR(255),

    biography TEXT,

    consultation_fee NUMERIC(10, 2),

    is_available BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_doctors_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_doctors_specialisation
        FOREIGN KEY (specialisation_id)
        REFERENCES specialisations(id),

    CONSTRAINT chk_consultation_fee
        CHECK (
            consultation_fee IS NULL
            OR consultation_fee >= 0
        )
);

CREATE INDEX idx_doctors_specialisation_id
    ON doctors(specialisation_id);

-- =============================================
-- INITIAL SYSTEM ROLES
-- =============================================

INSERT INTO roles (name, description)
VALUES
    ('ADMIN', 'System administrator'),
    ('MANAGER', 'Hospital management staff'),
    ('DOCTOR', 'Medical doctor'),
    ('CUSTOMER', 'Registered patient');
