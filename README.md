![NET NiNjA header](web-ui/new_assets/ninja_header_readme.png)

# NET NiNjA v1.2

## Overview
NET NiNjA is a local-first network dashboard that combines a Ktor-based server, a Web UI, and an Android WebView shell. The Android app boots a local server, serves the bundled web assets, and loads the dashboard directly in a WebView so the same UI can run both on-device and in a desktop browser during development.

## What it does (functions & capabilities)
- **Local Web UI**: Serves the dashboard UI from `/ui` on the local server, with the default entry at `http://127.0.0.1:8787/ui/ninja_mobile_new.html`.
- **Android WebView shell**: Launches `ninja_mobile_new.html` from bundled assets, then switches to the local server once it is ready. This keeps the UI responsive while the server is starting and avoids file-scheme fetch limitations.
- **Asset sync for consistent UI**: The packaged Web UI assets are synced into the app’s internal storage and served by the local server so WebView always loads the same bundle.
- **Safe WebView defaults**: JavaScript and DOM storage are enabled, cache is cleared and disabled on boot, and the UI is served from a local-only origin (`127.0.0.1`).
- **Diagnostics**: WebView console errors are logged and JS errors are captured by `window.onerror` for easier debugging during development.

## Primary use cases
- **Android deployment**: Package the Web UI and local server in the Android app to run an on-device dashboard.
- **Local dev**: Run the Ktor server directly and open the Web UI in a browser for faster iteration.
- **Offline-friendly UX**: Load the bundled dashboard UI immediately so the app isn’t blank while the local server is warming up.

## Architecture at a glance
- **Server**: Ktor-based local server (runs on `127.0.0.1:8787`) that serves `/ui/*`.
- **Web UI**: Static HTML/CSS/JS under `web-ui/`, including the mobile dashboard in `web-ui/`.
- **Android app**: WebView wrapper that syncs `web-ui/` assets into internal storage and then loads the local server URLs.

## Ninja CAM (IP camera viewer tab)
![Ninja CAM header](web-ui/ninja_cam_header.png)

The Ninja CAM experience is a dedicated **Cameras** tab in the dashboard that loads the IP camera viewer via `net_ninja_cam.html` and keeps it isolated from the rest of the dashboard UI. The tab is wired into the primary bottom navigation and rendered as an embedded view so it feels native to the app shell.

### What it supports
- **Flexible stream inputs**: Add a camera by name and URL, including HLS, MJPEG, or HTTP streams. RTSP streams are supported via a gateway such as MediaMTX that converts RTSP to HLS/WebRTC for browser playback.
- **Bulk import**: Paste a multi-line list of cameras using the `Name | URL` format for quick onboarding.
- **Multi-layout grid**: Switch between 1-up, 2x2, 3x3, and wall layouts, then load or reconnect the grid for live monitoring.

## OpenClaw Android Companion integration
![OpenClaw Companion header](web-ui/ninja_claw.png)

The dashboard includes an **OpenClaw** tab that embeds the OpenClaw Gateway dashboard (`openclaw_dash.html`) directly inside NET NiNjA via an iframe, keeping the companion experience inside the same shell as the rest of the dashboard. The header media now uses the `ninja_claw.mp4` loop as the top hero.

### What it supports
- **Gateway status + profile context**: Quick connection status plus the active profile/workspace summary that mirrors the OpenClaw gateway state pane.
- **Channel visibility**: A dedicated channels table lists gateway names, status, and active session counts.
- **Agent/instance control surface**: A table of agent instances with profile/workspace metadata, plus quick actions and a detail panel for sandbox and access mode.
- **Sessions tracking**: A sessions table for monitoring active task/session entries.
- **Command runner modal**: A command dialog is available to run OpenClaw commands (e.g., `openclaw status`) from within the dashboard shell.

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
- Dashboard: `http://127.0.0.1:8787/ui/ninja_mobile_new.html`

## Repo layout (high level)
- `android-app/`: Android WebView app and local server bootstrapping.
- `server/`: Ktor server module.
- `web-ui/`: Static web assets (HTML/CSS/JS).
- `docs/`: Implementation notes and WebView hardening details.

## Contributing & security
- See `CONTRIBUTING.md` and `SECURITY.md`.
