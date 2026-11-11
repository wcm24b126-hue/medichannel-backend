-- =============================================
-- MEDICHANNEL
-- V7: Persistent consultation chat messages
-- Target: PostgreSQL
-- =============================================

-- Sender identity is always derived from the authenticated
-- WebSocket principal. The application never trusts a client
-- supplied sender ID.

CREATE TABLE chat_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    appointment_id UUID NOT NULL,
    sender_user_id UUID NOT NULL,

    sender_role VARCHAR(20) NOT NULL,

    content TEXT NOT NULL,

    sent_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_chat_messages_appointment
        FOREIGN KEY (appointment_id)
        REFERENCES appointments(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_chat_messages_sender
        FOREIGN KEY (sender_user_id)
        REFERENCES users(id)
        ON DELETE RESTRICT,

    CONSTRAINT chk_chat_sender_role
        CHECK (sender_role IN ('CUSTOMER', 'DOCTOR')),

    CONSTRAINT chk_chat_content_length
        CHECK (char_length(content) BETWEEN 1 AND 2000)
);

CREATE INDEX idx_chat_messages_appointment_sent
    ON chat_messages(appointment_id, sent_at ASC);

CREATE INDEX idx_chat_messages_sender_sent
    ON chat_messages(sender_user_id, sent_at DESC);
