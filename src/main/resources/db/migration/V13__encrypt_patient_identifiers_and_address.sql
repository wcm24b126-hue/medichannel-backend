-- ============================================================================
-- MEDICHANNEL
-- V13: Encrypt the remaining patient identifiers and store the address
-- ============================================================================
-- NIC was previously stored only as an HMAC lookup hash. The brief requires
-- the NIC itself to be recoverable by authorised users and encrypted at rest,
-- so a ciphertext column is added alongside the existing hash.
-- The postal address required by the registration form was not persisted at
-- all; it is added here as ciphertext.
-- Existing rows are back-filled by the application at start-up because the
-- AES key exists only in the environment, never in the database.

ALTER TABLE patients
    ADD COLUMN IF NOT EXISTS nic_encrypted TEXT;

ALTER TABLE patients
    ADD COLUMN IF NOT EXISTS address_encrypted TEXT;
