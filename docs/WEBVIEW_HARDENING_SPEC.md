# WebView Hardening Spec

## Entry and Asset Routing
- Appassets base URL: N/A (UI served by local Ktor server)
- index.html path: http://127.0.0.1:8787/ui/new_assets/ninja_login.html
- Asset path rules: `/ui/*` serves from internal storage `files/web-ui` synced from APK assets (login + dashboard under `new_assets/`)

## Logging
- WebChromeClient console/error logging: enabled via `NetNinjaWebView` console logs
- WebViewClient load failure logging: main-frame errors logged with error code/description

## Cache Strategy
- Debug behavior: clear cache on boot; `LOAD_NO_CACHE` to prevent stale UI
- Release behavior: clear cache on boot; `LOAD_NO_CACHE` to prevent stale UI

## Settings
- JavaScript: enabled
- DOM storage: enabled
- Mixed content: not enabled (local server only)
- File/content access: defaults (no file/content access overrides)

## Lifecycle
- onCreate/onResume/onPause behavior: start foreground service, show asset login immediately, then probe server readiness and load URL
- Reload/reset rules: retry probe for up to 150s; load login after readiness or timeout

## Navigation
- Back handling: not customized
- History clearing rules: none
