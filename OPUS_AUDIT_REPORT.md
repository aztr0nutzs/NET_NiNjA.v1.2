# OPUS AUDIT REPORT — Net Ninja v1.2

**Auditor**: OPUS (Senior Software Auditor & Release Gatekeeper)  
**Date**: 2026-02-10  
**Scope**: Full end-to-end inspection of every source file, build config, doc, and asset  
**Commit branch**: `main`

---

## 1️⃣ EXECUTIVE STATUS (1-Minute Read)

### Overall Project Status: **NEAR-READY**

Net Ninja v1.2 is a local-first network discovery dashboard with a Ktor server backend, an Android WebView app, and a static web UI. The architecture is sound, the feature set is real and functional, and the code demonstrates deliberate engineering decisions around auth, error handling, and resilience. However, the project has **encoding corruption visible to end-users**, a **placeholder media file shipped as real content**, **dead code modules that could mislead maintainers**, a **thread-safety bug in the Android scan engine**, and **no test coverage for two of three Gradle modules**. These issues individually range from embarrassing to crash-inducing; collectively they block a confident public release.

**Who should care right now:**
- **Dev lead**: Fix the Mojibake encoding, placeholder MP4, thread-safety bug, and dead code before merge.
- **PM**: Feature completeness is high, but OpenClaw dashboard has several placeholder UI sections that promise functionality not yet backed by a real backend.
- **Release engineer**: The build system works (Dockerfile, Gradle, Shadow JAR), but `scripts/run-dev.sh` is empty and `BUILD_INSTRUCTIONS.md` is incomplete.
- **End user**: The core network scanning and device management will work. The OpenClaw and Settings sections will feel unfinished.

---

## 2️⃣ PROJECT SCORECARD

| Area | Score (0–10) | Status | Notes |
|---|---|---|---|
| **Architecture** | 8 | Good | Clean 3-module Gradle structure (core → server → android-app). Ktor + SQLite + WebView is appropriate for a local-first tool. OpenClaw gateway is well-isolated. |
| **Code Quality** | 6 | Mixed | Server and core modules are clean. `AndroidLocalServer.kt` (1,575 lines) and `App.kt` (1,108 lines) are monoliths. `ninja_mobile_new.html` (3,562 lines inline) is a maintenance burden. Thread-safety bug in `ScanEngine.kt`. |
| **Completeness** | 6 | Gaps | Core scanning, device management, camera viewer, and 3D map are complete. OpenClaw dashboard has 5+ placeholder UI sections. Settings panel is purely cosmetic. `api.js`/`state.js` are dead code. |
| **Build / Run Reliability** | 7 | Good | Gradle builds, Docker works, Shadow JAR configured. Kotlin version mismatch between catalog (1.9.23) and root build script (2.0.0) is sloppy but overridden correctly. CI exists. |
| **Error Handling** | 8 | Strong | `catching`/`catchingSuspend`/`retryTransientOrNull` patterns used consistently across server and Android. Graceful shutdown, DB integrity checks, error-mapping in API responses. |
| **Docs / Readability** | 5 | Incomplete | `DEPLOYMENT.md` is solid. `BUILD_INSTRUCTIONS.md` is minimal (missing server/core modules). `core/README.md` says "Core engine placeholder". 20+ audit/report markdown files clutter the repo root. |
| **Maintainability** | 5 | Fragile | 3 copies of API helper functions. 3,562-line HTML monolith. Global JS state. Encoding corruption baked into source files. No module-level tests for `server/` or `core/`. |
| **Release Readiness** | 5 | Blocked | Encoding corruption, placeholder MP4, thread-safety bug, dead code must be resolved. See Section 3. |

---

## 3️⃣ CRITICAL ISSUES (SHIP-BLOCKERS)

### 🔴 C-01: Mojibake Encoding Corruption in Main Dashboard

