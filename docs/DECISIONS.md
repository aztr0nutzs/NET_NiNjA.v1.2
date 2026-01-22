# Decisions

- Use `android-app` as the Gradle `:app` module to ensure the WebView UI and local server are built and launched by default.
- Keep the placeholder `app/` directory intact to avoid destructive changes.
- Override global neon CSS in the mobile page rather than editing shared styles.
- Prefer real engine scan results with a SIM fallback to keep UI responsive offline.
- Remove `neon_btns.css` from the mobile page to avoid global anchor styling conflicts.
- Use live-only scan results and display data status explicitly in the UI.
