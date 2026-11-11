\set ON_ERROR_STOP on

-- ============================================================
-- MEDICHANNEL V4 - LOCAL POSTGRESQL APPOINTMENT VERIFICATION
-- TEST DATABASE ONLY: medichannel_test
-- ============================================================

BEGIN;

-- 1. Create test users and related records.

INSERT INTO users (
    id, role_id, email, password_hash, account_status, email_verified
)
SELECT
    '10000000-0000-4000-8000-000000000001',
    id,
    'test.v4.doctor@medichannel.local',
    'LOCAL_TEST_HASH',
    'ACTIVE',
    TRUE
FROM roles WHERE name = 'DOCTOR';

INSERT INTO users (
    id, role_id, email, password_hash, account_status, email_verified
)
SELECT
    '10000000-0000-4000-8000-000000000002',
    id,
    'test.v4.patient1@medichannel.local',
    'LOCAL_TEST_HASH',
    'ACTIVE',
    TRUE
FROM roles WHERE name = 'CUSTOMER';

INSERT INTO users (
    id, role_id, email, password_hash, account_status, email_verified
)
SELECT
    '10000000-0000-4000-8000-000000000003',
    id,
    'test.v4.patient2@medichannel.local',
    'LOCAL_TEST_HASH',
    'ACTIVE',
    TRUE
FROM roles WHERE name = 'CUSTOMER';

INSERT INTO users (
    id, role_id, email, password_hash, account_status, email_verified
)
SELECT
    '10000000-0000-4000-8000-000000000004',
    id,
    'test.v4.patient3@medichannel.local',
    'LOCAL_TEST_HASH',
    'ACTIVE',
    TRUE
FROM roles WHERE name = 'CUSTOMER';

INSERT INTO users (
    id, role_id, email, password_hash, account_status, email_verified
)
SELECT
    '10000000-0000-4000-8000-000000000005',
    id,
    'test.v4.patient4@medichannel.local',
    'LOCAL_TEST_HASH',
    'ACTIVE',
    TRUE
FROM roles WHERE name = 'CUSTOMER';

INSERT INTO specialisations (id, name, description)
VALUES (
    '20000000-0000-4000-8000-000000000001',
    'Local Test Specialisation',
    'Temporary local database verification'
);

INSERT INTO doctors (
    id, user_id, specialisation_id, first_name, last_name,
    registration_number, qualification
)
VALUES (
    '30000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000001',
    'Test',
    'Doctor',
    'LOCAL-TEST-DOCTOR-001',
    'Test qualification'
);

INSERT INTO rooms (
    id, room_number, name, location, room_type, capacity
)
VALUES (
    '40000000-0000-4000-8000-000000000001',
    'LOCAL-TEST-01',
    'Local Test Room',
    'Local Test Hospital',
    'CONSULTATION',
    1
);

INSERT INTO patients (
    id, user_id, first_name_encrypted, last_name_encrypted
)
VALUES
(
    '50000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000002',
    'LOCAL_TEST_CIPHERTEXT_1',
    'LOCAL_TEST_CIPHERTEXT_1'
),
(
    '50000000-0000-4000-8000-000000000002',
    '10000000-0000-4000-8000-000000000003',
    'LOCAL_TEST_CIPHERTEXT_2',
    'LOCAL_TEST_CIPHERTEXT_2'
),
(
    '50000000-0000-4000-8000-000000000003',
    '10000000-0000-4000-8000-000000000004',
    'LOCAL_TEST_CIPHERTEXT_3',
    'LOCAL_TEST_CIPHERTEXT_3'
),
(
    '50000000-0000-4000-8000-000000000004',
    '10000000-0000-4000-8000-000000000005',
    'LOCAL_TEST_CIPHERTEXT_4',
    'LOCAL_TEST_CIPHERTEXT_4'
);

-- 2. Create an OPEN physical session with capacity four.

