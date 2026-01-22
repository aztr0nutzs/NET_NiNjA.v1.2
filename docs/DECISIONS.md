# Decisions

- Use `android-app` as the Gradle `:app` module to ensure the WebView UI and local server are built and launched by default.
- Keep the placeholder `app/` directory intact to avoid destructive changes.
- Override global neon CSS in the mobile page rather than editing shared styles.
- Prefer real engine scan results with a SIM fallback to keep UI responsive offline.
- Remove `neon_btns.css` from the mobile page to avoid global anchor styling conflicts.
- Use live-only scan results and display data status explicitly in the UI.
- Always resync APK `web-ui` assets at startup and delete stale files to prevent old dashboards.
- Disable WebView cache for the local UI to keep content consistent with bundled assets.
- Start the UI flow at `new_assets/ninja_login.html` and transition to the dashboard after authentication.
- Keep login styling nearly black to match the product look and avoid blue backgrounds.
- Load the login UI from bundled assets first to avoid blank screens before the local server is ready.
