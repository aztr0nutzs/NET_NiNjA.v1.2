# NET NiNjA v1.2 — Audit Fix Prompts (Copy-Paste Ready)

Each section below is a self-contained prompt you can hand directly to an AI coding agent.

---
---

# PART A: 4 SHIP-BLOCKERS (CRITICAL)

---

## BLOCKER 1: Fix Mojibake Encoding Corruption in Main Dashboard

```
TASK: Fix Mojibake (UTF-8 encoding corruption) in web-ui/ninja_mobile_new.html

PROBLEM:
The file web-ui/ninja_mobile_new.html contains ~40+ instances of garbled
Unicode characters caused by a double-encoding issue (UTF-8 bytes interpreted
as Windows-1252). These garbled strings are visible to every user on every
page load. Examples found by grep:

  Line 808:  â€œdangerâ€    → should be "danger"
  Line 1146: Wiâ€'Fi       → should be Wi‑Fi
  Line 1146: â€¢           → should be •
  Line 1195: â€"           → should be –
  Line 1203: Whoâ€™s       → should be Who's
  Line 1232: Autoâ€'block  → should be Auto‑block
  Line 1233: Wiâ€'Fi       → should be Wi‑Fi
  Line 1298: Wiâ€'Fi       → should be Wi‑Fi
  Line 1299: rateâ€'limited → should be rate‑limited

EXACT FIX:
1. Open web-ui/ninja_mobile_new.html
2. Search and replace ALL of the following Mojibake sequences with their
   correct Unicode equivalents:
   
   â€™  →  '   (RIGHT SINGLE QUOTATION MARK U+2019)
   â€œ  →  "   (LEFT DOUBLE QUOTATION MARK U+201C)
   â€   →  "   (RIGHT DOUBLE QUOTATION MARK U+201D) — note: â€ followed by end-of-token
   â€"  →  –   (EN DASH U+2013)
   â€"  →  —   (EM DASH U+2014) — context-dependent; check surrounding text
   â€'  →  ‑   (NON-BREAKING HYPHEN U+2011)
   â€¢  →  •   (BULLET U+2022)
   â†'  →  →   (RIGHTWARDS ARROW U+2192)
   â‹®  →  ⋮   (VERTICAL ELLIPSIS U+22EE)

3. Ensure the file is saved as UTF-8 (no BOM).
4. Verify that the <meta charset="utf-8"> tag is present in the <head>.
5. Do a final grep for â€ (the byte sequence \xC3\xA2\xE2\x82\xAC) to confirm
   zero remaining instances.

SCOPE: Only web-ui/ninja_mobile_new.html. Do not change any logic or structure.
The file is 3,562 lines. Only touch the corrupted character sequences.

VERIFICATION:
- Open the file in a browser. All text should render with proper dashes,
  quotes, bullets, and arrows — no garbled â characters anywhere.
```

---

## BLOCKER 2: Replace Placeholder MP4 or Remove Dead Reference

```
TASK: Fix the placeholder ninja_claw placeholder video file in web-ui/

PROBLEM:
The file web-ui/ninja_claw placeholder video is NOT a valid MP4 video file. It is a text
file containing a Python docstring placeholder ("This is a placeholder MP4
file for the hero video…"). While no current HTML file directly references
this file via a <source> tag (the main dashboard uses ninja_header.mp4 which
is a real binary), the file exists in web-ui/ and is referenced in
documentation (docs/README_PROPOSED.md line 26, docs/patches/final_user_patch.diff
lines 44 and 51). If the patch from final_user_patch.diff is ever applied,
it will create a broken <video> element.

For reference, web-ui/ninja_mobile_new.html line 1137 uses:
  <source src="ninja_header.mp4" type="video/mp4" />
That file (ninja_header.mp4) is a real binary MP4 and works fine.

EXACT FIX (choose one):

Option A — Replace with a real video:
  1. Create or obtain a short (3-8 second) looping MP4 video suitable for
     the OpenClaw hero section.
  2. Overwrite web-ui/ninja_claw placeholder video with the real video binary.
  3. Verify the file starts with valid MP4 headers (ftyp atom).

Option B — Delete the placeholder and clean up references:
  1. Delete web-ui/ninja_claw placeholder video
  2. In docs/README_PROPOSED.md, line 26: remove or update the sentence
     "The header media now uses the ninja_claw placeholder video loop as the top hero."
  3. Optionally delete docs/patches/final_user_patch.diff if it is no longer
     needed, or update it to not reference ninja_claw placeholder video.
  4. Check if android-app/src/main/assets/web-ui/ also contains a copy
     (the patch diff attempts to rename it there) — delete if present.

SCOPE: web-ui/ninja_claw placeholder video + references in docs/ folder.
Do NOT touch ninja_header.mp4 — that file is real and working.

VERIFICATION:
- Run: file web-ui/ninja_claw placeholder video — should show "ISO Media" or file should
  not exist (depending on which option chosen).
- Grep the repo for "ninja_claw placeholder video" — all references should be either
  removed or pointing to a valid file.
```

