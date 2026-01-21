## ✅ Desktop runtime path
- Server now serves UI at `/ui/*` and API on same origin.
- `web-ui/api.js` resolves API base from `location.origin`, avoiding CORS.
- SSE log stream is implemented at `/api/v1/logs/stream`.


## ✅ Android runtime path
- Android starts a **foreground EngineService** that runs an embedded Ktor CIO server on `127.0.0.1:8787`.
- WebView loads `http://127.0.0.1:8787/ui/ninja_mobile.html` (same-origin) to avoid file→http restrictions.
- Network security config permits cleartext to localhost.


## 🟡 Android feature parity limits (expected)
- Android discovery uses a bounded ICMP reachability sweep (1..64) derived from Wi-Fi IP.
- Linux-only ARP parsing remains desktop-only.
- This keeps Android safe and non-root.


## ✅ Endpoint coverage (runtime)
- Implemented: `/api/v1/system/info`, `/api/v1/discovery/scan`, `/api/v1/discovery/results`, `/api/v1/devices/{id}`, `/api/v1/devices/{id}/history`, `/api/v1/devices/{id}/uptime`, `/api/v1/export/devices`, `/api/v1/logs/stream`.


## ✅ WebView constraints resolved
- Avoids `file:///android_asset` origin for API calls.
- Uses same-origin HTTP for UI + API.
- Enables cleartext for localhost via network security config.

