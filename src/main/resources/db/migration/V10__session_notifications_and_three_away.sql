-- Operational session and queue notifications.
ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS chk_notification_type;

ALTER TABLE notifications
    ADD CONSTRAINT chk_notification_type CHECK (
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
            'ACCOUNT_STATUS_CHANGED',
            'THREE_NUMBERS_AWAY',
            'SESSION_DELAYED',
            'SESSION_CANCELLED'
        )
    );

-- A patient receives the three-numbers-away alert once per appointment.
CREATE UNIQUE INDEX uq_notification_three_away_once
    ON notifications(user_id, appointment_id, type)
    WHERE appointment_id IS NOT NULL
      AND type = 'THREE_NUMBERS_AWAY';
