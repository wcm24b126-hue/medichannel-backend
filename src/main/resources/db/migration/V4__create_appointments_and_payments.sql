-- =============================================
-- MEDICHANNEL
-- V4: Appointment Booking and Payments
-- Target: PostgreSQL
-- =============================================

-- 1. Add a permanent appointment-number counter.

ALTER TABLE doctor_sessions
    ADD COLUMN last_appointment_number INTEGER NOT NULL DEFAULT 0;

ALTER TABLE doctor_sessions
    ADD CONSTRAINT chk_session_appointment_counter
    CHECK (
        last_appointment_number >= 0
        AND last_appointment_number <= max_appointments
    );


-- 2. Appointment numbers are allocated automatically by
-- the BEFORE INSERT trigger defined below.
--
-- The database owns allocation. The application must NOT
-- supply appointment_number when inserting an appointment.
-- The trigger locks and increments the session counter
-- atomically. Failed inserts roll back the increment.

-- 3. Create appointments.

CREATE TABLE appointments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    session_id UUID NOT NULL,
    patient_id UUID NOT NULL,

    appointment_number INTEGER NOT NULL,

    booking_reference UUID NOT NULL DEFAULT gen_random_uuid(),

    reason_encrypted TEXT NOT NULL,

    status VARCHAR(30) NOT NULL DEFAULT 'BOOKED',

    payment_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    payment_method VARCHAR(20),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelled_at TIMESTAMPTZ,

    CONSTRAINT fk_appointments_session
        FOREIGN KEY (session_id)
        REFERENCES doctor_sessions(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_appointments_patient
        FOREIGN KEY (patient_id)
        REFERENCES patients(id)
        ON DELETE RESTRICT,

    CONSTRAINT uq_appointment_session_number
        UNIQUE (session_id, appointment_number),

    CONSTRAINT uq_appointment_booking_reference
        UNIQUE (booking_reference),

    CONSTRAINT chk_appointment_number
        CHECK (appointment_number BETWEEN 1 AND 60),

    CONSTRAINT chk_appointment_status
        CHECK (
            status IN (
                'BOOKED',
                'ARRIVED',
                'IN_CONSULTATION',
                'COMPLETED',
                'NO_SHOW',
                'CANCELLED'
            )
        ),

    CONSTRAINT chk_appointment_payment_status
        CHECK (payment_status IN ('PENDING', 'PAID')),

    CONSTRAINT chk_appointment_payment_method
        CHECK (
            (
                payment_status = 'PENDING'
                AND payment_method IS NULL
            )
            OR
            (
                payment_status = 'PAID'
                AND payment_method IS NOT NULL
                AND payment_method IN ('CASH', 'CARD')
            )
        ),

    CONSTRAINT chk_appointment_cancelled_at
        CHECK (
            (status = 'CANCELLED' AND cancelled_at IS NOT NULL)
            OR
            (status <> 'CANCELLED' AND cancelled_at IS NULL)
        )
);


-- 4. Prevent multiple active appointments for one patient
-- in the same consultation session.
--
-- Historical appointments remain stored. Cancelled,
-- completed and no-show appointments do not block
-- a future booking in the same session.

CREATE UNIQUE INDEX uq_active_appointment_patient_session
    ON appointments(session_id, patient_id)
    WHERE status IN (
        'BOOKED',
        'ARRIVED',
        'IN_CONSULTATION'
    );


-- 5. Indexes for common appointment queries.

CREATE INDEX idx_appointments_patient_created
    ON appointments(patient_id, created_at DESC);

CREATE INDEX idx_appointments_session_status_number
    ON appointments(session_id, status, appointment_number);

CREATE INDEX idx_appointments_payment_status
    ON appointments(payment_status, created_at);


-- 6. Automatically allocate appointment numbers.
--
-- This trigger is the single authority for appointment-number
-- allocation. It increments the session counter atomically.
--
-- Concurrent inserts for the same session are serialized by
-- PostgreSQL's row-level UPDATE lock.
--
-- If the appointment insert fails later, PostgreSQL rolls back
-- the counter increment as part of the same transaction.

CREATE OR REPLACE FUNCTION assign_appointment_number()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    allocated_number INTEGER;
BEGIN
    UPDATE doctor_sessions
    SET last_appointment_number = last_appointment_number + 1
    WHERE id = NEW.session_id
      AND status = 'OPEN'
      AND last_appointment_number < max_appointments
    RETURNING last_appointment_number
    INTO allocated_number;

    IF NOT FOUND THEN
        RAISE EXCEPTION
            'Session does not exist, is not open, or is fully booked'
            USING ERRCODE = '23514';
    END IF;

    NEW.appointment_number := allocated_number;

    RETURN NEW;
END;
$$;


CREATE TRIGGER trg_assign_appointment_number
BEFORE INSERT
ON appointments
FOR EACH ROW
EXECUTE FUNCTION assign_appointment_number();


-- =============================================
-- END OF V4
-- =============================================

-- 7. Protect appointment history and identity.

CREATE OR REPLACE FUNCTION protect_appointment_history()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            'Appointments cannot be permanently deleted'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.session_id IS DISTINCT FROM NEW.session_id
       OR OLD.patient_id IS DISTINCT FROM NEW.patient_id
       OR OLD.appointment_number IS DISTINCT FROM NEW.appointment_number
       OR OLD.booking_reference IS DISTINCT FROM NEW.booking_reference
    THEN
        RAISE EXCEPTION
            'Appointment identity fields cannot be changed'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;


CREATE TRIGGER trg_protect_appointment_history
BEFORE UPDATE OR DELETE
ON appointments
FOR EACH ROW
EXECUTE FUNCTION protect_appointment_history();