INSERT INTO doctor_sessions (
    id, doctor_id, room_id, session_date,
    start_time, end_time, consultation_type,
    max_appointments, status,
    minutes_per_patient, fee_lkr, delay_minutes
)
VALUES (
    '60000000-0000-4000-8000-000000000001',
    '30000000-0000-4000-8000-000000000001',
    '40000000-0000-4000-8000-000000000001',
    DATE '2027-01-15',
    TIME '09:00',
    TIME '13:00',
    'PHYSICAL',
    4,
    'OPEN',
    10,
    1500.00,
    0
);

-- 3. First booking must receive number 1.

INSERT INTO appointments (
    id, session_id, patient_id, reason_encrypted
)
VALUES (
    '70000000-0000-4000-8000-000000000001',
    '60000000-0000-4000-8000-000000000001',
    '50000000-0000-4000-8000-000000000001',
    'LOCAL_TEST_ENCRYPTED_REASON_1'
);

DO $$
BEGIN
    IF (
        SELECT appointment_number FROM appointments
        WHERE id = '70000000-0000-4000-8000-000000000001'
    ) <> 1 THEN
        RAISE EXCEPTION 'TEST FAILED: first appointment number is not 1';
    END IF;
    RAISE NOTICE 'PASS: first appointment receives number 1';
END $$;

-- 4. A duplicate active booking must fail.
-- Its failed insert must not consume the next appointment number.

DO $$
BEGIN
    BEGIN
        INSERT INTO appointments (
            id, session_id, patient_id, reason_encrypted
        )
        VALUES (
            '70000000-0000-4000-8000-000000000002',
            '60000000-0000-4000-8000-000000000001',
            '50000000-0000-4000-8000-000000000001',
            'LOCAL_TEST_DUPLICATE_REASON'
        );

        RAISE EXCEPTION 'TEST FAILED: duplicate active booking was accepted';
    EXCEPTION
        WHEN unique_violation THEN
            RAISE NOTICE 'PASS: duplicate active booking rejected';
    END;
END $$;

DO $$
BEGIN
    IF (
        SELECT last_appointment_number FROM doctor_sessions
        WHERE id = '60000000-0000-4000-8000-000000000001'
    ) <> 1 THEN
        RAISE EXCEPTION 'TEST FAILED: failed insert consumed a number';
    END IF;
    RAISE NOTICE 'PASS: failed duplicate insert did not consume a number';
END $$;

-- 5. Second patient receives number 2.

INSERT INTO appointments (
    id, session_id, patient_id, reason_encrypted
)
VALUES (
    '70000000-0000-4000-8000-000000000003',
    '60000000-0000-4000-8000-000000000001',
    '50000000-0000-4000-8000-000000000002',
    'LOCAL_TEST_ENCRYPTED_REASON_2'
);

DO $$
BEGIN
    IF (
        SELECT appointment_number FROM appointments
        WHERE id = '70000000-0000-4000-8000-000000000003'
    ) <> 2 THEN
        RAISE EXCEPTION 'TEST FAILED: second appointment number is not 2';
    END IF;
    RAISE NOTICE 'PASS: second appointment receives number 2';
END $$;

-- 6. Cancel the first appointment. Keep the historical record.

UPDATE appointments
SET status = 'CANCELLED',
    cancelled_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP
WHERE id = '70000000-0000-4000-8000-000000000001';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM appointments
        WHERE id = '70000000-0000-4000-8000-000000000001'
          AND status = 'CANCELLED'
          AND cancelled_at IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'TEST FAILED: cancellation history not retained';
    END IF;
    RAISE NOTICE 'PASS: cancellation retained as historical record';
END $$;

-- 7. The same patient may book again after cancellation.
-- The new appointment must receive number 3, not number 1.

INSERT INTO appointments (
    id, session_id, patient_id, reason_encrypted
)
VALUES (
    '70000000-0000-4000-8000-000000000004',
    '60000000-0000-4000-8000-000000000001',
    '50000000-0000-4000-8000-000000000001',
    'LOCAL_TEST_ENCRYPTED_REASON_3'
);

