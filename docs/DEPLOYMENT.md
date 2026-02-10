# Deployment Guide (Desktop/Server)

This repository ships two primary runtimes:

- Desktop/server: Ktor (Netty) + SQLite (`:server`)
- Android: embedded Ktor (CIO) + on-device SQLite (`:app`)

This document focuses on deploying the desktop/server runtime to a production host.

## Environment Variables

The desktop/server runtime reads configuration from environment variables (see `server/src/main/kotlin/server/ServerConfig.kt`).

- `NET_NINJA_HOST`
  - Default: `127.0.0.1`
  - Meaning: bind address for the HTTP server.
  - Production: use `0.0.0.0` if you intentionally want to expose the service beyond localhost.
- `NET_NINJA_PORT`
  - Default: `8787`
  - Meaning: HTTP port.
- `NET_NINJA_DB`
  - Default: `netninja.db` (relative to the working directory)
  - Meaning: path to the SQLite database file.
- `NET_NINJA_ALLOWED_ORIGINS`
  - Default: derived from `NET_NINJA_HOST`/`NET_NINJA_PORT` (localhost + host)
  - Meaning: comma-separated list of allowed CORS origins.
  - Example: `NET_NINJA_ALLOWED_ORIGINS=http://localhost:8787,http://127.0.0.1:8787`
- `NET_NINJA_TOKEN`
  - Default: auto-generated and persisted to a token file (`netninja.token`) when binding to localhost
  - Meaning: shared-secret Bearer token used to authenticate API requests.
  - Required: when binding to a non-loopback host (anything other than `127.0.0.1`/`localhost`/`::1`), the server will refuse to start without this.
  - Clients:
    - HTTP: `Authorization: Bearer <token>` (preferred) or `X-NetNinja-Token: <token>`
    - SSE/Web UI: `?token=<token>`

## Build And Run

From the repo root:

```powershell
.\gradlew :server:run
```

To run with explicit environment configuration (PowerShell):

```powershell
$env:NET_NINJA_HOST="0.0.0.0"
$env:NET_NINJA_PORT="8787"
$env:NET_NINJA_DB="C:\\data\\netninja\\netninja.db"
$env:NET_NINJA_ALLOWED_ORIGINS="https://your-ui.example.com"
.\gradlew :server:run
```

## Health, Logs, Monitoring

- Health/readiness: `GET /api/v1/system/info`
- Metrics (JSON): `GET /api/v1/metrics`
- Logs (Server-Sent Events stream): `GET /api/v1/logs/stream`

Recommended baseline monitoring:

- Alert if `GET /api/v1/system/info` fails or returns non-200 for more than N minutes.
- Alert if `/api/v1/metrics` starts returning an `error` field repeatedly.
- Capture and ship stderr/stdout logs (Logback + application logs) to your logging backend.

## Token Rotation

- Rotate token: `POST /api/v1/system/token/rotate`
- Rotation keeps the previous token valid for a short grace period to avoid instantly breaking active clients.
- The active token is persisted to `netninja.token` next to the DB file.

## Database And Migrations

The server uses SQLite via `core.persistence.Db.open(...)` (`core/src/main/kotlin/core/persistence/Db.kt`).

Current migration strategy:

- Tables are created with `CREATE TABLE IF NOT EXISTS ...`.
- New columns are added with a lightweight, idempotent startup migration:
  - The code checks `PRAGMA table_info(...)` and only runs `ALTER TABLE ... ADD COLUMN ...` when a column is missing.

Operational guidance:

- Upgrades: stop the server, backup the DB file, deploy the new build, then start the server.
- Rollbacks: stop the server, restore the previous DB backup, redeploy the previous build.

## Backup And Restore

The database is a single SQLite file.

Backup (offline, safest):

1. Stop the server process.
2. Copy the DB file from `NET_NINJA_DB` to your backup location.

Restore:

1. Stop the server process.
2. Replace the DB file at `NET_NINJA_DB` with a known-good backup.
3. Start the server process.

Notes:

- Do not rely on copying an actively-written SQLite file unless you are using SQLite's online backup APIs.
- If you containerize, mount `NET_NINJA_DB` on persistent storage.

## Performance Tuning

Most performance characteristics are driven by scan parameters and concurrency inside the runtime.

Practical knobs:

- Prefer local network deployment (low latency) if scans are frequent.
- If you expose the service beyond localhost, set conservative CORS (`NET_NINJA_ALLOWED_ORIGINS`) and front it with a reverse proxy.

Runtime behavior notes:

- Scans are parallelized with coroutines and a semaphore; increasing concurrency may increase CPU and network load.
- Timeouts are per-probe and per-endpoint; reducing them can improve responsiveness at the cost of fewer detections.

If you need explicit tunables (concurrency/timeouts as env vars), add them in a follow-up change so ops can adjust without rebuilds.

## Docker (Optional)

Basic (HTTP, no TLS):

```bash
docker compose up --build
```

With Nginx reverse proxy (HTTPS):

1. Provide TLS certs in `deploy/nginx/certs/` (see `deploy/nginx/README.md`).
2. Run:

```bash
docker compose -f docker-compose.yml -f docker-compose.proxy.yml up --build
```
