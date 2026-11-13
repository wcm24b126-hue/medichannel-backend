-- V11: Add appointment acceptance status and doctor response tracking
ALTER TABLE appointments
    ADD COLUMN IF NOT EXISTS acceptance_status VARCHAR(20) NOT NULL DEFAULT 'PENDING';

ALTER TABLE appointments
    ADD COLUMN IF NOT EXISTS doctor_accepted_at TIMESTAMP WITH TIME ZONE;
