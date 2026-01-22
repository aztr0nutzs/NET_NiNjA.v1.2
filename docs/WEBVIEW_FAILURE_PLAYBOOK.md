# WebView Failure Playbook

## Blank Screen / Popup
- Check console errors via `NetNinjaWebView` tag
- Verify local server readiness at `http://127.0.0.1:8787/api/v1/system/info`
- Confirm `ninja_mobile.html` is reachable at `/ui/ninja_mobile.html`

## Missing Assets
- Ensure `files/web-ui` is synced from APK assets on every launch
- Confirm assets are served under `/ui/*`

## Old UI / Stale Cache
- Verify cache mode is `LOAD_NO_CACHE` and cache cleared on boot
- Confirm asset sync overwrites stale files in `files/web-ui`

## Back Button Broken
- Verify `canGoBack` handling if added later
- Ensure WebView history is not cleared unexpectedly

## Random Reloads
- Check service lifecycle and ensure the local server stays running
- Avoid double-loading URLs or reinitializing the WebView unnecessarily