- **📍 Where**: [web-ui/ninja_mobile_new.html](web-ui/ninja_mobile_new.html) — throughout (~40+ instances)
- **💥 Why it blocks**: User-facing text displays as garbled characters: `â€'`, `â€"`, `â€™`, `â†'`, `â‹®` instead of proper Unicode dashes, quotes, and arrows. This is immediately visible to every user and makes the app look broken.
- **🛠 Fix**: Re-encode the file as UTF-8 (no BOM). Search-replace all Mojibake sequences with their correct Unicode equivalents (`–`, `—`, `'`, `→`, `⋮`). Ensure the server sends `Content-Type: text/html; charset=utf-8`.
- **⏱ Urgency**: **Immediate**

### 🔴 C-02: Placeholder Video File Shipped as Real Content

- **📍 Where**: [web-ui/ninja_claw placeholder video](web-ui/ninja_claw placeholder video)
- **💥 Why it blocks**: This file is a **text file** containing a Python docstring ("This is a placeholder MP4 file for the hero video…"). It is not a valid MP4. The OpenClaw tab hero section references it via `<video>` tag. Result: broken media element visible to users. `ninja_header.mp4` is a real binary and works fine.
- **🛠 Fix**: Either replace with a real video file or remove the `<video>` element and fall back to a static image.
- **⏱ Urgency**: **Immediate**

### 🔴 C-03: Thread-Safety Bug in Android ScanEngine

- **📍 Where**: `android-app/src/main/java/com/netninja/scan/ScanEngine.kt` — `results` mutable list
- **💥 Why it blocks**: The `results` list is a `mutableListOf<Device>()` mutated from multiple coroutines without synchronization. Under concurrent scan operations (which is the normal operating mode with semaphore-limited parallelism), this causes `ConcurrentModificationException` or data corruption. The rest of `AndroidLocalServer.kt` correctly uses `ConcurrentHashMap` for its device cache, making this inconsistency an oversight.
- **🛠 Fix**: Replace `mutableListOf()` with `ConcurrentLinkedQueue()` or protect mutations with a `Mutex`.
- **⏱ Urgency**: **Immediate**

### 🔴 C-04: Dead Code Modules (`api.js`, `state.js`) Create Maintenance Hazard

- **📍 Where**: [web-ui/api.js](web-ui/api.js) (~70 lines), [web-ui/state.js](web-ui/state.js) (~72 lines)
- **💥 Why it blocks**: These ES modules are never imported by `ninja_mobile_new.html`. The main dashboard reimplements all API helpers inline. Additionally, `state.js` contains an **XSS vulnerability** (`innerHTML` without escaping device data). If a future developer imports `state.js` thinking it's the canonical API layer, they inherit the XSS bug. Shipping dead code with known vulnerabilities is unacceptable.
- **🛠 Fix**: Either (a) delete both files and document that API logic lives inline in the dashboard, or (b) refactor the dashboard to import them and fix the XSS bug. Option (b) is architecturally preferable.
- **⏱ Urgency**: **Immediate**

---

## 4️⃣ MAJOR GAPS & MISSING PIECES

### Gap 1: No Tests for `server/` or `core/` Modules

- **Evidence**: No `server/src/test/` directory found. No `core/src/test/` directory found. The Android app has a good test structure (`AndroidLocalServerTest.kt`, `AndroidLocalServerApiContractTest.kt`, `AndroidLocalServerIntegrationTest.kt`, plus sub-package tests), but the JVM-side server and core library — which contain the network scanning engine, persistence layer, and all API routing — have **zero test coverage**.
- **Impact**: Any refactoring of `App.kt` (1,108 lines), `Db.kt`, `DeviceDao.kt`, or the scanning logic has no safety net. Regressions will reach production undetected.

### Gap 2: OpenClaw Dashboard Has Multiple Placeholder Sections

- **Evidence**: `openclaw_dash.html` contains these explicit placeholder labels:
  - "Job schedule + execution log placeholder. Wire it later." (line ~849)
  - "Agent capabilities list placeholder." (line ~868)
  - "Mode selector placeholder." (line ~900)
  - "Configuration view placeholder." (line ~932)
  - "Debug view placeholder." (line ~966)
  - "Message composition + routing UI placeholder (wire to your gateway)." (line ~652)
- **Impact**: Users navigating to these sections will see "placeholder" text. This is acceptable for an internal/beta build but not for a public release.

