![NET NiNjA header](web-ui/new_assets/ninja_header_readme.png)

# NET NiNjA v1.2

## Overview
NET NiNjA is a local-first network dashboard that combines a Ktor-based server, a Web UI, and an Android WebView shell. The Android app boots a local server, serves the bundled web assets, and drives the login→dashboard flow in a WebView so the same UI can run both on-device and in a desktop browser during development.

## What it does (functions & capabilities)
- **Local Web UI**: Serves the login and dashboard UI from `/ui` on the local server, with the default entry at `http://127.0.0.1:8787/ui/new_assets/ninja_login.html` and the post-auth dashboard at `http://127.0.0.1:8787/ui/new_assets/ninja_mobile.html`. 
- **Android WebView shell**: Launches `ninja_login.html` from bundled assets, then switches to the local server once it is ready. This keeps the UI responsive while the server is starting and avoids file-scheme fetch limitations.
- **Asset sync for consistent UI**: The packaged Web UI assets are synced into the app’s internal storage and served by the local server so WebView always loads the same bundle.
- **Safe WebView defaults**: JavaScript and DOM storage are enabled, cache is cleared and disabled on boot, and the UI is served from a local-only origin (`127.0.0.1`).
- **Diagnostics**: WebView console errors are logged and JS errors are captured by `window.onerror` for easier debugging during development.

## Primary use cases
- **Android deployment**: Package the Web UI and local server in the Android app to run an on-device dashboard.
- **Local dev**: Run the Ktor server directly and open the Web UI in a browser for faster iteration.
- **Offline-friendly UX**: Load a bundled login UI immediately so the app isn’t blank while the local server is warming up.

## Architecture at a glance
- **Server**: Ktor-based local server (runs on `127.0.0.1:8787`) that serves `/ui/*`.
- **Web UI**: Static HTML/CSS/JS under `web-ui/`, including the login flow in `web-ui/new_assets/` and the mobile dashboard.
- **Android app**: WebView wrapper that syncs `web-ui/` assets into internal storage and then loads the local server URLs.

## Quick start
### Run the server (Gradle)
```bash
./gradlew :server:run
```
Then open: `http://localhost:8787/ui/ninja_mobile_new.html`

### Run with Docker
```bash
docker-compose up --build
```
Then open: `http://localhost:8787/ui/ninja_mobile_new.html`

## Web UI entry points
- Login (default): `http://127.0.0.1:8787/ui/new_assets/ninja_login.html`
- Dashboard (post-auth): `http://127.0.0.1:8787/ui/new_assets/ninja_mobile.html`

## Repo layout (high level)
- `android-app/`: Android WebView app and local server bootstrapping.
- `server/`: Ktor server module.
- `web-ui/`: Static web assets (HTML/CSS/JS).
- `docs/`: Implementation notes and WebView hardening details.

## Contributing & security
- See `CONTRIBUTING.md` and `SECURITY.md`.
