# UI Verification Matrix

## Core Paths
- Launch app → login at `new_assets/ninja_login.html` → authenticate → `ninja_mobile.html`
- Local server starts and serves `/ui` assets
- Header video plays from `ninja_header.mp4`
- Header video logs `HEADER_VIDEO_LOADED` or falls back to localhost if the asset fails
- SCAN toggles stream and populates device cards
- Login redirects to server-hosted dashboard when engine is online

## Error States
- Missing assets show console errors in logcat
- Local server down shows "Engine offline" on login instead of invalid credentials
- File-scheme dashboards still call API over localhost (no `file:///api` failures)

## Devices
- Phone (Android 8+): Pending
- Tablet: Pending

## Results
- Manual: Not run
- Automated: Not run
