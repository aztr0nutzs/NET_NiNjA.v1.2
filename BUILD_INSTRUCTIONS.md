# BUILD_INSTRUCTIONS.md

Project: NET_NiNjA.v1.2

## Prerequisites
- JDK 21 (recommended for local builds; toolchains in `core`/`server` request 21).
- Android SDK installed (for `:app` / `android-app` builds).

## Build, Test, Lint (FV-01 Command)
Run from repo root:

```powershell
.\gradlew clean assembleDebug assembleRelease test lint
```

On macOS/Linux (or Git Bash), the equivalent is:

```bash
./gradlew clean assembleDebug assembleRelease test lint
```

## Outputs (Local)
- Debug APK: `android-app/build/outputs/apk/debug/app-debug.apk`
- Release APK (unsigned): `android-app/build/outputs/apk/release/app-release-unsigned.apk`
- Lint report: `android-app/build/reports/lint-results-debug.html`

## Notes
- Android cleartext to localhost is allowed via `android-app/src/main/res/xml/network_security_config.xml` and manifest `android:networkSecurityConfig`.

## Server (JVM Desktop)

Build the fat JAR:
```powershell
.\gradlew :server:shadowJar
```

Run the server directly:
```powershell
.\gradlew :server:run
```

Run the fat JAR:
```powershell
java -jar server/build/libs/server-all.jar
```

The server starts on http://localhost:8787 by default.

### Environment Variables
| Variable | Default | Description |
|---|---|---|
| NET_NINJA_HOST | 0.0.0.0 | Bind address |
| NET_NINJA_PORT | 8787 | HTTP port |
| NET_NINJA_DB | netninja.db | SQLite database path |
| NET_NINJA_TOKEN | (auto-generated) | API auth token (required for non-loopback) |
| NET_NINJA_ALLOWED_ORIGINS | (none) | CORS allowed origins, comma-separated |

## Docker

Build the image:
```bash
docker build -t netninja .
```

Run with Docker Compose:
```bash
docker-compose up -d
```

Run with the Nginx TLS proxy:
```bash
docker-compose -f docker-compose.yml -f docker-compose.proxy.yml up -d
```

Ensure TLS certificates are placed in deploy/nginx/certs/ (fullchain.pem, privkey.pem).

## Windows Installer

### Full Installer (Recommended) — bundles JRE, desktop icon, start menu
Requires: JDK 21, [Inno Setup 6](https://jrsoftware.org/isdl.php)
```powershell
.\scripts\windows\build-full-installer.ps1
```
Output: `build\windows-installer\out\NetNiNjA-v1.2.0-Setup.exe`

### Lightweight Installer — jpackage (requires JDK on target machine)
```powershell
.\scripts\windows\build-installer.ps1
```

See [docs/WINDOWS_INSTALLER.md](docs/WINDOWS_INSTALLER.md) for full details.
