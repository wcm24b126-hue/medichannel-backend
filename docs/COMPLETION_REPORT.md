# MediChannel Backend — Completion Report

**Branch:** `backend-ashan`
**Date:** 11 November 2026
**Stack:** Java 21 · Spring Boot 4.1.2 · PostgreSQL · Flyway · Spring Security (JWT) · WebSocket/STOMP
**Test result:** **53 tests · 0 failures · 0 errors · 0 skipped — BUILD SUCCESS**

---

## 1. Scope delivered

The backend now covers the full appointment and consultation lifecycle with
role-based authorization, ownership checks, validation, encryption of personal
and clinical data, persistent chat, notifications, reviews and administrative
oversight.

| Area | Status | Notes |
|---|---|---|
| Registration / login / JWT | ✅ Preserved | BCrypt cost 12, 15-minute HS256 access tokens, login lockout |
| Patient profiles | ✅ Extended | `PUT /api/v1/patients/me`, encrypted PII |
| Doctor profiles | ✅ Extended | Doctor self-service profile endpoint |
| Doctor sessions | ✅ Extended | Update, open/start/complete/cancel, delay, doctor view |
| Appointments | ✅ Extended | Booking, history, cancellation, check-in |
| Appointment states | ✅ New | `BOOKED → ARRIVED → IN_CONSULTATION → COMPLETED`, `NO_SHOW`, `CANCELLED` |
| Payments | ✅ New | Simulated CASH/CARD recording, per-appointment + patient history |
| Consultation summaries | ✅ New | Doctor-authored, encrypted at rest, patient/staff read rules |
| Real-time chat | ✅ New | STOMP over WebSocket, persistent history, participant-only |
| Notifications | ✅ New | Persisted per user, unread counts, mark-read, private STOMP queue |
| Reviews / ratings | ✅ New | Completed-appointment only, one per appointment, public averages |
| Admin portal | ✅ New | User status management, stats, appointment/payment oversight |
| CORS / WebSocket handshake | ✅ Hardened | Explicit origin allow-list, no wildcard, no credentials |
| Admin bootstrap | ✅ New | Env-driven first admin, no default password |

---

## 2. Security posture

- **Authentication:** stateless HS256 JWT; issuer and expiry validated on every
  request. Role claim is re-derived from the database at WebSocket CONNECT so a
  stale token cannot grant a revoked role.
- **Authorization:** URL rules in `SecurityConfig`, plus a central
  `AppointmentAccessPolicy` for resource-level ownership. Patients only see
  their own records; doctors only their own consultations; staff have oversight;
  chat excludes staff entirely.
- **Passwords:** BCrypt (strength 12), never returned or logged. Login lockout
  after 5 failures for 15 minutes.
- **PII/PHI at rest:** names, phone, NIC, date of birth, gender and consultation
  free text are AES-GCM encrypted. NIC also has an HMAC lookup hash for
  uniqueness without decryption.
- **Payments:** no card number, CVV, expiry or token is collected or stored.
  `CARD` payments are explicitly simulated records.
- **CORS:** explicit origins only; `*` is rejected at startup; credentials
  disabled because the API uses Bearer tokens.
- **WebSocket:** handshake permitted without HTTP auth (browsers cannot send
  headers), JWT required on STOMP `CONNECT`, destinations denied by default,
  chat topics/sends checked against the same ownership rules.
- **Errors:** JSON 401/403 responses, request IDs, no stack traces or SQL
  leakage, validation messages generic at the boundary.
- **Secrets:** all secrets come from environment variables. `.env` and secret
  files remain untracked.

---

## 3. Database changes

New forward-only Flyway migrations (V1–V4 untouched):

| Version | Purpose |
|---|---|
| `V5__create_payments_and_consultation_lifecycle.sql` | `payments` table, appointment lifecycle timestamps, consistency constraints |
| `V6__create_consultation_summaries.sql` | Encrypted consultation summaries, one per appointment |
| `V7__create_chat_messages.sql` | Persistent chat messages with role/content constraints |
| `V8__create_notifications.sql` | Per-user notifications, read state, notification type constraint |
| `V9__create_reviews.sql` | Reviews, 1–5 rating, one per appointment, indexes |

All migrations were validated and applied from an empty schema against
PostgreSQL 18 during the test run.

> V9 initially declared `rating SMALLINT` while the entity used `int`. It was
> corrected to `INTEGER` **before V9 was committed or applied outside the
> disposable local test database**; that local schema was rebuilt so Flyway
> re-applied all nine migrations cleanly. No shared or production database was
> altered, and V1–V4 were never edited.

---

## 4. Verification

- `./mvnw test` → **53 tests, 0 failures, 0 errors, 0 skipped**.
- Hibernate `ddl-auto=validate` passes against the migrated PostgreSQL schema,
  proving entity/migration alignment.
- Integration tests cover IDOR, role escalation, payment duplication, lifecycle
  ordering, summary encryption and access, chat privacy/spoofing, notification
  ownership, review eligibility/duplication, admin self-lockout and lockout
  recovery.
- No baseline test was weakened or removed; the original 27 tests still pass.
- No production Neon database was contacted during testing.

Detailed results: [`TEST_REPORT.md`](TEST_REPORT.md).

---

## 5. Residual risks / not in scope

Documented in [`REMAINING_WORK.md`](REMAINING_WORK.md). The most important:

1. Payments are **simulated**, not processed by a gateway.
2. Registration auto-activates because no email-verification provider exists.
3. Integration tests point at a local PostgreSQL instance at
   `127.0.0.1:55432`; CI must provide that container or the configuration must
   be externalised.
4. No refresh-token/rotation flow; access tokens last 15 minutes.
5. No rate limiting at the application layer (rely on edge/proxy).
6. No audit-log table yet, although the original README listed audit logs.
7. No file uploads (avatars, reports) or video consultation.

---

## 6. Change management

No commits or pushes were made. All work is uncommitted on `backend-ashan`.
A full inventory is in [`GIT_SUMMARY.md`](GIT_SUMMARY.md). Three untracked
assistant/tooling artefacts (`.agents/`, `.claude/`, `skills-lock.json`) must
**not** be staged.
