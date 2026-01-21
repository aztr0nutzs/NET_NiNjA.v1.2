# Layout Bug Report

## Summary
- User-visible symptom: Screen clipped on left/right edges; layout collapsed; tap targets inconsistent.
- Viewport(s): Mobile portrait (unspecified), likely 360-412px wide.

## Repro Steps
1. Launch app and open the mobile UI.
2. Observe horizontal clipping on both sides.
3. Note small tap targets on chips/buttons.

## Findings
- Overlaps/clipping/off-screen controls: Escaped class/id attributes prevented layout CSS from applying; global neon button CSS applied large padding/margins.
- Tap targets < 44px: Buttons/chips/hamburger/inputs were 42px or less.
- Overlay blockers: None detected (CRT overlay is pointer-events:none).

## Root Cause
- What broke: HTML attributes were escaped and global neon button CSS overrode anchors.
- Why it broke: Escaped attributes bypassed `.dashboard-ui` rules; unscoped button stylesheet applied to all anchors.

## Fix
- Change summary: Fix escaped attributes, remove global neon button CSS from this page, keep layout overrides and debug mode.
- Files touched: `web-ui/ninja_mobile.html`.

## Verification
- Viewports validated: Not run (requires device/emulator checks).
- Debug mode notes: `?debugLayout=1` shows viewport HUD + issue outlines.
