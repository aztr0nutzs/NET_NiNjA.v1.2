# Decisions

- Use `android-app` as the Gradle `:app` module to ensure the WebView UI and local server are built and launched by default.
- Keep the placeholder `app/` directory intact to avoid destructive changes.
- Override global neon CSS in the mobile page rather than editing shared styles.
- Prefer real engine scan results with a SIM fallback to keep UI responsive offline.
- Remove `neon_btns.css` from the mobile page to avoid global anchor styling conflicts.
- Use live-only scan results and display data status explicitly in the UI.
- Always resync APK `web-ui` assets at startup and delete stale files to prevent old dashboards.
- Disable WebView cache for the local UI to keep content consistent with bundled assets.
- Start the UI flow on the dashboard using bundled assets while the local server warms up.
- When UI is loaded from `file://`, force API base to `http://127.0.0.1:8787` to avoid file-scheme fetch failures.
- Keep dashboard media/video references relative to `new_assets/` to avoid double-path lookups.
- Prefer server-hosted dashboard when the engine is ready to avoid file-scheme limitations.
