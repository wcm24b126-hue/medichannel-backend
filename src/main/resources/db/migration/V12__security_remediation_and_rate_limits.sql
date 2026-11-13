-- ============================================================================
-- MEDICHANNEL
-- V12: Security Hardening, Notification Privacy Sanitization & Rate Limits
-- ============================================================================

-- 1. Sanitize all legacy notification previews to generic privacy-preserving text
UPDATE notifications
SET message = 'You have a new consultation message. Open MediChannel to view it.'
WHERE type = 'NEW_CHAT_MESSAGE';

-- 2. Add token_version to users table for instantaneous token invalidation/revocation on logout
ALTER TABLE users
ADD COLUMN IF NOT EXISTS token_version INTEGER NOT NULL DEFAULT 1;

-- 3. Distributed API Rate Limiting Table
CREATE TABLE IF NOT EXISTS api_rate_limits (
    rate_key VARCHAR(128) NOT NULL,
    window_bucket BIGINT NOT NULL,
    request_count INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_api_rate_limits PRIMARY KEY (rate_key, window_bucket)
);

CREATE INDEX IF NOT EXISTS idx_rate_limits_window
    ON api_rate_limits(window_bucket);
