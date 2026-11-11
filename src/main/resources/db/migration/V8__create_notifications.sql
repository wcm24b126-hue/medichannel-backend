-- =============================================
-- MEDICHANNEL
-- V8: Persistent user notifications
-- Target: PostgreSQL
-- =============================================

CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    user_id UUID NOT NULL,
    appointment_id UUID,

    type VARCHAR(40) NOT NULL,
    title VARCHAR(150) NOT NULL,
    message VARCHAR(1000) NOT NULL,

    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    read_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_notifications_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_notifications_appointment
        FOREIGN KEY (appointment_id)
        REFERENCES appointments(id)
        ON DELETE SET NULL,

    CONSTRAINT chk_notification_type
        CHECK (
            type IN (
                'APPOINTMENT_BOOKED',
                'APPOINTMENT_ARRIVED',
                'APPOINTMENT_CANCELLED',
                'APPOINTMENT_REMINDER',
                'PAYMENT_RECEIVED',
                'CONSULTATION_STARTED',
                'CONSULTATION_COMPLETED',
                'SUMMARY_AVAILABLE',
                'NEW_CHAT_MESSAGE',
                'REVIEW_RECEIVED',
                'ACCOUNT_STATUS_CHANGED'
            )
        ),

    CONSTRAINT chk_notification_read_state
        CHECK (
            (is_read = FALSE AND read_at IS NULL)
            OR (is_read = TRUE AND read_at IS NOT NULL)
        )
);

CREATE INDEX idx_notifications_user_created
    ON notifications(user_id, created_at DESC);

CREATE INDEX idx_notifications_user_unread
    ON notifications(user_id)
    WHERE is_read = FALSE;

CREATE INDEX idx_notifications_appointment
    ON notifications(appointment_id)
    WHERE appointment_id IS NOT NULL;
