# MediChannel Backend — Implementation Summary

This document explains how the new features are structured so a maintainer can
extend them safely.

---

## 1. Architecture overview

```
HTTP (Spring MVC)                    STOMP over WebSocket
        │                                     │
        ▼                                     ▼
Controllers (DTO in / DTO out)       ChatWebSocketController
        │                                     │
        ▼                                     ▼
Services (transactions, rules)  ◄──►  ChatService / NotificationService
        │
        ▼
Repositories (Spring Data JPA)
        │
        ▼
PostgreSQL + Flyway
```

- **DTO boundary:** entities are never serialized directly. Request records use
  Bean Validation; response records expose only display-safe fields.
- **Constructor injection** everywhere; no field injection.
- **Transactions** are declared on service methods. Read-only queries use
  `@Transactional(readOnly = true)`.
- **Authorization** is enforced twice: coarse role rules in `SecurityConfig`
  and resource ownership in `AppointmentAccessPolicy` / `ConsultationAccessService`.
- **Timestamps** come from an injected `Clock` (`TimeConfiguration`) so tests
  and future scheduling remain consistent.

---

## 2. New database objects

### `payments` (V5)
- Links to one appointment; optional `recorded_by` user.
- `status ∈ {PENDING, COMPLETED, FAILED, REFUNDED}`.
- Partial unique index guarantees **at most one COMPLETED payment per
  appointment** while preserving failed/refunded history.
- `paid_at` required exactly when status is `COMPLETED`.
- No card or credential columns exist.

### Appointment lifecycle timestamps (V5)
`arrived_at`, `consultation_started_at`, `consultation_completed_at`, plus a
check constraint ensuring completion never precedes start. Status remains the
single source of truth.

### `consultation_summaries` (V6)
One row per appointment. Free-text fields are stored in `*_encrypted` columns;
`consultation_date` and relationships stay readable for integrity checks.
Follow-up date cannot precede the consultation date.

### `chat_messages` (V7)
`appointment_id`, derived `sender_user_id`, derived `sender_role`
(`CUSTOMER`/`DOCTOR`), content 1–2000 characters, `sent_at`.

### `notifications` (V8)
Per-user, optional appointment link, constrained type list, read state kept
consistent (`is_read` ↔ `read_at`), indexes for latest and unread queries.

### `reviews` (V9)
One review per appointment (`uq_review_appointment`), rating 1–5, optional
comment ≤ 1000 characters, indexes for doctor and patient histories.

---

## 3. Domain model changes

| Entity | Change |
|---|---|
| `Appointment` | Lifecycle transitions (`markArrived`, `startConsultation`, `complete`, `markNoShow`), `markPaid`, audit timestamps |
| `DoctorSession` | `updateSchedule`, explicit `open/start/complete/cancel`, `delayMinutes` |
| `Patient` | `updateProfile` (encrypted fields + NIC lookup hash) |
| `User` | Administrator activate/suspend/disable/unlock, lock-state helpers |
| `Payment`, `ConsultationSummary`, `ChatMessage`, `Notification`, `Review` | New entities |

---

## 4. Services

| Service | Responsibility |
|---|---|
| `AppointmentService` | Booking, patient history, cancellation/check-in, doctor portal transitions, admin oversight, notifications |
| `AppointmentAccessPolicy` | Single source of truth for patient-owner / assigned-doctor / staff / chat-participant rules |
| `PaymentService` | Simulated payment recording, duplicate/terminal-state protection, history |
| `ConsultationSummaryService` | Encrypt/decrypt clinical text, completion gate, one-per-appointment, role read rules |
| `ChatService` | Sender-role derivation, validation, persistence, private history, recipient notification |
| `NotificationService` | Persist notification, push to `/user/queue/notifications` **after commit**, read-state |
| `ReviewService` | Eligibility, uniqueness, encrypted-free comments, public aggregate |
| `AdminService` | User listing/status, self-lockout and last-admin protection, stats, appointment/payment oversight |
| `ConsultationAccessService` | WebSocket destination authorization using the same ownership queries |
| `DoctorSessionService` | Session CRUD, scheduling validation, state transitions, delay |
| `PatientService` | Profile create/read/update with encryption |
| `DoctorService` | Doctor profile for the authenticated doctor |

### Notification delivery
`NotificationService.create` registers a `TransactionSynchronization` so the
STOMP push happens only after the database commit. A rolled-back action never
emits a phantom real-time event.

