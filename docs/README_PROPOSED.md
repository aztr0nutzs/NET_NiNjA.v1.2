# NET NiNjA v1.2 — Quick Start (proposed)

![NET NiNjA header](web-ui/new_assets/ninja_header_readme.png)

## Overview
NET NiNjA is a local-first network dashboard that bundles a Ktor-based server, a web UI, and an Android WebView shell. The Android app boots the local server, serves the bundled web assets, and drives the dashboard flow in WebView so the same UI works on-device and in a desktop browser during development.

## Highlights
- **Local Web UI**: Serve the dashboard UI from the local server at `http://127.0.0.1:8787/ui/ninja_mobile_new.html`.
- **Android WebView shell**: Launches the bundled dashboard HTML while the server warms up, then switches to the local server for the full dashboard.
- **Local-first architecture**: Assets are synced into internal storage so WebView and desktop dev serve the same UI bundle.

## Ninja CAM (IP camera viewer tab)
![Ninja CAM header](web-ui/ninja_cam_header.png)

The Ninja CAM experience is a dedicated **Cameras** tab inside the dashboard. It loads the `net_ninja_cam.html` viewer inside the tab so IP camera monitoring stays part of the main UI surface while remaining isolated from the rest of the dashboard UI.

### What it supports
- **Stream inputs**: Add cameras by name and stream URL. HLS/MJPEG/HTTP are supported directly; RTSP is supported via a gateway (MediaMTX → HLS/WebRTC).
- **Bulk import**: Paste a multi-line list using the `Name | URL` format to onboard cameras quickly.
- **Multi-layout grid**: Switch between 1-up, 2x2, 3x3, and wall layouts for live monitoring, with load/reconnect controls for the grid.

## OpenClaw Android Companion integration
![OpenClaw Companion header](web-ui/ninja_claw.png)

The dashboard includes an **OpenClaw** tab that embeds the OpenClaw Gateway dashboard (`openclaw_dash.html`) directly inside NET NiNjA via an iframe, keeping the companion experience inside the same shell as the rest of the dashboard. The header media uses the `ninja_header.mp4` loop as the top hero.

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

## Development notes
- API base defaults to `http://127.0.0.1:8787`.
- DB default: SQLite file `netninja.db` in the server working directory.

## Repo layout (high level)
- `android-app/`: Android WebView app and local server bootstrapping.
- `server/`: Ktor server module.
- `web-ui/`: Static web assets (HTML/CSS/JS).
- `docs/`: Implementation notes and WebView hardening details.

## Contributing & security
- See `CONTRIBUTING.md` and `SECURITY.md`.