---

## BLOCKER 3: Fix Thread-Safety Bug in Android ScanEngine

```
TASK: Fix thread-safety bug in ScanEngine.kt

PROBLEM:
File: android-app/src/main/java/com/netninja/scan/ScanEngine.kt
Lines 47-62

The scanIpRange() function creates a mutable list on line 49:
  val results = mutableListOf<Device>()

Then inside a coroutineScope, it launches multiple async coroutines on
Dispatchers.IO (line 51-62), each of which calls results.add(device) on
line 55. mutableListOf() returns an ArrayList, which is NOT thread-safe.
Multiple coroutines calling .add() concurrently will cause:
- ConcurrentModificationException
- Silent data corruption (lost entries, duplicated entries)
- IndexOutOfBoundsException from internal array resizing

The rest of the codebase correctly uses thread-safe collections
(ConcurrentHashMap in AndroidLocalServer.kt for the device cache),
making this an oversight in the extracted ScanEngine module.

EXACT FIX:
Replace line 49:
  val results = mutableListOf<Device>()
With:
  val results = java.util.Collections.synchronizedList(mutableListOf<Device>())

OR (preferred Kotlin coroutines approach) use a Mutex:

  import kotlinx.coroutines.sync.Mutex
  import kotlinx.coroutines.sync.withLock

  // At the top of scanIpRange():
  val resultsMutex = Mutex()
  val results = mutableListOf<Device>()

  // Then change line 55 from:
  results.add(device)
  // To:
  resultsMutex.withLock { results.add(device) }

FULL CONTEXT — Current code (lines 40-66):
```kotlin
  suspend fun scanIpRange(
    ips: List<String>,
    onProgress: (completed: Int, total: Int, found: Int) -> Unit,
    onDeviceFound: suspend (Device) -> Unit
  ): List<Device> = coroutineScope {
    val sem = Semaphore(config.scanConcurrency)
    val total = ips.size.coerceAtLeast(1)
    val completed = AtomicInteger(0)
    val foundCount = AtomicInteger(0)
    val results = mutableListOf<Device>()  // <-- BUG: not thread-safe

    ips.map { ip ->
      async(Dispatchers.IO) {
        sem.withPermit {
          val device = scanSingleIp(ip)
          if (device != null) {
            results.add(device)              // <-- CONCURRENT MUTATION
            foundCount.incrementAndGet()
            onDeviceFound(device)
          }

          val done = completed.incrementAndGet()
          onProgress(done, total, foundCount.get())
        }
      }
    }.awaitAll()

    results
  }
```

FIXED VERSION:
```kotlin
  suspend fun scanIpRange(
    ips: List<String>,
    onProgress: (completed: Int, total: Int, found: Int) -> Unit,
    onDeviceFound: suspend (Device) -> Unit
  ): List<Device> = coroutineScope {
    val sem = Semaphore(config.scanConcurrency)
    val total = ips.size.coerceAtLeast(1)
    val completed = AtomicInteger(0)
    val foundCount = AtomicInteger(0)
    val resultsMutex = Mutex()
    val results = mutableListOf<Device>()

    ips.map { ip ->
      async(Dispatchers.IO) {
        sem.withPermit {
          val device = scanSingleIp(ip)
          if (device != null) {
            resultsMutex.withLock { results.add(device) }
            foundCount.incrementAndGet()
            onDeviceFound(device)
          }

          val done = completed.incrementAndGet()
          onProgress(done, total, foundCount.get())
        }
      }
    }.awaitAll()

    results
  }
```

Add this import at the top of the file (after the existing coroutines imports):
  import kotlinx.coroutines.sync.Mutex
  import kotlinx.coroutines.sync.withLock

Note: Mutex is already a dependency (kotlinx-coroutines-core is in the build).
Semaphore from the same package is already imported on line 8.

SCOPE: Only android-app/src/main/java/com/netninja/scan/ScanEngine.kt

VERIFICATION:
- The file should compile with no errors.
- Run existing tests: ./gradlew :app:test
- The Mutex and withLock imports should be present.
```

