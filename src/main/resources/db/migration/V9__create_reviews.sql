-- =============================================
-- MEDICHANNEL
-- V9: Patient feedback and doctor reviews
-- Target: PostgreSQL
-- =============================================

-- Reviews are linked to exactly one completed appointment.
-- The unique constraint prevents duplicate reviews even when
-- two requests arrive concurrently.

CREATE TABLE reviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    appointment_id UUID NOT NULL,
    doctor_id UUID NOT NULL,
    patient_id UUID NOT NULL,

    rating INTEGER NOT NULL,
    comment VARCHAR(1000),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_reviews_appointment
        FOREIGN KEY (appointment_id)
        REFERENCES appointments(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_reviews_doctor
        FOREIGN KEY (doctor_id)
        REFERENCES doctors(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_reviews_patient
        FOREIGN KEY (patient_id)
        REFERENCES patients(id)
        ON DELETE RESTRICT,

    CONSTRAINT uq_review_appointment
        UNIQUE (appointment_id),

    CONSTRAINT chk_review_rating
        CHECK (rating BETWEEN 1 AND 5),

    CONSTRAINT chk_review_comment_length
        CHECK (comment IS NULL OR char_length(comment) BETWEEN 1 AND 1000)
);

CREATE INDEX idx_reviews_doctor_created
    ON reviews(doctor_id, created_at DESC);

CREATE INDEX idx_reviews_patient_created
    ON reviews(patient_id, created_at DESC);
