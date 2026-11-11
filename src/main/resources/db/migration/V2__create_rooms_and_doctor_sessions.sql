-- =============================================
-- MEDICHANNEL
-- V2: Rooms and Doctor Consultation Sessions
-- =============================================

-- 1. HOSPITAL ROOMS
CREATE TABLE rooms (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    room_number VARCHAR(30) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    location VARCHAR(255),

    room_type VARCHAR(30) NOT NULL DEFAULT 'CONSULTATION',
    capacity INTEGER NOT NULL DEFAULT 1,

    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_room_type
        CHECK (room_type IN ('CONSULTATION', 'PROCEDURE', 'OTHER')),

    CONSTRAINT chk_room_capacity
        CHECK (capacity > 0)
);

CREATE INDEX idx_rooms_active
    ON rooms(is_active);


-- 2. DOCTOR CONSULTATION SESSIONS
CREATE TABLE doctor_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    doctor_id UUID NOT NULL,
    room_id UUID,

    session_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,

    consultation_type VARCHAR(20) NOT NULL,

    max_appointments INTEGER NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_sessions_doctor
        FOREIGN KEY (doctor_id)
        REFERENCES doctors(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_sessions_room
        FOREIGN KEY (room_id)
        REFERENCES rooms(id)
        ON DELETE RESTRICT,

    CONSTRAINT chk_session_time
        CHECK (end_time > start_time),

    CONSTRAINT chk_consultation_type
        CHECK (consultation_type IN ('PHYSICAL', 'ONLINE')),

    CONSTRAINT chk_session_room
        CHECK (
            (consultation_type = 'PHYSICAL' AND room_id IS NOT NULL)
            OR
            (consultation_type = 'ONLINE' AND room_id IS NULL)
        ),

    CONSTRAINT chk_session_capacity
        CHECK (max_appointments > 0),

    CONSTRAINT chk_session_status
        CHECK (status IN ('SCHEDULED', 'CANCELLED', 'COMPLETED'))
);

CREATE INDEX idx_sessions_doctor_date
    ON doctor_sessions(doctor_id, session_date);

CREATE INDEX idx_sessions_date_status
    ON doctor_sessions(session_date, status);

CREATE INDEX idx_sessions_room_date
    ON doctor_sessions(room_id, session_date);