---

## BLOCKER 4: Delete Dead Code Modules with XSS Vulnerability

```
TASK: Remove dead code files web-ui/api.js and web-ui/state.js

PROBLEM:
Two ES module files exist in web-ui/ that are NEVER imported by any HTML page:

1. web-ui/api.js (70 lines) — Exports api(), postJson(), sse() functions.
   The main dashboard ninja_mobile_new.html reimplements ALL of these
   functions inline in its <script> block. This file is orphaned dead code.

2. web-ui/state.js (72 lines) — Imports from api.js and contains a runScan()
   function. It uses innerHTML WITHOUT escaping on line 40-44:
   
     detail.innerHTML = `
       <h3>${d.ip}</h3>
       <div><b>MAC</b>: ${d.mac ?? "-"}</div>
       <div><b>Vendor</b>: ${d.vendor ?? "-"}</div>
       ...
     `;
   
   If a device returns a malicious vendor string like:
     <img src=x onerror=alert(document.cookie)>
   this becomes a stored XSS vulnerability. The inline dashboard JS in
   ninja_mobile_new.html does NOT have this bug — it uses textContent and
   escapeHtml() for device data rendering.

   Additionally, state.js references DOM element IDs (devicesList,
   deviceDetail, jobProg, termBody, scanBtn) that DO NOT EXIST in the
   current ninja_mobile_new.html DOM.

Neither file is imported via <script type="module" src="..."> in any HTML
file in the repository.

EXACT FIX:
1. Delete web-ui/api.js
2. Delete web-ui/state.js
3. Grep the entire repo for 'api.js' and 'state.js' to confirm no other
   file references them. Expected: zero references in any HTML or JS file
   (only documentation/audit files may mention them).

SCOPE: Delete two files only. Do NOT modify ninja_mobile_new.html or any
other file.

VERIFICATION:
- web-ui/api.js should not exist
- web-ui/state.js should not exist
- grep -r "api\.js\|state\.js" web-ui/ should return zero matches
- The dashboard should load and function identically (since these files
  were never imported)
```

---
---

# PART B: 7 MAJOR GAPS

---

## GAP 1: No Tests for server/ or core/ Modules

```
TASK: Add unit test scaffolding and initial tests for the server/ and core/ modules

PROBLEM:
The android-app module has extensive tests (ApiContractTest, IntegrationTest,
UnitTest, plus tests for cam/, config/, progress/, validation/ subpackages).
However, the server/ and core/ modules have ZERO test files. There is no
server/src/test/ or core/src/test/ directory. This means the Ktor API routing
(1,108 lines in App.kt), the SQLite persistence layer (Db.kt, DeviceDao.kt,
EventDao.kt), the ARP reader, TCP scanner, and OUI database have no automated
test coverage whatsoever.

EXACT FIX:

For core/ module:
1. Create core/src/test/kotlin/core/ directory structure
2. Add core/build.gradle.kts test dependencies (if not present):
     testImplementation("org.jetbrains.kotlin:kotlin-test")
     testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
3. Create these initial test files:
   - core/src/test/kotlin/core/persistence/DbTest.kt
     Test: Database creation, PRAGMA settings, schema migration, integrity check
   - core/src/test/kotlin/core/persistence/DeviceDaoTest.kt
     Test: Insert, update, getAll, delete, getById
   - core/src/test/kotlin/core/persistence/EventDaoTest.kt
     Test: Insert event, query history with limit
   - core/src/test/kotlin/core/alerts/ChangeDetectorTest.kt
     Test: NEW_DEVICE, DEVICE_ONLINE, DEVICE_OFFLINE, IP_CHANGED detection
   - core/src/test/kotlin/core/discovery/OuiDbTest.kt
     Test: Fallback lookup for known prefixes (B8:27:EB → Raspberry Pi)
   - core/src/test/kotlin/core/metrics/UptimeTest.kt
     Test: Uptime percentage calculation with known event sequences

For server/ module:
1. Create server/src/test/kotlin/server/ directory structure
2. Add server/build.gradle.kts test dependencies:
     testImplementation("io.ktor:ktor-server-test-host:2.3.7")
     testImplementation("io.ktor:ktor-client-content-negotiation:2.3.7")
     testImplementation("org.jetbrains.kotlin:kotlin-test")
3. Create these initial test files:
   - server/src/test/kotlin/server/RateLimiterTest.kt
     Test: Allow up to limit, reject after limit, bucket refill
   - server/src/test/kotlin/server/ServerApiAuthTest.kt
     Test: Token generation, validation, rotation, grace period
   - server/src/test/kotlin/server/ServerConfigTest.kt
     Test: Default values, env var override, port parsing

SCOPE: Create test directories and initial test files for both modules.
Use JUnit 5 or kotlin-test. Follow the pattern already established in
android-app/src/test/.

VERIFICATION:
- ./gradlew :core:test should run and pass
- ./gradlew :server:test should run and pass
```

