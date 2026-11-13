-- =============================================
-- MEDICHANNEL
-- V14: Hospital notices published by managers
-- Target: PostgreSQL
-- =============================================

CREATE TABLE notices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    title VARCHAR(150) NOT NULL,
    body VARCHAR(4000) NOT NULL,

    is_published BOOLEAN NOT NULL DEFAULT FALSE,
    published_at TIMESTAMPTZ,

    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_notices_created_by
        FOREIGN KEY (created_by)
        REFERENCES users(id)
        ON DELETE SET NULL,

    CONSTRAINT chk_notice_title_length
        CHECK (char_length(title) BETWEEN 1 AND 150),

    CONSTRAINT chk_notice_body_length
        CHECK (char_length(body) BETWEEN 1 AND 4000),

    CONSTRAINT chk_notice_publish_state
        CHECK (
            (is_published = FALSE AND published_at IS NULL)
            OR (is_published = TRUE AND published_at IS NOT NULL)
        )
);

CREATE INDEX idx_notices_published
    ON notices(published_at DESC)
    WHERE is_published = TRUE;

CREATE INDEX idx_notices_created
    ON notices(created_at DESC);
