# MediChannel Backend — API Reference

Base path: `/api/v1` · JSON only · `Authorization: Bearer <jwt>` for protected
routes · timestamps are ISO-8601 UTC.

Error envelope:

```json
{
  "success": false,
  "errorCode": "FORBIDDEN",
  "message": "You are not authorized to access this appointment.",
  "referenceId": "…",
  "timestamp": "2026-11-11T06:00:00Z"
}
```

Common status codes: `400` validation, `401` unauthenticated/invalid
credentials, `403` role or ownership denial, `404` not found (also used to hide
other users' resources), `409` state conflict/duplicate, `500` unexpected.

---

## 1. Authentication

### `POST /api/v1/auth/register` — public

```json
{
  "email": "patient@example.com",
  "password": "8-72 chars with letter, number and symbol",
  "confirmPassword": "8-72 chars with letter, number and symbol"
}
```

`201` → `{ message, userId, email, accountStatus }`. Accounts are auto-activated
while no email-verification provider is configured.

### `POST /api/v1/auth/login` — public

```json
{ "email": "patient@example.com", "password": "8-72 chars with letter, number and symbol" }
```

`200` → `{ accessToken, tokenType, expiresIn, userId, email, role }`.
Tokens are HS256, issuer `medichannel-api`, lifetime 15 minutes. Five failed
attempts lock the account for 15 minutes; locked/suspended/disabled accounts get
`401`.

---

## 2. Patient profile — CUSTOMER

| Method | Path | Description |
|---|---|---|
| `POST` | `/patients/me` | Create profile (once) |
| `GET` | `/patients/me` | Read own profile |
| `PUT` | `/patients/me` | Update own profile |

Request:

```json
{
  "firstName": "Nimal",
  "lastName": "Perera",
  "phone": "0771234567",
  "nic": "200012345678",
  "dateOfBirth": "2000-01-01",
  "gender": "MALE"
}
```

Response: `{ patientId, firstName, lastName, phone, dateOfBirth, gender,
createdAt, updatedAt }`. PII is encrypted at rest.

---

## 3. Appointments — CUSTOMER

| Method | Path | Description |
|---|---|---|
| `POST` | `/appointments` | Book a session |
| `GET` | `/appointments/me` | Own appointment history |
| `GET` | `/appointments/{appointmentId}` | Own appointment |
| `PATCH` | `/appointments/{appointmentId}/cancel` | Cancel before the session |
| `PATCH` | `/appointments/{appointmentId}/check-in` | `BOOKED → ARRIVED` |
| `PATCH` | `/appointments/{appointmentId}/hide` | Clear a closed row from this patient's history view |

Booking request:

```json
{
  "sessionId": "uuid",
  "reason": "Persistent headache",
  "period": "MORNING"
}
```

`period` is required and must be `MORNING` (07:00–11:00) or `EVENING`
(13:00–15:00). A mismatch with the selected session returns `400`.

Response fields include `appointmentId`, `appointmentNumber`, `sessionDate`,
`sessionStartTime`, `estimatedConsultationTime`, `status`, `paymentStatus`,
`consultationFeeLkr`, `doctorId`, `doctorName`, `specialisation`,
`consultationType`, `roomNumber`, `paymentMethod`, and lifecycle timestamps.

Booking rules: session must be `OPEN`, before the booking deadline (60 min
physical / 30 min online), capacity available, one active booking per patient
per session. No appointment number is issued during the 11:00–13:00 break;
both patient and reception booking return `409` then.

`PATCH /appointments/{appointmentId}/hide` is a non-destructive, participant-
scoped visibility change. It is allowed only for `COMPLETED`, `NO_SHOW` or
`CANCELLED` rows and returns `204`. The appointment, payment, chat, summary,
review and audit records are retained; the doctor's view is unaffected.

### Live queue

`GET /api/v1/sessions/{sessionId}/queue` returns the public queue summary
(`sessionId`, `nowServing`, `nextNumber`, `remainingAppointments`,
`maxAppointments`). Updates are broadcast to
`/topic/sessions/{sessionId}/queue` over WebSocket; patients, doctors and
managers may subscribe, but only staff may publish state transitions.

---

## 4. Payments — CUSTOMER, ADMIN, MANAGER

### `POST /api/v1/appointments/{appointmentId}/payments`

```json
{ "method": "CARD" }
```

`201` → `{ paymentId, appointmentId, amount, currency, method, status,
paymentReference, paidAt, createdAt }`.

**Simulated payment:** no card number, CVV or gateway token is accepted or
stored. `CARD` is a recorded method only. Duplicate, cancelled, no-show and
already-paid appointments return `409`.

### `GET /api/v1/appointments/{appointmentId}/payments`
Patient owner, assigned doctor or staff. Returns payment history.

### `GET /api/v1/payments/me`
Authenticated patient's payment history, newest first.

---

## 5. Doctor portal — DOCTOR

| Method | Path | Description |
|---|---|---|
| `GET` | `/doctor/profile` | Own doctor profile |
| `GET` | `/doctor/appointments?date=&status=` | Own appointments |
| `GET` | `/doctor/appointments/{appointmentId}` | One appointment |
| `PATCH` | `/doctor/appointments/{appointmentId}/arrive` | Mark arrival |
| `PATCH` | `/doctor/appointments/{appointmentId}/start` | Start consultation |
| `PATCH` | `/doctor/appointments/{appointmentId}/complete` | Complete consultation |
| `PATCH` | `/doctor/appointments/{appointmentId}/no-show` | Mark no-show |
| `PATCH` | `/doctor/appointments/{appointmentId}/hide` | Clear a closed row from this doctor's session history view |
| `GET` | `/doctor/sessions?date=` | Own sessions |

Rules:
- Arrival is allowed from `BOOKED` (becomes `ARRIVED`).
- Start is allowed from `BOOKED`/`ARRIVED`, requires `PAID` and an `OPEN` or
  `IN_PROGRESS` session; becomes `IN_CONSULTATION`.
- Complete requires `IN_CONSULTATION`; becomes `COMPLETED`.
- No-show is allowed from `BOOKED`/`ARRIVED`.
- Hide is allowed only for `COMPLETED`, `NO_SHOW` or `CANCELLED` rows and only
  affects this doctor's queue/history view.
- All operations are scoped to the doctor's own appointments (`404` otherwise).

Response (`DoctorAppointmentResponse`) includes patient display name, queue
estimate, room, status, payment status/method, and lifecycle timestamps.

---

## 6. Consultation summaries

| Method | Path | Access |
|---|---|---|
| `POST` | `/appointments/{appointmentId}/summary` | Assigned doctor, after `COMPLETED` |
| `PUT` | `/appointments/{appointmentId}/summary` | Assigned doctor |
| `GET` | `/appointments/{appointmentId}/summary` | Patient owner or assigned doctor only |

Request:

```json
{
  "symptoms": "…",
  "diagnosis": "Required",
  "treatmentNotes": "…",
  "prescription": "…",
  "followUpInstructions": "…",
  "followUpDate": "2027-01-15"
}
```

Response returns decrypted plaintext to authorized readers. At rest every
free-text field is AES-GCM ciphertext. One summary per appointment (`409` on
duplicate). Follow-up date cannot precede the consultation date.

---

## 7. Reviews

| Method | Path | Access |
|---|---|---|
| `POST` | `/appointments/{appointmentId}/review` | Patient owner, `COMPLETED` only |
| `GET` | `/reviews/me` | CUSTOMER |
| `GET` | `/doctors/{doctorId}/reviews` | Public |

Request:

```json
{ "rating": 5, "comment": "Excellent care" }
```

`rating` 1–5, comment ≤ 1000 characters. One review per appointment (`409`).
Public response: `{ doctorId, averageRating, reviewCount, reviews[] }`.

---

## 8. Chat

### `GET /api/v1/appointments/{appointmentId}/chat/messages?limit=200`

Patient owner or assigned doctor only (staff excluded). Returns messages oldest
first: `{ messageId, appointmentId, senderUserId, senderRole, senderName,
content, sentAt }`. `limit` is capped at 500.

### WebSocket

Endpoint: `/ws` (STOMP). Allowed origins are the exact origins configured in
`APP_FRONTEND_ORIGIN`: `https://medichannel-f-p3z5.vercel.app` and the protected
preview origin `https://medichannel-f-p3z5-git-main-ashandhanushka660s-projects.vercel.app`.
Wildcard Vercel origins are not accepted in production.

STOMP headers on `CONNECT`:

```
Authorization: Bearer <jwt>
```

Subscribe to history/live messages:

```
SUBSCRIBE /topic/appointments/{appointmentId}/chat
```

Send a message:

```
SEND /app/appointments/{appointmentId}/chat
{"content": "Hello doctor"}
```

Server-derived identity; the payload has **no** sender field. Errors are sent to
`/user/queue/errors` as `{ code, message }`.

Allowed queues: `/user/queue/notifications`, `/user/queue/errors`. All other
destinations are denied.

---

## 9. Notifications — any authenticated user

| Method | Path | Description |
|---|---|---|
| `GET` | `/notifications` | Own notifications, newest first |
| `GET` | `/notifications/unread-count` | `{ unreadCount }` |
| `PATCH` | `/notifications/{notificationId}/read` | Mark one read |
| `PATCH` | `/notifications/read-all` | Mark all read |

Marking another user's notification returns `404`. Notifications are also
pushed to `/user/queue/notifications` after the originating transaction commits.

Types: `APPOINTMENT_BOOKED`, `APPOINTMENT_ARRIVED`, `APPOINTMENT_CANCELLED`,
`APPOINTMENT_REMINDER`, `PAYMENT_RECEIVED`, `CONSULTATION_STARTED`,
`CONSULTATION_COMPLETED`, `SUMMARY_AVAILABLE`, `NEW_CHAT_MESSAGE`,
`REVIEW_RECEIVED`, `ACCOUNT_STATUS_CHANGED`.

---

## 10. Administration — ADMIN only, except reception

User, room, audit and statistics endpoints are ADMIN-only. Reception appointment
endpoints are also available to MANAGER.

| Method | Path | Access | Description |
|---|---|---|---|
| `GET` | `/admin/users?role=&status=` | ADMIN | List users |
| `GET` | `/admin/users/{userId}` | ADMIN | One user |
| `PATCH` | `/admin/users/{userId}/activate` | ADMIN | Activate / verify |
| `PATCH` | `/admin/users/{userId}/suspend` | ADMIN | Suspend |
| `PATCH` | `/admin/users/{userId}/disable` | ADMIN | Disable |
| `PATCH` | `/admin/users/{userId}/unlock` | ADMIN | Clear login lock |
| `POST` | `/admin/users/{userId}/reset-password` | ADMIN | Set a new password for a DOCTOR or MANAGER |
| `GET` | `/admin/stats` | ADMIN | User/appointment/payment totals |
| `GET` | `/admin/appointments?sessionId=&doctorId=&status=&date=` | ADMIN, MANAGER | Oversight |
| `GET` | `/admin/appointments/{appointmentId}` | ADMIN, MANAGER | One appointment |
| `PATCH` | `/admin/appointments/{appointmentId}/arrive` | ADMIN, MANAGER | Mark a booked patient as arrived |
| `GET` | `/admin/payments` | ADMIN | All payments |
| `GET` | `/admin/appointments/{appointmentId}/payments` | ADMIN, MANAGER | Payments for one appointment |

### Walk-in booking

| Method | Path | Access | Description |
|---|---|---|---|
| `POST` | `/admin/appointments/walk-in` | ADMIN, MANAGER | Book a patient who is at the reception desk |

Request body:

```json
{
  "sessionId": "33333333-3333-3333-3333-333333333333",
  "firstName": "Asha",
  "lastName": "Perera",
  "phone": "0712345678",
  "reason": "Persistent headache"
}
```

`phone` must match `07XXXXXXXX`. Staff booking reuses an existing patient record
for that phone where possible; otherwise it creates an active CUSTOMER account
and encrypted patient profile. The appointment is created as `BOOKED`, the queue
is published, and the reception desk can then mark the patient arrived and
record payment. Returns `201` with the appointment response.

Password reset accepts `{ "newPassword": "...", "confirmPassword": "..." }`.
The password must be 8–72 bytes with a letter, a number and a symbol. Only
DOCTOR and MANAGER accounts can be reset; the action unlocks the account and
increments the token version so every existing session is signed out.

Self-suspension/disable and suspending/disabling the last active administrator
return `409`. Unknown role filters return `400`.

---

## 11. Doctor sessions — public read, ADMIN/MANAGER write

| Method | Path | Access |
|---|---|---|
| `GET` | `/sessions?doctorId=&specialisationId=&date=` | Public |
| `GET` | `/sessions/{sessionId}` | Authenticated |
| `POST` | `/sessions` | ADMIN/MANAGER |
| `PUT` | `/sessions/{sessionId}` | ADMIN/MANAGER |
| `PATCH` | `/sessions/{sessionId}/open` | ADMIN/MANAGER |
| `PATCH` | `/sessions/{sessionId}/start` | ADMIN/MANAGER |
| `PATCH` | `/sessions/{sessionId}/complete` | ADMIN/MANAGER |
| `PATCH` | `/sessions/{sessionId}/cancel` | ADMIN/MANAGER |
| `PATCH` | `/sessions/{sessionId}/delay` | ADMIN/MANAGER |

Create/update request:

```json
{
  "doctorId": "uuid",
  "roomId": "uuid",
  "sessionDate": "2026-12-01",
  "startTime": "09:00",
  "endTime": "12:00",
  "consultationType": "PHYSICAL",
  "maxAppointments": 10,
  "minutesPerPatient": 15,
  "feeLkr": 1500.00
}
```

Delay request: `{ "delayMinutes": 15 }`.

---

## 12. Doctors, specialisations, rooms

| Module | Public read | ADMIN/MANAGER write |
|---|---|---|
| Doctors | `GET /doctors`, `GET /doctors/{id}` | `POST /doctors`, `PUT /doctors/{id}`, `PATCH /doctors/{id}/availability` |
| Specialisations | `GET /specialisations`, `GET /specialisations/{id}` | `POST`, `PUT`, `PATCH /{id}/activate`, `PATCH /{id}/deactivate` |
| Rooms | — | `GET/POST/PUT/PATCH` under `/rooms` (ADMIN/MANAGER) |

---

## 13. Health

`GET /actuator/health` — public, used by Render health checks.

---

## 14. Notices — public read, ADMIN/MANAGER write

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET | `/api/v1/notices` | public | Published hospital notices |
| GET | `/api/v1/notices/manage` | ADMIN, MANAGER | All notices, including drafts |
| POST | `/api/v1/notices` | ADMIN, MANAGER | Create a notice (optionally published) |
| PUT | `/api/v1/notices/{id}` | ADMIN, MANAGER | Update title, body and publish state |
| PATCH | `/api/v1/notices/{id}/publish` | ADMIN, MANAGER | Publish a notice |
| PATCH | `/api/v1/notices/{id}/withdraw` | ADMIN, MANAGER | Withdraw a published notice |
| DELETE | `/api/v1/notices/{id}` | ADMIN, MANAGER | Delete a notice |

Request body: `{ "title": "...", "body": "...", "published": true }`.

---

## 15. Reports — ADMIN/MANAGER

`GET /api/v1/reports/overview?from=YYYY-MM-DD&to=YYYY-MM-DD`

The date range is optional (defaults to the last 30 days, maximum 366 days).
All figures are computed in the backend with SQL aggregate queries:

- `todaySessions` — today's sessions with doctor, room, type, status and
  booked/completed counts.
- `appointmentsPerDay` — appointment count per session date.
- `physicalAppointments`, `onlineAppointments` — totals by consultation type.
- `totalAppointments`, `noShows`, `cancelledAppointments`, `noShowRate`.
- `revenueLkr`, `completedPayments` — completed payments in the range.
- `topDoctors` — appointments and revenue per doctor (top five).
- `doctorRatings` — average rating and review count per doctor.

---

## 16. Feedback moderation — ADMIN/MANAGER

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/reviews/manage?status=APPROVED` | List feedback (optional status filter) |
| PATCH | `/api/v1/reviews/{id}/approve` | Approve feedback |
| PATCH | `/api/v1/reviews/{id}/hide` | Hide feedback from public ratings |
| DELETE | `/api/v1/reviews/{id}` | Delete feedback |

Public doctor reviews (`GET /api/v1/doctors/{id}/reviews`) and average
ratings only include `APPROVED` feedback.
