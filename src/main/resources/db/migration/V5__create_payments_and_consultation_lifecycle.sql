-- =============================================
-- MEDICHANNEL
-- V5: Payment records and consultation lifecycle
-- Target: PostgreSQL
-- =============================================

-- 1. Consultation lifecycle timestamps.
--
-- The appointment status remains the single source of truth for
-- the lifecycle. These timestamps are audit data only.

ALTER TABLE appointments
    ADD COLUMN arrived_at TIMESTAMPTZ,
    ADD COLUMN consultation_started_at TIMESTAMPTZ,
    ADD COLUMN consultation_completed_at TIMESTAMPTZ;

ALTER TABLE appointments
    ADD CONSTRAINT chk_appointment_consultation_time_order
    CHECK (
        consultation_completed_at IS NULL
        OR (
            consultation_started_at IS NOT NULL
            AND consultation_completed_at >= consultation_started_at
        )
    );


-- 2. Payment records.
--
-- MediChannel records payments. No card numbers, CVV values or
-- other payment credentials are stored. A future gateway
-- integration can populate the gateway reference and status.

CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    appointment_id UUID NOT NULL,

    amount NUMERIC(10, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'LKR',

    method VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',

    payment_reference VARCHAR(64) NOT NULL,

    paid_at TIMESTAMPTZ,
    recorded_by UUID,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_payments_appointment
        FOREIGN KEY (appointment_id)
        REFERENCES appointments(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_payments_recorded_by
        FOREIGN KEY (recorded_by)
        REFERENCES users(id)
        ON DELETE SET NULL,

    CONSTRAINT uq_payments_reference
        UNIQUE (payment_reference),

    CONSTRAINT chk_payment_amount
        CHECK (amount >= 0),

    CONSTRAINT chk_payment_currency
        CHECK (currency = 'LKR'),

    CONSTRAINT chk_payment_method
        CHECK (method IN ('CASH', 'CARD')),

    CONSTRAINT chk_payment_record_status
        CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'REFUNDED')),

    CONSTRAINT chk_payment_paid_at
        CHECK (
            (status = 'COMPLETED' AND paid_at IS NOT NULL)
            OR (status <> 'COMPLETED')
        )
);


-- 3. At most one completed payment per appointment.
--
-- Failed or refunded attempts remain as history and never block
-- a new attempt.

CREATE UNIQUE INDEX uq_completed_payment_per_appointment
    ON payments(appointment_id)
    WHERE status = 'COMPLETED';

CREATE INDEX idx_payments_appointment_created
    ON payments(appointment_id, created_at DESC);

CREATE INDEX idx_payments_status_created
    ON payments(status, created_at DESC);
