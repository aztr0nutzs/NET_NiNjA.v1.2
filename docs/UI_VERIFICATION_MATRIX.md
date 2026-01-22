# UI Verification Matrix

## Core Paths
- Launch app → login at `new_assets/ninja_login.html` → authenticate → `ninja_mobile.html`
- Local server starts and serves `/ui` assets

## Error States
- Missing assets show console errors in logcat
- Local server down shows "Engine offline" on login instead of invalid credentials

## Devices
- Phone (Android 8+): Pending
- Tablet: Pending

## Results
- Manual: Not run
- Automated: Not run