DO $$
BEGIN
    IF (
        SELECT appointment_number FROM appointments
        WHERE id = '70000000-0000-4000-8000-000000000004'
    ) <> 3 THEN
        RAISE EXCEPTION 'TEST FAILED: rebooking did not receive number 3';
    END IF;
    RAISE NOTICE 'PASS: rebooking receives number 3; number 1 is not reused';
END $$;

-- 8. Fourth appointment receives number 4.

INSERT INTO appointments (
    id, session_id, patient_id, reason_encrypted
)
VALUES (
    '70000000-0000-4000-8000-000000000005',
    '60000000-0000-4000-8000-000000000001',
    '50000000-0000-4000-8000-000000000003',
    'LOCAL_TEST_ENCRYPTED_REASON_4'
);

DO $$
BEGIN
    IF (
        SELECT appointment_number FROM appointments
        WHERE id = '70000000-0000-4000-8000-000000000005'
    ) <> 4 THEN
        RAISE EXCEPTION 'TEST FAILED: fourth appointment number is not 4';
    END IF;
    RAISE NOTICE 'PASS: fourth appointment receives number 4';
END $$;

-- 9. Fifth appointment must fail because the session is full.

DO $$
BEGIN
    BEGIN
        INSERT INTO appointments (
            id, session_id, patient_id, reason_encrypted
        )
        VALUES (
            '70000000-0000-4000-8000-000000000006',
            '60000000-0000-4000-8000-000000000001',
            '50000000-0000-4000-8000-000000000004',
            'LOCAL_TEST_OVER_CAPACITY_REASON'
        );

        RAISE EXCEPTION 'TEST FAILED: over-capacity booking was accepted';
    EXCEPTION
        WHEN check_violation THEN
            RAISE NOTICE 'PASS: booking rejected when session is full';
    END;
END $$;

DO $$
BEGIN
    IF (
        SELECT last_appointment_number FROM doctor_sessions
        WHERE id = '60000000-0000-4000-8000-000000000001'
    ) <> 4 THEN
        RAISE EXCEPTION 'TEST FAILED: capacity failure changed counter';
    END IF;
    RAISE NOTICE 'PASS: capacity failure did not change appointment counter';
END $$;

-- 10. Appointment identity must not be modified.

DO $$
BEGIN
    BEGIN
        UPDATE appointments
        SET patient_id = '50000000-0000-4000-8000-000000000004'
        WHERE id = '70000000-0000-4000-8000-000000000003';

        RAISE EXCEPTION 'TEST FAILED: appointment identity was modified';
    EXCEPTION
        WHEN check_violation THEN
            RAISE NOTICE 'PASS: appointment identity changes are blocked';
    END;
END $$;

-- 11. Appointment history must not be permanently deleted.

DO $$
BEGIN
    BEGIN
        DELETE FROM appointments
        WHERE id = '70000000-0000-4000-8000-000000000001';

        RAISE EXCEPTION 'TEST FAILED: appointment deletion was accepted';
    EXCEPTION
        WHEN check_violation THEN
            RAISE NOTICE 'PASS: permanent appointment deletion is blocked';
    END;
END $$;

-- 12. Print final test evidence.

SELECT
    appointment_number,
    status,
    payment_status,
    patient_id
FROM appointments
WHERE session_id = '60000000-0000-4000-8000-000000000001'
ORDER BY appointment_number;

SELECT
    last_appointment_number,
    max_appointments
FROM doctor_sessions
WHERE id = '60000000-0000-4000-8000-000000000001';

-- All fixtures are temporary verification data.
-- Roll back so the local database returns to its original state.
ROLLBACK;

\echo '============================================================'
\echo 'ALL APPOINTMENT V4 VERIFICATION CHECKS PASSED'
\echo 'TEST FIXTURES ROLLED BACK - DATABASE LEFT CLEAN'
\echo '============================================================'
