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
- Loaded the login screen from `new_assets` before entering the dashboard.
- Restyled the login page to a blacked-out theme and removed the extra init overlay.
- Added an asset-based login bootstrap to avoid black screens while the local server starts.
- Separated login auth failures from engine-offline conditions to avoid false invalid-credential errors.
- Shifted login visuals to a black background with purple matrix rain.
