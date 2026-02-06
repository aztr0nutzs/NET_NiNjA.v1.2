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