---

## GAP 2: OpenClaw Dashboard Has 6 Placeholder Sections

```
TASK: Replace placeholder text in openclaw_dash.html with "Coming Soon" UI

PROBLEM:
File: web-ui/openclaw_dash.html contains 6 sections with raw "placeholder"
text visible to users:

  Line 652:  "Message composition + routing UI placeholder (wire to your gateway)."
  Line 849:  "Job schedule + execution log placeholder. Wire it later."
  Line 868:  "Agent capabilities list placeholder."
  Line 900:  "Mode selector placeholder."
  Line 932:  "Configuration view placeholder (separate tab like screenshot)."
  Line 966:  "Debug view placeholder (separate tab like screenshot)."

These are developer notes that should never be shown to end users.

EXACT FIX:
For each of the 6 placeholder <p> tags listed above:

1. Replace the raw placeholder text with a styled "Coming Soon" indicator.
   Example replacement for each:

   BEFORE:
   <p class="subtitle" style="margin-top:-6px;">Job schedule + execution log placeholder. Wire it later.</p>

   AFTER:
   <p class="subtitle" style="margin-top:-6px; opacity:0.5; font-style:italic;">
     Coming soon — this feature is under development.
   </p>

2. Apply the same pattern to all 6 instances. Keep the existing class and
   inline style structure. Just change the visible text and add opacity:0.5
   and font-style:italic to visually indicate it's not yet active.

SCOPE: Only web-ui/openclaw_dash.html. Only change the 6 <p> elements
identified above. Do not remove or restructure any surrounding HTML.

VERIFICATION:
- Open openclaw_dash.html in a browser
- Navigate to each section (Chat, Jobs, Skills, Mode, Config, Debug)
- Each should show "Coming soon — this feature is under development."
  in italic with reduced opacity
- grep -n "placeholder" web-ui/openclaw_dash.html should only return
  matches for <input placeholder="..."> attributes, NOT for visible body text
```

---

## GAP 3: Settings Panel is Non-Functional

```
TASK: Mark the Settings panel as non-functional or wire it to real state

PROBLEM:
File: web-ui/ninja_mobile_new.html
The Settings overlay panel displays hardcoded values:
  "App theme: Neon dark"
  "Language: English"
  "Active interface: Auto"
No settings API endpoint exists on the server. No toggle, dropdown, or input
is wired to any state. Clicking settings items does nothing. Users will expect
this to work since it's presented as a real settings screen.

EXACT FIX (choose one):

Option A — Minimal: Add "read-only" visual indicator
  1. Find the Settings panel markup in ninja_mobile_new.html
  2. Add a small banner at the top of the settings overlay:
     <div style="padding:8px 16px; background:rgba(0,255,255,0.08);
       border-radius:8px; margin-bottom:12px; font-size:13px; opacity:0.7;">
       Settings are read-only in this version.
     </div>
  3. Add cursor:default and opacity:0.6 to any settings list items to
     indicate they are not interactive.

Option B — Full: Wire to localStorage
  1. Create a settings object in localStorage with keys: theme, language,
     interface.
  2. Add click handlers to each settings row that cycle through options.
  3. Persist changes to localStorage and apply theme changes to the DOM.
  4. Reload settings from localStorage on page load.

RECOMMENDED: Option A for release. It's honest and takes 5 minutes.

SCOPE: Only web-ui/ninja_mobile_new.html, settings panel section only.

VERIFICATION:
- Open the dashboard, tap the settings gear icon
- The settings panel should show a "read-only" banner (Option A) or
  functional toggles (Option B)
```

---

## GAP 4: ArpReader and Gateway/DNS Resolution Are Linux-Only

