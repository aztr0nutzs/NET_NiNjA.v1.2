# REMAINING_WORK.md

Project: NET_NiNjA.v1.2
Date: 2026-02-09

## Outstanding (Non-blocking)
- Decide and reconcile the canonical `openclaw_dash.html` copy (see `INTEGRITY_AUDIT.md`).
- Manual validation: ONVIF discovery on a real Wi-Fi network with at least one ONVIF camera (emulator is not reliable for multicast).
- Reduce Kotlin compiler warnings (deprecated APIs, always-true condition) for signal-to-noise improvement.

## Environment Notes
- A physical device was connected but reported as `unauthorized` via ADB during FV-01. Emulator validation was used instead for smoke-testing.
