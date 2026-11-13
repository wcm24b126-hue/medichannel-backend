-- ============================================================================
-- MEDICHANNEL PRODUCTION SAFETY SCRIPT
-- Purpose: Safely deactivate demo and test accounts in production without
--          violating foreign-key constraints or deleting clinical histories.
-- Execution: Run manually against the production database when decommissioning
--            staging/demo accounts.
-- ============================================================================

BEGIN;

-- 1. Count demo accounts before deactivation
DO $$
DECLARE
    demo_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO demo_count
    FROM users
    WHERE email LIKE '%@medichannel.demo'
       OR email LIKE '%@medic.demo'
       OR email LIKE '%@example.com'
       OR email LIKE '%@example.test'
       OR email LIKE '%@example.lk';

    RAISE NOTICE 'Found % demo/test accounts to deactivate.', demo_count;
END $$;

-- 2. Safely disable demo accounts (preserves doctor/patient foreign keys)
UPDATE users
SET account_status = 'DISABLED',
    locked_until = '2099-01-01 00:00:00+00',
    failed_login_attempts = 99,
    token_version = COALESCE(token_version, 1) + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE (email LIKE '%@medichannel.demo'
   OR email LIKE '%@medic.demo'
   OR email LIKE '%@example.com'
   OR email LIKE '%@example.test'
   OR email LIKE '%@example.lk')
  -- Retain the primary administrator account if it was bootstrap-created
  AND email != COALESCE(CURRENT_SETTING('app.admin_email', true), 'admin@medichannel.demo');

-- 3. Confirm deactivation summary
SELECT id, email, account_status, locked_until, updated_at
FROM users
WHERE account_status = 'DISABLED'
ORDER BY email;

COMMIT;