```
TASK: Add cross-platform fallbacks for Linux-only system calls in core/
and server/

PROBLEM:
Three components read Linux-specific paths that don't exist on
Windows or macOS:

1. core/src/main/kotlin/core/discovery/ArpReader.kt (lines 8-21)
   Reads /proc/net/arp — Linux only. Returns emptyMap() on other OSes.
   ARP-based MAC address resolution silently fails.

2. server/src/main/kotlin/server/App.kt lines 1070-1089
   resolveDefaultGateway() reads /proc/net/route — Linux only.

3. server/src/main/kotlin/server/App.kt lines 1093-1106
   resolveDnsServers() reads /etc/resolv.conf — Linux only (also macOS,
   but not Windows).

EXACT FIX:

For ArpReader.kt — add Windows/macOS fallback:
```kotlin
object ArpReader {
  fun read(): Map<String, String> {
    // Linux: /proc/net/arp
    val f = java.io.File("/proc/net/arp")
    if (f.exists()) return parseLinuxArp(f)

    // Windows/macOS: shell out to 'arp -a'
    return try {
      val process = ProcessBuilder("arp", "-a").start()
      val output = process.inputStream.bufferedReader().readText()
      process.waitFor()
      parseArpCommand(output)
    } catch (e: Exception) {
      emptyMap()
    }
  }

  private fun parseLinuxArp(f: java.io.File): Map<String, String> {
    val out = mutableMapOf<String, String>()
    f.readLines().drop(1).forEach { line ->
      val parts = line.trim().split(Regex("\\s+"))
      if (parts.size >= 4) {
        val ip = parts[0]; val mac = parts[3]
        if (mac != "00:00:00:00:00:00") out[ip] = mac
      }
    }
    return out
  }

  private fun parseArpCommand(output: String): Map<String, String> {
    val out = mutableMapOf<String, String>()
    // Pattern matches both Windows and macOS arp -a output
    val pattern = Regex("""(\d+\.\d+\.\d+\.\d+)\s+([0-9a-fA-F:-]{11,17})""")
    pattern.findAll(output).forEach { match ->
      val ip = match.groupValues[1]
      val mac = match.groupValues[2].uppercase().replace("-", ":")
      if (mac != "00:00:00:00:00:00") out[ip] = mac
    }
    return out
  }
}
```

For resolveDefaultGateway() in App.kt — add Windows/macOS fallback:
  After the /proc/net/route check fails, try:
  - Windows: parse output of "route print 0.0.0.0"
  - macOS: parse output of "netstat -rn | grep default"
  - Generic: parse output of "ip route show default" (works on most Linux too)

For resolveDnsServers() in App.kt — add Windows fallback:
  After /etc/resolv.conf check fails, try:
  - Windows: parse output of "powershell -c Get-DnsClientServerAddress"
  - Or use Java's built-in: InetAddress.getAllByName(hostname) won't help,
    but you can read sun.net.dns.ResolverConfiguration (internal API) or
    just return null with a log message.

SCOPE:
- core/src/main/kotlin/core/discovery/ArpReader.kt (rewrite)
- server/src/main/kotlin/server/App.kt (modify resolveDefaultGateway and
  resolveDnsServers functions only, lines 1070-1106)

VERIFICATION:
- On Windows: ArpReader.read() should return a non-empty map when there
  are ARP entries
- On macOS: Same
- On Linux: Behavior unchanged (still reads /proc/net/arp first)
- ./gradlew :core:build and ./gradlew :server:build should succeed
```

---

## GAP 5: Empty scripts/run-dev.sh

```
TASK: Either populate scripts/run-dev.sh or delete it

PROBLEM:
File: scripts/run-dev.sh contains only a shebang line (#!/bin/bash) and
no actual commands. The repo root has dev-run-server.sh which may serve
this purpose. Having an empty script in scripts/ is misleading.

EXACT FIX (choose one):

Option A — Populate it:
  Add the following content to scripts/run-dev.sh:
```bash
#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> Building server shadow JAR..."
./gradlew :server:shadowJar

echo "==> Starting NET NiNjA server (dev mode)..."
java -jar server/build/libs/server-all.jar
```

Option B — Delete it:
  1. Delete scripts/run-dev.sh
  2. If scripts/ directory becomes empty, delete scripts/ too
     (but scripts/windows/build-installer.ps1 exists, so the dir stays)

SCOPE: scripts/run-dev.sh only.

VERIFICATION:
- If populated: bash scripts/run-dev.sh should build and start the server
- If deleted: file should not exist; no broken references elsewhere
```

---

## GAP 6: BUILD_INSTRUCTIONS.md is Incomplete

