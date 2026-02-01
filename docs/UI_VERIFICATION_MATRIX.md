# UI Verification Matrix

## Core Paths
- Launch app → dashboard at `ninja_mobile_new.html`
- Local server starts and serves `/ui` assets
- Foreground engine service starts with `dataSync` type on Android 14+
- Header video plays from `ninja_header.mp4`
- Header video logs `HEADER_VIDEO_LOADED` or falls back to localhost if the asset fails
- SCAN toggles stream and populates device cards
- Dashboard loads from the local server when engine is online
- SCAN toggles stream and updates device cards

## Error States
- Missing assets show console errors in logcat
- File-scheme dashboards still call API over localhost (no `file:///api` failures)

## Devices
- Phone (Android 8+): Pending
- Tablet: Pending

## Results
- Manual: Not run
- Automated: Not run