### Gap 3: Settings Panel is Non-Functional

- **Evidence**: The settings overlay in `ninja_mobile_new.html` displays hardcoded values: `"App theme: Neon dark"`, `"Language: English"`, `"Active interface: Auto"`. No settings API exists. No toggle, dropdown, or input wired to state. It's a visual mockup.
- **Impact**: Users expect this to work. It currently does nothing.

### Gap 4: ArpReader and Gateway/DNS Resolution Are Linux-Only

- **Evidence**: `ArpReader.kt` reads `/proc/net/arp`. `App.kt:resolveDefaultGateway()` reads `/proc/net/route`. `App.kt:resolveDnsServers()` reads `/etc/resolv.conf`. **None of these paths exist on Windows or macOS.**
- **Impact**: The desktop server (via `DesktopLauncher.kt`) and Docker deploy target multiple platforms, but ARP-based MAC address resolution, gateway detection, and DNS server display will silently return empty results on non-Linux hosts. The Android app has its own implementations that use Android APIs, so this only affects the JVM server module.

### Gap 5: `core/README.md` Is a Placeholder

- **Evidence**: The file contains only: "Core engine placeholder"
- **Impact**: Minor, but contributes to the impression of an unfinished project. The core module is actually well-implemented.

### Gap 6: `scripts/run-dev.sh` Is Empty

- **Evidence**: The file contains only `#!/bin/bash` — no actual commands.
- **Impact**: `dev-run-server.sh` (repo root) may serve this purpose instead, but having a dead script in `scripts/` is misleading.

### Gap 7: `BUILD_INSTRUCTIONS.md` Is Incomplete

- **Evidence**: Only documents Android `assembleDebug`/`assembleRelease`/`test`/`lint`. Makes no mention of `:server:run`, `:server:shadowJar`, Docker builds, or the Windows installer script.
- **Impact**: A new contributor cannot build or run the server from this doc alone.

---

## 5️⃣ NON-BLOCKING BUT HIGH-RISK AREAS

### Risk 1: Auth Token in localStorage and URL Query Params

- **Files**: `ninja_mobile_new.html` (inline JS), `api.js`, `openclaw_dash.html`
- **Details**: The API token is stored in `localStorage` and passed via `?token=...` query parameters for SSE and WebSocket connections. Tokens in URLs appear in browser history, server access logs, referrer headers, and proxy logs. localStorage is accessible to any JS running on the same origin.
- **Mitigation path**: Use `httpOnly` cookies for session auth, or at minimum use the `Authorization` header exclusively and avoid query-param tokens.

### Risk 2: No Content Security Policy (CSP)

- **Files**: All HTML files, nginx config
- **Details**: No `Content-Security-Policy` header or meta tag. External fonts loaded from `fonts.googleapis.com`. `innerHTML` used throughout (with escaping in most places, but fragile). No `X-Frame-Options`, `X-Content-Type-Options`, or `Strict-Transport-Security` headers in nginx config.
- **Impact at scale**: XSS exploitation surface is wider than necessary.

### Risk 3: 3,562-Line HTML Monolith

- **File**: `ninja_mobile_new.html`
- **Details**: ~1,100 lines CSS + ~500 lines HTML + ~1,800 lines JS, all inline. Three separate API helper implementations exist across the repo. This file is extremely difficult to review, test, or modify without regressions.
- **Impact**: Any UI change is high-risk. Code review is impractical at this file size.

### Risk 4: Kotlin Version Catalog Mismatch

- **Files**: `gradle/libs.versions.toml` (declares `kotlin = "1.9.23"`), `build.gradle.kts` (applies `version "2.0.0"`)
- **Details**: The version catalog declares Kotlin 1.9.23 but the root build script hardcodes 2.0.0. The catalog version is effectively dead. This won't break the build (root plugins win), but it's confusing and could cause issues if someone tries to use `libs.versions.kotlin` elsewhere.
- **Fix**: Update `libs.versions.toml` to `kotlin = "2.0.0"` for consistency.

### Risk 5: No Database Indexes in Android LocalDatabase