```
TASK: Complete BUILD_INSTRUCTIONS.md to cover all build targets

PROBLEM:
File: BUILD_INSTRUCTIONS.md (30 lines)
Currently only documents Android assembleDebug/assembleRelease/test/lint.
Missing entirely:
  - How to build/run the server module (./gradlew :server:run or :server:shadowJar)
  - How to build the Docker image (docker build -t netninja .)
  - How to run with Docker Compose (docker-compose up)
  - How to build the Windows installer (scripts/windows/build-installer.ps1)
  - How to run the core module tests
  - What environment variables are available (NET_NINJA_HOST, NET_NINJA_PORT,
    NET_NINJA_DB, NET_NINJA_TOKEN, NET_NINJA_ALLOWED_ORIGINS)

EXACT FIX:
Add the following sections AFTER the existing "## Notes" section:

```markdown
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

Build a Windows .exe installer using jpackage:
```powershell
.\scripts\windows\build-installer.ps1
```

Requires: JDK 21 with jpackage, and a prior :server:shadowJar build.
```

SCOPE: BUILD_INSTRUCTIONS.md only. Append new sections.

VERIFICATION:
- The file should contain sections for Android, Server, Docker, and
  Windows Installer builds.
- A new contributor should be able to follow the instructions end-to-end.
```

---

## GAP 7: Kotlin Version Catalog Mismatch

```
TASK: Sync Kotlin version in libs.versions.toml to match build.gradle.kts

PROBLEM:
File: gradle/libs.versions.toml line 2 declares:
  kotlin = "1.9.23"

File: build.gradle.kts lines 3-5 declares:
  id("org.jetbrains.kotlin.jvm") version "2.0.0" apply false
  id("org.jetbrains.kotlin.android") version "2.0.0" apply false
  id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0" apply false

The root build script hardcodes Kotlin 2.0.0 and the catalog version is
unused/dead. This is confusing for contributors and could cause issues if
someone tries to reference libs.versions.kotlin elsewhere.

EXACT FIX:
In gradle/libs.versions.toml, change line 2:

FROM:
  kotlin = "1.9.23"
TO:
  kotlin = "2.0.0"

No other changes needed. The root build.gradle.kts will continue to work
as-is. This just makes the catalog consistent.

SCOPE: gradle/libs.versions.toml line 2 only. One character change.

VERIFICATION:
- ./gradlew :server:build should succeed
- ./gradlew :app:assembleDebug should succeed
- grep 'kotlin' gradle/libs.versions.toml should show "2.0.0"
```

---
---

# PART C: 7 HIGH-RISK NON-BLOCKERS

---

## RISK 1: Auth Token in localStorage and URL Query Params

```
TASK: Harden token handling — remove token from URL query parameters

PROBLEM:
The API auth token is stored in localStorage (accessible to any JS on the
same origin) and passed via ?token=... query parameters for SSE EventSource
and WebSocket connections. Tokens in URLs appear in:
- Browser history
- Server access logs
- HTTP Referrer headers
- Proxy logs

Files affected:
- web-ui/ninja_mobile_new.html (inline JS — getToken() function, SSE/WS URLs)
- web-ui/openclaw_dash.html (similar pattern)

RECOMMENDED FIX:
1. For regular API calls: Already uses Authorization: Bearer header — this is fine.
2. For SSE (EventSource): EventSource API cannot set custom headers. Options:
   a. Use a short-lived session cookie instead of query param token
   b. Create a /api/v1/auth/session-ticket endpoint that returns a one-time
      ticket (valid for 30 seconds), then use ?ticket=... for SSE.
      Server validates and immediately invalidates the ticket.
   c. Accept the risk for SSE since it's localhost-only (document this decision)
3. For WebSocket: WebSocket API supports no custom headers either. Same
   options as SSE above.
4. Minimum: Strip the ?token= from the browser URL bar after reading it
   using history.replaceState() to keep it out of browser history:
   
   const url = new URL(location.href);
   if (url.searchParams.has("token")) {
     url.searchParams.delete("token");
     history.replaceState(null, "", url.toString());
   }

SCOPE: web-ui/ninja_mobile_new.html, web-ui/openclaw_dash.html
EFFORT: Low (history.replaceState) to Medium (session-ticket approach)
```

---

## RISK 2: No Content Security Policy (CSP) or Security Headers

