# CORRECTIONS_APPLIED.md

Project: NET_NiNjA.v1.2
Date: 2026-02-06

## Build/Tooling Corrections
- Installed JDK 17 and JDK 21 locally (Temurin) to satisfy Gradle toolchain requirements during verification.
- Aligned Kotlin Gradle plugin to `2.0.0` (from `2.3.0`) to keep lint/analysis compatible.
- Adjusted dependency versions in `gradle/libs.versions.toml` to avoid Kotlin-metadata incompatibilities during lint/build verification.

## Test Corrections
- Android unit tests:
  - `android-app/src/test/java/com/netninja/AndroidLocalServerTest.kt` now runs under Robolectric (`@RunWith(RobolectricTestRunner::class)`, `@Config`).
- Server unit tests:
  - `server/build.gradle.kts` now includes `testImplementation(kotlin("test"))`.
  - `server/src/test/kotlin/server/HealthTest.kt` now uses an ephemeral port and readiness retry.

## Runtime Corrections (Android)
- Manifest correctness:
  - Added `MainActivity` launcher declaration in `android-app/src/main/AndroidManifest.xml`.
  - Enabled localhost cleartext via `android:networkSecurityConfig="@xml/network_security_config"`.
- Local server probe reliability:
  - `android-app/src/main/java/com/netninja/AndroidLocalServer.kt` `/api/v1/system/info` now returns a typed `@Serializable` DTO.

## Runtime Corrections (Server)
- `server/src/main/kotlin/server/App.kt` `/api/v1/system/info` now returns typed `SystemInfo` DTO to avoid mixed-type serialization.

