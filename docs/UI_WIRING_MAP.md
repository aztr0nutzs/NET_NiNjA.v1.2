# UI Wiring Map

## Entry Points
- Android entry: `com.netninja.MainActivity`
- Foreground engine: `com.netninja.EngineService` (`foregroundServiceType="dataSync"`)
- WebView URL: `file:///android_asset/web-ui/ninja_mobile_new.html` (bootstrap) then `http://127.0.0.1:8787/ui/ninja_mobile_new.html`

## Assets
- Source assets: `web-ui/`
- Packaged assets: `android-app/src/main/assets/web-ui`
- Served by: `AndroidLocalServer` at `/ui` (dashboard)

## Media
- Header video: `web-ui/ninja_header.mp4` loaded by `ninja_mobile_new.html`

## JS/CSS Bundles
- `web-ui/ninja_mobile_new.html` includes JS/CSS from `web-ui/`

## Native ↔ WebView Bridges
- None (WebView is plain HTML/JS)

## Logging/Diagnostics
- WebView console via `WebChromeClient` in `MainActivity`
- JS errors via injected `window.onerror` handler