```
TASK: Add security headers to Nginx config and HTML meta tags

PROBLEM:
File: deploy/nginx/conf.d/netninja.conf (48 lines)
No security headers are set. Missing:
- Content-Security-Policy
- X-Frame-Options
- X-Content-Type-Options
- Strict-Transport-Security (HSTS)
- Referrer-Policy

External resources loaded: fonts.googleapis.com, fonts.gstatic.com

RECOMMENDED FIX:
Add to the server { listen 443 ... } block in netninja.conf, after the
ssl_prefer_server_ciphers directive:

  # Security headers
  add_header X-Frame-Options "SAMEORIGIN" always;
  add_header X-Content-Type-Options "nosniff" always;
  add_header Referrer-Policy "strict-origin-when-cross-origin" always;
  add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
  add_header Content-Security-Policy "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; connect-src 'self' ws: wss:; img-src 'self' data: blob:; media-src 'self' blob:;" always;

Note: 'unsafe-inline' is required because ninja_mobile_new.html uses
inline <script> and <style> blocks. This can be tightened later when/if
JS is extracted to external files with nonces.

SCOPE: deploy/nginx/conf.d/netninja.conf only
EFFORT: Low (15 minutes)
```

---

## RISK 3: 3,562-Line HTML Monolith

```
TASK: Extract inline CSS and JS from ninja_mobile_new.html into separate files

PROBLEM:
File: web-ui/ninja_mobile_new.html is 3,562 lines containing:
- ~1,100 lines of CSS in <style> blocks
- ~500 lines of HTML structure
- ~1,800 lines of JavaScript in <script> blocks
- 3 separate inline API helper implementations across the codebase

Any UI change requires editing a single massive file. Code review is
impractical. No IDE can provide proper JS intellisense for inline scripts.

RECOMMENDED FIX:
1. Extract all <style> content to web-ui/css/dashboard.css
2. Extract the <script> content to web-ui/js/dashboard.js
3. Replace inline blocks with:
   <link rel="stylesheet" href="css/dashboard.css">
   <script src="js/dashboard.js"></script>
4. Create web-ui/js/api-client.js as the single canonical API helper,
   consolidating the 3 duplicate implementations.
5. Update the Ktor static file serving in server/App.kt if new paths
   need to be mounted (they shouldn't — already serves all of web-ui/).

SCOPE: web-ui/ninja_mobile_new.html → web-ui/css/dashboard.css +
web-ui/js/dashboard.js + web-ui/js/api-client.js
EFFORT: High (2-4 hours). Deferrable but recommended before next feature work.
```

---

## RISK 4: No Database Indexes in Android LocalDatabase

```
TASK: Add database indexes to Android LocalDatabase.kt

PROBLEM:
File: android-app/src/main/java/com/netninja/LocalDatabase.kt
The events table (line 35-40) has no index on deviceId or ts. The devices
table has no secondary indexes. Queries like:
  SELECT * FROM events WHERE deviceId=? ORDER BY ts DESC LIMIT ?
will table-scan at scale.

The server-side core/src/main/kotlin/core/persistence/Db.kt correctly
creates indexes:
  CREATE INDEX IF NOT EXISTS idx_events_device_ts ON events(deviceId, ts)
  CREATE INDEX IF NOT EXISTS idx_devices_lastSeen ON devices(lastSeen DESC)

But the Android LocalDatabase.kt does NOT.

RECOMMENDED FIX:
1. In the onCreate() method, after the CREATE TABLE statements, add:

  db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_device_ts ON events(deviceId, ts)")
  db.execSQL("CREATE INDEX IF NOT EXISTS idx_devices_lastSeen ON devices(lastSeen DESC)")

2. Bump the schema version from 4 to 5.

3. In onUpgrade(), add:
  if (oldVersion < 5) {
    runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_device_ts ON events(deviceId, ts)") }
    runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_devices_lastSeen ON devices(lastSeen DESC)") }
  }

SCOPE: android-app/src/main/java/com/netninja/LocalDatabase.kt only
EFFORT: Low (10 minutes)
```

---

## RISK 5: Aggressive Polling Without Visibility Gating

```
TASK: Gate the 20-second polling loop with Page Visibility API

PROBLEM:
File: web-ui/ninja_mobile_new.html (inline JS)
A setInterval fires 4 API requests every 20 seconds regardless of tab
visibility:
  setInterval(() => {
    refreshNetworkInfo();
    refreshDiscoveryResults();
    refreshPermissionStatus();
    refreshDebugState();
  }, 20_000);

When the tab is hidden or the phone screen is off, this wastes battery,
bandwidth, and keeps the server busy for no reason.

RECOMMENDED FIX:
Replace the polling loop with visibility-aware polling:

```javascript
let pollTimer = null;

