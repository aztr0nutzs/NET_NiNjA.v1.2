# Pixel-Perfect Spec

## Target Viewports
- 360x640 (portrait)
- 412x915 (portrait)
- 480x800 (portrait)
- 640x360 (landscape)
- 915x412 (landscape)
- 1280x720 (landscape)

## Requirements
- No overlaps/clipping/off-screen critical controls
- Stable hitboxes
- No click-blocking overlays
- Touch targets >= 44px
- Consistent spacing/alignment across target viewports

## Tolerances
- Text rasterization: 1-2px variance allowed
- Key control geometry: 0px variance

## Debug Mode
- Query param: ?debugLayout=1
- Outlines + HUD enabled