### Chat sender identity
`senderUserId` always comes from the authenticated `Principal` / JWT subject.
The client payload contains **only** `content`; spoofing a sender is impossible
through the API.

---

## 5. Security configuration

`SecurityConfig` route groups (summary):

| Route group | Access |
|---|---|
| `POST /api/v1/auth/register`, `POST /api/v1/auth/login` | Public |
| `GET /actuator/health`, `GET /ws` | Public |
| `/api/v1/patients/me` | CUSTOMER |
| `/api/v1/appointments` (book, me, detail, cancel, check-in) | CUSTOMER |
| `POST /api/v1/appointments/*/payments` | CUSTOMER, ADMIN, MANAGER |
| `GET /api/v1/appointments/*/payments`, `*/summary`, `*/chat/messages` | Authenticated (service enforces ownership) |
| `POST/PUT /api/v1/appointments/*/summary` | DOCTOR |
| `POST /api/v1/appointments/*/review`, `GET /api/v1/reviews/me` | CUSTOMER |
| `/api/v1/payments/me` | CUSTOMER |
| `/api/v1/notifications/**` | Authenticated (always scoped to caller) |
| `/api/v1/doctor/**` | DOCTOR |
| `/api/v1/admin/**` | ADMIN |
| `GET /api/v1/doctors/**`, `GET /api/v1/specialisations/**`, `GET /api/v1/sessions` | Public |
| Doctor/specialisation/room/session management | ADMIN, MANAGER |

CORS accepts only the comma-separated origins in `APP_FRONTEND_ORIGIN`; `*` and
empty lists fail fast at startup. Credentials are disabled.

---

## 6. WebSocket flow

1. Browser opens `/ws` (handshake allowed without HTTP auth).
2. Client sends STOMP `CONNECT` with `Authorization: Bearer <jwt>`.
3. `WebSocketJwtInterceptor` validates signature/issuer/expiry, reloads the
   user, requires `ACTIVE` + verified email, and sets the principal with the
   **current** database role.
4. `WebSocketAuthorizationInterceptor` allows only:
   - `SUBSCRIBE /user/queue/notifications`
   - `SUBSCRIBE /user/queue/errors`
   - `SUBSCRIBE /topic/appointments/{id}/chat` for the patient or assigned doctor
   - `SEND /app/appointments/{id}/chat` for the patient or assigned doctor
5. `ChatWebSocketController` persists the message, then broadcasts a
   `ChatMessageResponse` to the appointment topic.
6. Failures are sent to the caller's `/user/queue/errors` without leaking
   internals.

---

## 7. Important invariants

- A consultation can only start after the appointment is `PAID` and the session
  is `OPEN` or `IN_PROGRESS`.
- A summary can only be created after `COMPLETED`, exactly once, by the assigned
  doctor.
- A patient can see their summary only after completion; staff can always read.
- Reviews require `COMPLETED`, patient ownership and one review per appointment.
- Chat is unavailable for cancelled appointments and never accessible to staff.
- Payments cannot be recorded for cancelled/no-show/paid appointments.
- An administrator cannot suspend/disable themselves or the last active admin.
- Deleting users is not exposed; lifecycle is activate/suspend/disable.

---

## 8. Configuration added

| Property | Default | Purpose |
|---|---|---|
| `server.port` | `8080` | Render injects `PORT` |
| `server.forward-headers-strategy` | `framework` | Correct scheme/host behind Render proxy |
| `app.registration.auto-activate` | `true` | No email provider yet |
| `APP_FRONTEND_ORIGIN` | `http://localhost:3000` | CORS + WebSocket origin allow-list |
| `APP_ADMIN_EMAIL` / `APP_ADMIN_PASSWORD` | empty | Optional first-admin bootstrap |

Required secrets remain `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`,
`ENCRYPTION_KEY`, `HMAC_SECRET`.

---

## 9. Testing strategy

- **Unit tests:** WebSocket interceptors, account lock entity, AES-GCM service.
- **PostgreSQL integration tests:** real Flyway migrations, Hibernate schema
  validation, MockMvc HTTP flows, JdbcTemplate assertions on encrypted columns.
- **Isolation:** every integration test is `@Transactional` and rolls back.
- **No production access:** tests never read Neon credentials; the local test
  database is a disposable PostgreSQL container.