function startPolling() {
  if (pollTimer) return;
  // Immediate refresh when becoming visible
  refreshNetworkInfo(); refreshDiscoveryResults();
  refreshPermissionStatus(); refreshDebugState();
  pollTimer = setInterval(() => {
    refreshNetworkInfo(); refreshDiscoveryResults();
    refreshPermissionStatus(); refreshDebugState();
  }, 20_000);
}

function stopPolling() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
}

document.addEventListener("visibilitychange", () => {
  if (document.hidden) stopPolling(); else startPolling();
});

// Start on load only if visible
if (!document.hidden) startPolling();
```

SCOPE: web-ui/ninja_mobile_new.html inline <script> section only.
Find the existing setInterval and replace it with the above.
EFFORT: Low (10 minutes)
```

---

## RISK 6: OuiDb Vendor Lookup Paths Are Linux-Only

```
TASK: Add cross-platform OUI database fallback in OuiDb.kt

PROBLEM:
File: core/src/main/kotlin/core/discovery/OuiDb.kt (lines 22-28)
The loadOuiPrefixes() function only searches Linux paths:
  /usr/share/nmap/nmap-mac-prefixes
  /usr/share/ieee-data/oui.txt
  /usr/share/wireshark/manuf

On Windows and macOS, none of these paths exist. The fallback is a hardcoded
3-entry map (Raspberry Pi, Cisco, Google) which is near-useless.

RECOMMENDED FIX:
1. Add Windows/macOS common paths:
   - Windows (Wireshark): C:\Program Files\Wireshark\manuf
   - Windows (nmap): C:\Program Files (x86)\Nmap\nmap-mac-prefixes
   - macOS (Homebrew Wireshark): /usr/local/share/wireshark/manuf
   - macOS (Homebrew nmap): /usr/local/share/nmap/nmap-mac-prefixes

2. Also check for a bundled OUI file in the classpath:
   val resource = OuiDb::class.java.getResourceAsStream("/oui-prefixes.txt")
   This allows shipping a small OUI database as a resource in the JAR.

Updated candidates list:
```kotlin
private fun loadOuiPrefixes(): Map<String, String> {
    // Check classpath first (bundled OUI database)
    val resource = OuiDb::class.java.getResourceAsStream("/oui-prefixes.txt")
    if (resource != null) return parseOuiStream(resource)

    val candidates = listOf(
      // Linux
      "/usr/share/nmap/nmap-mac-prefixes",
      "/usr/share/ieee-data/oui.txt",
      "/usr/share/wireshark/manuf",
      // macOS (Homebrew)
      "/usr/local/share/nmap/nmap-mac-prefixes",
      "/usr/local/share/wireshark/manuf",
      "/opt/homebrew/share/wireshark/manuf",
      // Windows
      "C:\\Program Files\\Wireshark\\manuf",
      "C:\\Program Files (x86)\\Nmap\\nmap-mac-prefixes"
    )
    val file = candidates.map { java.io.File(it) }.firstOrNull { it.exists() }
      ?: return emptyMap()
    return parseOuiFile(file)
}
```

SCOPE: core/src/main/kotlin/core/discovery/OuiDb.kt only
EFFORT: Low (15 minutes)
```

---

## RISK 7: Nginx Config Missing Rate Limiting and Timeout Tuning

```
TASK: Add rate limiting and connection timeouts to Nginx reverse proxy config

PROBLEM:
File: deploy/nginx/conf.d/netninja.conf
The current config has only basic proxy settings. Missing:
- Rate limiting (client can hammer /api/* endpoints)
- Client body size limit (default is 1MB but should be explicit)
- Connection timeouts are only 60s read/send — no keepalive timeout

While the Ktor server has its own RateLimiter, the Nginx layer should
provide defense-in-depth.

RECOMMENDED FIX:
Add at the top of the file (before the upstream block):

  # Rate limiting zone: 10 requests/sec per IP, 10MB zone
  limit_req_zone $binary_remote_addr zone=api:10m rate=10r/s;

Add inside the server { listen 443 } block:

  # Client limits
  client_max_body_size 2m;
  client_body_timeout 30s;
  client_header_timeout 30s;
  keepalive_timeout 65s;

Add inside the location / block:

  limit_req zone=api burst=20 nodelay;

SCOPE: deploy/nginx/conf.d/netninja.conf only
EFFORT: Low (10 minutes)
```

---
---

*End of prompt list. 18 tasks total: 4 blockers + 7 major gaps + 7 high-risk items.*
