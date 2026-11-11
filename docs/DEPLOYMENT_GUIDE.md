# MediChannel Backend — Deployment Guide

Target architecture: **Render** (Spring Boot web service) + **Neon PostgreSQL**
(managed database) + **Netlify** (frontend, separate repository).

---

## 1. Prerequisites

- GitHub repository with the backend on the deployment branch.
- Neon PostgreSQL project and connection string.
- Render account.
- The frontend's exact production origin(s), e.g.
  `https://medichannel.netlify.app`.

---

## 2. Generate secrets

All three keys are Base64-encoded random bytes.

```bash
# 32-byte AES-GCM key (exactly 32 bytes after Base64 decode)
openssl rand -base64 32

# 32-byte HMAC key (exactly 32 bytes after Base64 decode)
openssl rand -base64 32

# JWT signing key (at least 32 bytes after Base64 decode)
openssl rand -base64 48
```

Store them in a password manager. Never commit them. Rotating `ENCRYPTION_KEY`
makes existing encrypted PII/PHI unreadable; plan a re-encryption migration
before rotating in production.

---

## 3. Neon database

1. Create the project/database (a pooled connection string is recommended for
   the application).
2. Use the JDBC form:
   `jdbc:postgresql://<host>/<database>?sslmode=require`
3. Keep the database user/password for `DB_USERNAME` / `DB_PASSWORD`.
4. If using a pooled endpoint, ensure Flyway can still acquire a connection and
   take its advisory lock. A direct endpoint is fine for migrations.

Flyway runs automatically on application startup (`spring.flyway.enabled=true`)
and applies `V1 … V9` in order. Never edit an applied migration; add the next
version.

---

## 4. Render web service

Create a **Web Service** from the repository.

| Setting | Value |
|---|---|
| Runtime | Java |
| Build command | `./mvnw -DskipTests clean package` |
| Start command | `java -jar target/medichannel-0.0.1-SNAPSHOT.jar` |
| Health check path | `/actuator/health` |
| Auto-deploy | Deployment branch only |

> `PORT` is injected by Render. The application reads `server.port=${PORT:8080}`
> and `server.forward-headers-strategy=framework`, so HTTPS/redirects behind
> Render's proxy resolve correctly.

### Environment variables

| Variable | Required | Example / notes |
|---|---|---|
| `DB_URL` | ✅ | `jdbc:postgresql://ep-xxx.neon.tech/medichannel?sslmode=require` |
| `DB_USERNAME` | ✅ | Neon user |
| `DB_PASSWORD` | ✅ | Neon password |
| `JWT_SECRET` | ✅ | Base64, ≥ 32 decoded bytes |
| `ENCRYPTION_KEY` | ✅ | Base64, exactly 32 decoded bytes |
| `HMAC_SECRET` | ✅ | Base64, exactly 32 decoded bytes |
| `APP_FRONTEND_ORIGIN` | ✅ | Comma-separated explicit origins, e.g. `https://medichannel.netlify.app` |
| `APP_ADMIN_EMAIL` | optional | First admin email; only used when the account does not exist |
| `APP_ADMIN_PASSWORD` | optional | ≥ 12 characters, ≤ 72 UTF-8 bytes; only used for first creation |
| `APP_REGISTRATION_AUTO_ACTIVATE` | optional | Default `true` until email verification exists |
| `DB_POOL_SIZE` | optional | Default `5` |
| `DB_MIN_IDLE` | optional | Default `1` |

`APP_FRONTEND_ORIGIN` must not be `*`. Empty or wildcard values fail startup by
design.

---

## 5. First administrator

Either:

1. Set `APP_ADMIN_EMAIL` and `APP_ADMIN_PASSWORD` for the first deploy, let the
   application create the ADMIN account, then **remove the password variable**
   (the runner never modifies an existing account), or
2. Create the ADMIN row through a controlled SQL/administrative procedure.

There is deliberately no hard-coded default administrator.

---

## 6. WebSocket configuration

- Endpoint: `wss://<backend-host>/ws`
- The STOMP `CONNECT` frame must include `Authorization: Bearer <jwt>`.
- The frontend origin must be listed in `APP_FRONTEND_ORIGIN` (same variable
  drives HTTP CORS and the WebSocket handshake allow-list).
- Do **not** attempt to send the JWT as an HTTP header during the handshake;
  browsers cannot. The server intentionally permits the handshake and checks the
  token on `CONNECT`.

---

## 7. Verification after deploy

```bash
# Health
curl -sS https://<backend-host>/actuator/health

# Login (expect 200 + accessToken)
curl -sS -X POST https://<backend-host>/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"..."}'

# Protected endpoint without token (expect 401)
curl -i https://<backend-host>/api/v1/appointments/me

# CORS preflight from the production origin (expect 200 + allow-origin)
curl -i -X OPTIONS https://<backend-host>/api/v1/doctors \
  -H 'Origin: https://medichannel.netlify.app' \
  -H 'Access-Control-Request-Method: GET'
```

Check Render logs for:

- `Successfully applied N migrations` (first deploy)
- `Admin bootstrap completed` (if configured)
- No stack traces or secret values.

---

## 8. Operational notes

- **Health:** `/actuator/health` exposes no details; only `health,info` actuator
  endpoints are exposed.
- **Logs:** do not log request bodies for auth/patient endpoints. Request IDs are
  returned as `X-Request-ID` and in error payloads.
- **Scaling:** the STOMP simple broker is in-memory. With more than one Render
  instance, move to a broker relay (RabbitMQ/Redis) so chat/notifications reach
  all instances. Run a single instance until then.
- **Backups:** enable Neon point-in-time restore. Encrypted columns rely on the
  `ENCRYPTION_KEY`; back up the key separately.
- **Migrations on deploy:** Flyway runs before the app accepts traffic;
  failed migrations abort startup (desired).
- **Rollback:** redeploy the previous build. Do not roll back the database by
  deleting migration history; write a compensating forward migration.
- **Test database isolation:** integration tests are configured for a local
  PostgreSQL at `127.0.0.1:55432`. Never point them at Neon.

---

## 9. Local development

```bash
cp .env.example .env   # if present; otherwise create .env
# fill DB_URL, DB_USERNAME, DB_PASSWORD, JWT_SECRET, ENCRYPTION_KEY, HMAC_SECRET
./mvnw spring-boot:run
```

Run tests:

```bash
docker run -d --name medichannel-v4-test \
  -e POSTGRES_PASSWORD=local_test_only_password \
  -e POSTGRES_DB=medichannel_test \
  -p 55432:5432 postgres:18
./mvnw test
```

`.env`, `.env.*`, `target/`, `.neon`, `.agents/`, `.claude/` and
`skills-lock.json` must never be committed.