- **File**: `LocalDatabase.kt`
- **Details**: The `events` table has no index on `deviceId` or `ts`. The `devices` table has no secondary indexes. Queries like `SELECT * FROM events WHERE deviceId=? ORDER BY ts` will table-scan at scale. The server-side `Db.kt` correctly creates `idx_events_device_ts` and `idx_devices_lastSeen`, but the Android version does not.
- **Impact**: Performance degradation on devices with many logged events.

### Risk 6: OuiDb Vendor Lookup Paths Are Linux-Only

- **File**: `core/src/main/kotlin/core/discovery/OuiDb.kt`
- **Details**: Looks for OUI databases at `/usr/share/nmap/nmap-mac-prefixes`, `/usr/share/ieee-data/oui.txt`, `/usr/share/wireshark/manuf`. Falls back to a hardcoded 3-entry map. On Windows/macOS, MAC vendor resolution will almost always return `null`.

### Risk 7: Aggressive Polling Without Visibility Gating

- **File**: `ninja_mobile_new.html` (inline JS)
- **Details**: `setInterval(() => { refreshNetworkInfo(); refreshDiscoveryResults(); refreshPermissionStatus(); refreshDebugState(); }, 20_000)` — fires 4 API requests every 20 seconds regardless of whether the tab is visible or the app is in the background. No backoff, no `document.visibilityState` check.
- **Impact**: Unnecessary battery drain on mobile, wasted bandwidth, and server load.

---

## 6️⃣ WHAT IS NEEDED ASAP (PRIORITIZED)

### Must-Do Immediately (Before Release)

| # | Item | Why |
|---|---|---|
| 1 | Fix Mojibake encoding in `ninja_mobile_new.html` | Garbled text visible to every user on every page load |
| 2 | Replace `ninja_claw placeholder video` with real video or remove `<video>` reference | Broken media element in OpenClaw tab |
| 3 | Fix thread-safety bug in `ScanEngine.kt` | Data corruption / crash under normal concurrent scanning |
| 4 | Delete or fix `api.js`/`state.js` (kill dead code + XSS) | Shipping vulnerable dead code is unacceptable |
| 5 | Sync Kotlin version in `libs.versions.toml` to `2.0.0` | Eliminate confusion for contributors |

### Should-Do Before Next Milestone

| # | Item | Why |
|---|---|---|
| 6 | Add unit tests for `server/` and `core/` modules | Zero test coverage on the JVM server is high-risk for any refactor |
| 7 | Add DB indexes in `LocalDatabase.kt` (Android) | Event queries will slow to a crawl as history grows |
| 8 | Add `Page Visibility API` gating to the polling loop | Prevent battery drain when tab is hidden |
| 9 | Complete `BUILD_INSTRUCTIONS.md` (server, Docker, installer) | New contributors can't onboard without it |
| 10 | Add security headers to nginx config | `X-Frame-Options`, `X-Content-Type-Options`, `Strict-Transport-Security` |
| 11 | Wire OpenClaw placeholder sections or label them as "Coming Soon" | Users seeing raw "placeholder" text is unprofessional |
| 12 | Add cross-platform fallbacks for ArpReader, gateway/DNS resolution | Desktop server on Windows/macOS silently loses features |

### Can Safely Defer

| # | Item | Why |
|---|---|---|
| 13 | Break up `ninja_mobile_new.html` into separate CSS/JS files | Significant refactor; works as-is |
| 14 | Break up `AndroidLocalServer.kt` and `App.kt` into smaller classes | Works correctly; readability improvement only |
| 15 | Make Settings panel functional | Cosmetic feature, not core functionality |
| 16 | Token auth hardening (httpOnly cookies, remove URL param) | Current token model is adequate for localhost-only threat model |
| 17 | Add CSP headers | Defense-in-depth; not critical for a local-only tool |
| 18 | Delete `scripts/run-dev.sh` (empty file) | Cleanup only |

---

## 7️⃣ WHAT IS ACTUALLY SOLID (NO FLUFF)

### Authentication & Authorization

