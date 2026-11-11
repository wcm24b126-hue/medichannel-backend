# MediChannel Backend — Remaining Work

The requested scope is functionally complete and tested. The items below are
deliberately out of scope or require external infrastructure. They are ordered
by production impact.

---

## High priority before public launch

### 1. Real email verification and password reset
Registration currently auto-activates
(`APP_REGISTRATION_AUTO_ACTIVATE=true`) because no email provider exists.
Add a provider (SES/Resend/SendGrid), a verification token table, a verification
endpoint, and set the flag to `false`. Add forgot/reset-password with
single-use, expiring tokens.

### 2. Real payment gateway
`PaymentService` records payments but does not charge anything. A `CARD`
payment is simulated. Integrate a gateway (Stripe/PayHere/local provider) with:
- server-side amount authority (never trust a client amount),
- idempotency keys,
- signed webhook verification,
- `PENDING → COMPLETED/FAILED` transitions through the callback,
- refund handling,
- reconciliation against `Payments`.

### 3. Refresh tokens / session revocation
Access tokens last 15 minutes and cannot be revoked before expiry. Add rotating
refresh tokens (hashed at rest), logout, and a revocation list or short-lived
access tokens with a token version on `users`.

### 4. Application-level rate limiting
Login lockout exists, but there is no global/IP rate limit. Add a filter or
edge rule for `/auth/login`, `/auth/register`, payment, chat and review
endpoints.

### 5. CI test database
PostgreSQL integration tests hard-code `127.0.0.1:55432` and local credentials.
Externalize them via environment variables or Testcontainers so CI can run the
suite without manual setup.

---

## Medium priority

### 6. Audit log
The project README lists audit logs; no `audit_logs` table exists yet. Log
administrative actions (user status, appointment overrides), authentication
events and payment state changes, without storing secrets or unnecessary PII.

### 7. Appointment reminders
`APPOINTMENT_REMINDER` exists as a notification type but nothing schedules it.
Add a scheduled job (e.g. 24h/1h before session) with idempotent delivery.

### 8. Chat enhancements
- Read receipts / unread message counts.
- Message moderation and reporting.
- Attachments (needs storage + malware scanning).
- Multi-instance STOMP broker relay (currently in-memory simple broker).
- Encrypt `chat_messages.content` at rest (consultation summaries are already
  encrypted; chat may also contain clinical information).

### 9. Consultation summary refinements
- `consultationDate` currently uses the session date; consider using the actual
  completion date.
- Add amendment history / versioning for clinical notes.
- Add a patient acknowledgement state.

### 10. Queue management
The README mentions queue management. Current behaviour exposes an estimated
consultation time from session start, minutes-per-patient and delay. A dedicated
queue position/check-in board could be added.

### 11. Manager role coverage
Role rules allow ADMIN and MANAGER for management endpoints, but automated
tests only exercise ADMIN. Add MANAGER fixtures and negative tests.

### 12. OpenAPI documentation
The API is documented in Markdown. Add `springdoc-openapi` (or generate a spec)
so the frontend can generate typed clients and the docs stay in sync.

---

## Low priority / hardening

- Add concurrency tests for duplicate payment/review races (constraints already
  exist; assert the mapped `409`).
- Add pagination to notifications, reviews and admin listings (currently full
  lists).
- Add soft-delete/retention policy for chat and summaries.
- Add database connection SSL enforcement in production configuration.
- Add structured JSON logging and metrics for auth failures, payment failures
  and STOMP errors.
- Add CSP/HSTS at the edge; the API itself sends JSON only.
- Add account deletion or anonymisation for privacy compliance.
- Add video consultation (WebRTC) if required by the product.
- Add file uploads (profile images, medical reports) with storage and scanning.

---

## Known limitations (documented, not bugs)

1. **No email verification provider** → auto-activation.
2. **Simulated payments** → no money moves, no gateway references are verified.
3. **Single-instance real-time** → STOMP simple broker.
4. **No refresh tokens** → users re-login after 15 minutes.
5. **No audit table** → request IDs and application logs only.
6. **No file storage** → avatars/reports use external URLs or are unsupported.
7. **No scheduled jobs** → reminders and expiry cleanup are not automated.
