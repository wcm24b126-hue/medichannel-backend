-- =============================================
-- MEDICHANNEL
-- V3: Complete Session Scheduling Requirements
-- =============================================

-- 1. Add required session fields

ALTER TABLE doctor_sessions
    ADD COLUMN minutes_per_patient INTEGER NOT NULL DEFAULT 10,
    ADD COLUMN fee_lkr NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    ADD COLUMN delay_minutes INTEGER NOT NULL DEFAULT 0;


-- 2. Update allowed session statuses

ALTER TABLE doctor_sessions
    DROP CONSTRAINT chk_session_status;

ALTER TABLE doctor_sessions
    ADD CONSTRAINT chk_session_status
    CHECK (
        status IN (
            'SCHEDULED',
            'OPEN',
            'IN_PROGRESS',
            'COMPLETED',
            'CANCELLED'
        )
    );


-- 3. Validate business rule limits

ALTER TABLE doctor_sessions
    ADD CONSTRAINT chk_session_max_appointments
    CHECK (max_appointments BETWEEN 1 AND 60);

ALTER TABLE doctor_sessions
    ADD CONSTRAINT chk_session_minutes_per_patient
    CHECK (minutes_per_patient BETWEEN 5 AND 30);

ALTER TABLE doctor_sessions
    ADD CONSTRAINT chk_session_fee
    CHECK (fee_lkr >= 0);

ALTER TABLE doctor_sessions
    ADD CONSTRAINT chk_session_delay
    CHECK (delay_minutes >= 0);


-- 4. Create a trigger function to prevent overlapping sessions.
-- Advisory transaction locks serialize competing scheduling requests.
-- No additional PostgreSQL extension is required.

CREATE OR REPLACE FUNCTION prevent_session_overlap()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status <> 'CANCELLED' THEN

        -- Lock scheduling for this doctor.
        PERFORM pg_advisory_xact_lock(
            hashtextextended(
                'medichannel:doctor:' || NEW.doctor_id::text,
                0
            )
        );

        -- Reject overlapping sessions for the same doctor.
        IF EXISTS (
            SELECT 1
            FROM doctor_sessions existing
            WHERE existing.doctor_id = NEW.doctor_id
              AND existing.id IS DISTINCT FROM NEW.id
              AND existing.status <> 'CANCELLED'
              AND (existing.session_date + existing.start_time)
                    < (NEW.session_date + NEW.end_time)
              AND (existing.session_date + existing.end_time)
                    > (NEW.session_date + NEW.start_time)
        ) THEN
            RAISE EXCEPTION
                'Doctor already has an overlapping session'
                USING ERRCODE = '23P01';
        END IF;

        -- If a physical room is assigned, lock scheduling for that room.
        IF NEW.room_id IS NOT NULL THEN

            PERFORM pg_advisory_xact_lock(
                hashtextextended(
                    'medichannel:room:' || NEW.room_id::text,
                    0
                )
            );

            -- Reject overlapping sessions in the same room.
            IF EXISTS (
                SELECT 1
                FROM doctor_sessions existing
                WHERE existing.room_id = NEW.room_id
                  AND existing.id IS DISTINCT FROM NEW.id
                  AND existing.status <> 'CANCELLED'
                  AND (existing.session_date + existing.start_time)
                        < (NEW.session_date + NEW.end_time)
                  AND (existing.session_date + existing.end_time)
                        > (NEW.session_date + NEW.start_time)
            ) THEN
                RAISE EXCEPTION
                    'Room is already assigned to an overlapping session'
                    USING ERRCODE = '23P01';
            END IF;

        END IF;
    END IF;

    RETURN NEW;
END;
$$;


-- 5. Apply the overlap protection to inserts and updates.

CREATE TRIGGER trg_prevent_session_overlap
BEFORE INSERT OR UPDATE
ON doctor_sessions
FOR EACH ROW
EXECUTE FUNCTION prevent_session_overlap();


-- 6. Additional indexes for session searches

CREATE INDEX idx_sessions_doctor_date_status
    ON doctor_sessions(doctor_id, session_date, status);

CREATE INDEX idx_sessions_room_date_status
    ON doctor_sessions(room_id, session_date, status);

