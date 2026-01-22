# UI Wiring Map

## Entry Points
- Android entry: `com.netninja.MainActivity`
- WebView URL: `http://127.0.0.1:8787/ui/ninja_mobile.html`

## Assets
- Source assets: `web-ui/`
- Packaged assets: `android-app/src/main/assets/web-ui`
- Served by: `AndroidLocalServer` at `/ui`

## JS/CSS Bundles
- `web-ui/ninja_mobile.html` includes JS/CSS from `web-ui/`

## Native ↔ WebView Bridges
- None (WebView is plain HTML/JS)

## Logging/Diagnostics
- WebView console via `WebChromeClient` in `MainActivity`
- JS errors via injected `window.onerror` handler
