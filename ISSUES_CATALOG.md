# ISSUES_CATALOG.md

Project: NET_NiNjA.v1.2
Date: 2026-02-06

## Resolved In FV-01
- Android: WebView cleartext load to `http://127.0.0.1:8787` blocked (`net::ERR_CLEARTEXT_NOT_PERMITTED`).
  - Fix: set `android:networkSecurityConfig="@xml/network_security_config"` in `android-app/src/main/AndroidManifest.xml`.
- Android: Localhost probe endpoint returned HTTP 500 due to mixed-type map serialization.
  - Fix: `/api/v1/system/info` now responds with a `@Serializable` DTO in `android-app/src/main/java/com/netninja/AndroidLocalServer.kt`.
- Android/server: `/api/v1/system/info` previously susceptible to mixed-type serialization problems (server module).
  - Fix: use typed `SystemInfo` DTO in `server/src/main/kotlin/server/App.kt`.
- Tests: Android unit tests previously failed due to missing instrumentation registration.
  - Fix: Robolectric runner config added to `android-app/src/test/java/com/netninja/AndroidLocalServerTest.kt`.
- Server tests: missing kotlin test dependency and flaky fixed-port startup.
  - Fixes:
    - Add `testImplementation(kotlin("test"))` in `server/build.gradle.kts`
    - Make `server/src/test/kotlin/server/HealthTest.kt` choose a free port + robust web-ui dir resolution + readiness retry.

## Known Warnings (Non-blocking)
- Kotlin compiler deprecation warnings in:
  - `android-app/src/main/java/com/netninja/AndroidLocalServer.kt`
  - `android-app/src/main/java/com/netninja/MainActivity.kt`
  - `server/src/main/kotlin/server/App.kt`

