CREATE TABLE IF NOT EXISTS audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    user_email VARCHAR(255),
    user_role VARCHAR(50),
    action VARCHAR(255) NOT NULL,
    details TEXT,
    ip_address VARCHAR(100) DEFAULT '127.0.0.1'
);

CREATE INDEX IF NOT EXISTS idx_audit_logs_created_at ON audit_logs(created_at DESC);

-- Seed initial demonstration records
INSERT INTO audit_logs (created_at, user_email, user_role, action, details, ip_address) VALUES
    (NOW() - INTERVAL '3 hours', 'isuru@example.lk', 'ADMIN', 'Account created (DOCTOR)', 'Dr. N. Wijesinghe registered under Cardiology', '10.0.1.5'),
    (NOW() - INTERVAL '2 hours', 'Unknown', NULL, 'Login locked after 5 failures', 'Exceeded maximum failed attempts from IP', '10.0.9.77'),
    (NOW() - INTERVAL '90 minutes', 'n.wijesinghe@example.lk', 'DOCTOR', 'Consultation record opened', 'Consultation room opened for session', '10.0.4.31'),
    (NOW() - INTERVAL '45 minutes', 'ruwan@example.lk', 'MANAGER', 'Session cancelled (session 5521)', 'Session cancelled due to doctor schedule conflict', '10.0.4.18');