The `ServerApiAuth` (server) and `LocalApiAuth` (Android) implementations are well-designed:
- Auto-generated 256-bit tokens via `SecureRandom`
- Token rotation with grace period for in-flight requests
- Rate limiting on failed auth attempts (15/min burst) and sensitive endpoints (token rotate: 2 per 10 min)
- Non-loopback bind requires explicit `NET_NINJA_TOKEN` environment variable — won't accidentally expose an unauthenticated server

### Error Handling Patterns

The `catching`/`catchingSuspend`/`retryTransientOrNull` patterns in both `App.kt` and `AndroidLocalServer.kt` are consistent, well-structured, and prevent unhandled exceptions from crashing the server. Every API route is wrapped. CancellationExceptions are correctly re-thrown. Structured logging with `where`, `fields`, and stack traces.

### Database Resilience (Server `Db.kt`)

- PRAGMA `integrity_check` on open — fails fast on corruption
- WAL mode with `busy_timeout=5000` for concurrent access
- Schema versioning with tracked migrations
- `SAVEPOINT`/`ROLLBACK` transaction safety for migration DDL
- Proper indexes on `devices(lastSeen)` and `events(deviceId, ts)`

### Android Asset Pipeline

- `AssetCopier.kt` does atomic file copy (write `.tmp`, then rename) with stale-file pruning
- `syncWebUiAssets` Gradle task ensures web-ui is bundled into APK assets from the single source of truth
- WebView falls back from server URL to bundled assets during boot with a 20-second bootstrap window

### Android `ServerConfig.kt`

Excellent configuration management: all magic numbers documented with rationales, SharedPreferences-backed, runtime-overridable, `resetToDefaults()` provided.

### Android `InputValidator.kt`

Thorough input validation: CIDR, IP, MAC, timeout, URL, port, payload size limits. URL validator blocks `localhost`/`127.0.0.1`/`::1` to prevent SSRF. Clean error messages with specific failure reasons.

### Docker & Deployment

Clean multi-stage Dockerfile (Gradle build → JRE runtime). Proper `docker-compose.yml` with `unless-stopped` restart policy, volume persistence, and environment variable passthrough. Nginx proxy config has TLS 1.2+ enforcement, HTTP→HTTPS redirect, WebSocket upgrade support.

### 3D Discovery Map (`ninja_nodes.html`)

Impressive pure-Canvas 3D visualization: 6 color themes, 5 layout modes, HUD overlays, touch gestures, momentum physics. 1,602 lines but self-contained with no external dependencies beyond what the parent page provides.

### OpenClaw WebSocket Protocol

Clean message-based protocol: `HELLO` → `HEARTBEAT` → `RESULT` lifecycle. `ConcurrentHashMap` for session tracking. Snapshot broadcasts on every state change. Proper `CancellationException` handling in coroutine scope.

---

## 8️⃣ FINAL VERDICT

### Can this project be shipped?

**Not yet.** Four issues block release:
1. Mojibake encoding corruption in the main dashboard (visual defect on every page load)
2. Placeholder MP4 file (broken media element)
3. Thread-safety bug in `ScanEngine.kt` (potential crash)
4. Dead code with XSS vulnerability (`state.js`)

**Estimated fix time**: 2–4 hours for an experienced developer to address all four blockers.

### Can this project be used by others?

**Yes, with caveats.** The core functionality — network scanning, device discovery, device management, camera viewer, 3D map — works. A user willing to ignore the encoding artifacts and avoid the OpenClaw placeholder sections can derive value from the tool today.

### Can this project be built upon safely?

**Partially.** The architecture (Gradle multi-module, Ktor, SQLite, WebView) is sound and extensible. The auth and error-handling patterns are worth preserving. However:
- The 3,562-line HTML monolith makes UI changes high-risk
- The lack of server/core tests makes backend changes high-risk
- The 3 duplicate API helper implementations will drift over time
- The Linux-only assumptions in `core/` limit cross-platform extensibility

**Bottom line**: Fix the four blockers, add server/core tests, and this project is ready for a confident public release.

---

*End of audit. All sections completed. No vague language remains. Every finding is traceable to a specific file or behavior observed during inspection.*
