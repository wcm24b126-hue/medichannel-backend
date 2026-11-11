# MediChannel Backend — Test Report

**Command:** `./mvnw test`
**Result:** **53 tests · 0 failures · 0 errors · 0 skipped — BUILD SUCCESS**
**Date:** 11 November 2026
**Environment:** Java 21, Spring Boot 4.1.2, PostgreSQL 18.6 (local container)

---

## 1. Summary by class

| Test class | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `MedichannelApplicationTests` | 1 | 0 | 0 | 0 |
| `RepositoryIntegrationTests` | 1 | 0 | 0 | 0 |
| `AppointmentPostgresIntegrationTests` | 1 | 0 | 0 | 0 |
| `AppointmentApiPostgresIntegrationTests` | 4 | 0 | 0 | 0 |
| `SecurityAuthorizationPostgresIntegrationTests` | 6 | 0 | 0 | 0 |
| `CorsAndWebSocketHandshakeTests` | 5 | 0 | 0 | 0 |
| `PatientDataProtectionServiceTests` | 5 | 0 | 0 | 0 |
| `UserAccountLockTests` | 4 | 0 | 0 | 0 |
| `ConsultationLifecyclePostgresIntegrationTests` (new) | 8 | 0 | 0 | 0 |
| `AdminPortalPostgresIntegrationTests` (new) | 6 | 0 | 0 | 0 |
| `WebSocketSecurityInterceptorTests` (new) | 12 | 0 | 0 | 0 |
| **TOTAL** | **53** | **0** | **0** | **0** |

Baseline was 27 tests; 26 tests were added. No existing test was altered to
weaken coverage — the only edits to existing Postgres test classes were enabling
Flyway so the new migrations are applied and schema-validated.

---

## 2. What the new tests prove

### `ConsultationLifecyclePostgresIntegrationTests` — 8 tests

1. **Full lifecycle**
   Book → check-in (`ARRIVED`) → start blocked before payment → simulated card
   payment → start (`IN_CONSULTATION`) → complete (`COMPLETED`).
   Asserts persisted status, payment status, all lifecycle timestamps, payment
   history, patient/doctor payment endpoints, and notification types. Repeated
   `arrive`/`start`/`complete`/`no-show` calls after completion return `409`
   (not `500`).
2. **Duplicate and terminal payments**
   Second payment on a paid appointment → `409`. Payment on a cancelled
   appointment → `409`.
3. **Consultation summaries**
   Non-assigned doctor → `403`; patient → `403`; assigned doctor before
   completion → `409`; successful creation returns decrypted clinical text;
   the database ciphertext does not contain the diagnosis; patient can read;
   unrelated patient → `403`; admin can read; update works; duplicate → `409`.
4. **Reviews**
   Before completion → `409`; non-owner → `403`; rating `0` → `400`; valid
   review → `201`; duplicate → `409`; patient history; public average and count;
   doctor receives `REVIEW_RECEIVED`.
5. **Chat privacy and integrity**
   Patient and assigned doctor can read history; another patient and an admin
   are rejected; messages persist with server-derived sender role/name; ordering
   is correct; third-party injection throws `ForbiddenOperationException`;
   cancelled appointment rejects new messages with `ConflictException`;
   `ConsultationAccessService` grants access only to the two participants.
6. **Notifications**
   Booking creates one unread notification; unread count; another user cannot
   mark it read (`404`); owner can; doctor arrival creates a new unread item;
   mark-all-read clears it.
7. **Doctor portal restrictions**
   Customer/admin → `403` on `/api/v1/doctor/**`; doctor profile succeeds;
   doctor cannot use customer appointment routes.
8. **Patient profile update**
   `PUT /api/v1/patients/me` returns plaintext, while `first_name_encrypted`
   and `phone_encrypted` in PostgreSQL do not contain the submitted values.

### `AdminPortalPostgresIntegrationTests` — 6 tests

1. Customers/doctors/anonymous users are rejected from all admin routes.
2. Admin can list users, read a user, read stats, list appointments, read one
   appointment, list payments and per-appointment payments.
3. Admin cannot suspend or disable their own account (`409`), and remains active.
4. Suspending a customer blocks login (`401`) until the admin reactivates them.
5. Five failed logins lock an account; admin unlock clears the lock and login
   succeeds.
6. Role filtering works; an unknown role returns `400`.

### `WebSocketSecurityInterceptorTests` — 12 tests

- STOMP `CONNECT` without a Bearer token → rejected.
- Malformed/undecodable token → rejected.
- Unknown user, suspended user → rejected.
- Valid active user → authenticated principal with the **database** role.
- `/user/queue/notifications` subscription requires authentication; authenticated
  users are allowed.
- Arbitrary topic subscriptions are denied.
- Chat subscription/send are allowed only when `ConsultationAccessService`
  grants access.
- Unauthenticated/non-participant chat sends are denied.
- Arbitrary `SEND` destinations are denied.

---

## 3. How to reproduce

Prerequisites:

```bash
# Disposable local PostgreSQL used by the Postgres integration tests
docker run -d --name medichannel-v4-test \
  -e POSTGRES_PASSWORD=local_test_only_password \
  -e POSTGRES_DB=medichannel_test \
  -p 55432:5432 postgres:18
```

Then:

```bash
./mvnw test
```

Flyway creates the schema from V1–V9 on the empty database. Hibernate runs with
`ddl-auto=validate`, so any entity/migration mismatch fails the run.

The test database is **never** Neon/production. All credentials in test
configuration are local placeholders.

---

## 4. Coverage notes and gaps

- Tests are behavioural integration tests, not line-coverage reports. They
  exercise the real security filter chain, controllers, services, repositories,
  Flyway schema and PostgreSQL constraints.
- Not covered by automated tests:
  - A real browser/STOMP client handshake (interceptors are unit-tested;
    `/ws` HTTP handshake is covered as not-401/403).
  - Real payment gateway callbacks (no gateway exists).
  - Email delivery (no provider exists).
  - Concurrency racing on duplicate payments/reviews beyond database
    constraints and `DataIntegrityViolationException` handling.
- CI must provide the PostgreSQL container above, or the hard-coded test URL
  should be replaced with environment-driven properties.
