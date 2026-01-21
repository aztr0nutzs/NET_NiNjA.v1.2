# UI Wiring Map

## Entry Points
- Android entry: `com.netninja.MainActivity`
- WebView URL: `file:///android_asset/web-ui/new_assets/ninja_login.html` (bootstrap) then `http://127.0.0.1:8787/ui/new_assets/ninja_login.html`
- Post-auth dashboard: `http://127.0.0.1:8787/ui/new_assets/ninja_mobile.html` when server is ready

## Assets
- Source assets: `web-ui/`
- Packaged assets: `android-app/src/main/assets/web-ui`
- Served by: `AndroidLocalServer` at `/ui` (login + dashboard)

## Media
- Header video: `web-ui/new_assets/ninja_header.mp4` loaded by `new_assets/ninja_mobile.html`

## JS/CSS Bundles
- `web-ui/ninja_mobile.html` includes JS/CSS from `web-ui/`

## Native ↔ WebView Bridges
- None (WebView is plain HTML/JS)

## Logging/Diagnostics
- WebView console via `WebChromeClient` in `MainActivity`
- JS errors via injected `window.onerror` handler
