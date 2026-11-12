-- NIC and patient address are stored as AES-256-GCM ciphertext by PatientService.
-- The HMAC NIC lookup hash remains a separate field for uniqueness/search.
ALTER TABLE patients
    ADD COLUMN nic_encrypted TEXT,
    ADD COLUMN address_encrypted TEXT;
