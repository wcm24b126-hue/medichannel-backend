-- =============================================
-- MEDICHANNEL
-- V6: Consultation summaries
-- Target: PostgreSQL
-- =============================================

-- Consultation notes contain protected health information.
-- Free-text medical fields are stored encrypted by the
-- application (AES-GCM). Dates and relationships stay readable
-- so the database can enforce integrity.

CREATE TABLE consultation_summaries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    appointment_id UUID NOT NULL,
    doctor_id UUID NOT NULL,
    patient_id UUID NOT NULL,

    consultation_date DATE NOT NULL,

    symptoms_encrypted TEXT,
    diagnosis_encrypted TEXT NOT NULL,
    treatment_notes_encrypted TEXT,
    prescription_encrypted TEXT,
    follow_up_instructions_encrypted TEXT,

    follow_up_date DATE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_summaries_appointment
        FOREIGN KEY (appointment_id)
        REFERENCES appointments(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_summaries_doctor
        FOREIGN KEY (doctor_id)
        REFERENCES doctors(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_summaries_patient
        FOREIGN KEY (patient_id)
        REFERENCES patients(id)
        ON DELETE RESTRICT,

    -- One summary per appointment.
    CONSTRAINT uq_summary_appointment
        UNIQUE (appointment_id),

    CONSTRAINT chk_summary_follow_up
        CHECK (
            follow_up_date IS NULL
            OR follow_up_date >= consultation_date
        )
);

CREATE INDEX idx_summaries_doctor_date
    ON consultation_summaries(doctor_id, consultation_date DESC);

CREATE INDEX idx_summaries_patient_date
    ON consultation_summaries(patient_id, consultation_date DESC);
