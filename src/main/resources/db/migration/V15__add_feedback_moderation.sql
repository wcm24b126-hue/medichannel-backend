-- =============================================
-- MEDICHANNEL
-- V15: Feedback moderation by managers
-- Target: PostgreSQL
-- =============================================

ALTER TABLE reviews
    ADD COLUMN IF NOT EXISTS moderation_status VARCHAR(20)
        NOT NULL DEFAULT 'APPROVED';

ALTER TABLE reviews
    DROP CONSTRAINT IF EXISTS chk_review_moderation_status;

ALTER TABLE reviews
    ADD CONSTRAINT chk_review_moderation_status
        CHECK (moderation_status IN ('PENDING', 'APPROVED', 'HIDDEN'));

CREATE INDEX IF NOT EXISTS idx_reviews_moderation
    ON reviews(moderation_status, created_at DESC);
