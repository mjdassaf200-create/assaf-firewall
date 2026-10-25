# ASSAF Backend API

Backend service for the ASSAF Android firewall project. It provides a starting API for accounts, per-user app-block settings, schedules, and subscription status.

## Deploy on Render

1. Create a **Web Service** for this repository.
2. Choose **Docker**.
3. Leave **Root Directory** blank.
4. Set **Dockerfile Path** to `./backend/Dockerfile`.
5. Add environment variables:
   - `JWT_SECRET`: a long random secret (at least 32 random bytes; do not share it or commit it).
   - `DATABASE_URL`: a PostgreSQL connection URL from a managed PostgreSQL database. Render's URL may start with `postgres://`; the app normalizes it.
   - `CORS_ORIGINS`: use `*` only if you understand that this API is intended for native app clients; for a web admin dashboard, specify exact origins.
6. Deploy and verify `/health` and `/docs`.

**Important:** do not deploy with a local SQLite database for real user data on ephemeral hosting. Use PostgreSQL and keep credentials in Render environment variables. A Render free web service may sleep when idle; availability and free database terms can change.

## API routes

- `GET /health` — liveness check
- `POST /v1/auth/register` — create account (password minimum 10 characters)
- `POST /v1/auth/login` — login and receive a 12-hour bearer token
- `GET /v1/me` — current account
- `GET /v1/settings` / `PUT /v1/settings` — read/save app block preferences
- `GET /v1/schedules` / `POST /v1/schedules` / `DELETE /v1/schedules/{id}` — schedules
- `GET /v1/subscription` — current subscription state (payment provider not connected yet)

Interactive API documentation is available at `/docs`.

## Security and scope

- Passwords are hashed with Argon2; raw passwords are never stored.
- User-owned settings and schedules require a bearer token and are scoped to the signed-in user.
- This is an initial backend foundation, not a finished paid production service. Before public commercial launch, add email verification, password reset, abuse/rate limiting, database migrations, backups, monitoring, and a payment provider/webhook with verified subscription events.
- This API stores preferences only. The Android VPNService still has to enforce network blocking on the device and call this API; deploying the backend does not itself block traffic.
