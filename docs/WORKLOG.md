# Worklog

- Identified `:app` module pointing to placeholder UI while real WebView app lives in `android-app`.
- Mapped Gradle `:app` to `android-app` to build/run the correct module.
- Repaired Android manifest and added AppCompat theme resources.
- Added WebView console/error logging and JS error trap.
- Added layout overrides to neutralize global neon CSS and prevent horizontal clipping.
- Enforced 44px minimum touch targets across buttons/chips/inputs.
- Added `?debugLayout=1` HUD and runtime layout checks.
- Wired SCAN to the local engine API with a SIM fallback path.
- Fixed escaped HTML attributes that prevented `.dashboard-ui` layout rules from applying.
- Removed global neon button CSS from the mobile page to avoid anchor padding/margins.
- Switched SCAN to live-only mode and surfaced data state via a badge.
- Synced APK web assets on every launch and removed stale files to avoid old dashboards.
- Added WebView cache hardening and server readiness probing to reduce blank startup windows.
- Boot the dashboard from bundled assets while the local server warms up.
- Fixed API base resolution for file:// WebView loads so API calls hit localhost instead of file:///api.
- Corrected dashboard header video path and API base resolution for file-scheme loads.
- Added header video diagnostics/fallback and scan click logging to confirm wiring.
- Redirected the dashboard to the server URL once the engine is ready to avoid file-scheme media and API issues.
