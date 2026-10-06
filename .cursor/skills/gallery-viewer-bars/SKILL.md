---
name: gallery-viewer-bars
description: >-
  Keep Simple Gallery viewer bars on the old gradient. Never use a translucent
  frosted bar, blur, or hairline behind viewer controls. Use when editing the
  photo viewer, video viewer, bottom action bar, top shadow, More sheet, or
  any chrome behind favorite, edit, share, and delete.
---

# Viewer bars stay on the gradient

The translucid bar behind favorite, edit, share, and delete is rejected. The same treatment on the top bar is rejected. Never use this design style again.

## Use

- Bottom action bar: `@drawable/gradient_background` on `bottom_actions.xml`
- Top shadow (photo viewer, external viewer, video player): `@drawable/gradient_background_flipped`
- Video bottom strip: `@drawable/gradient_background`
- More sheet: `@drawable/extended_details_background` (solid). Not a frost plate.

The toolbar stays transparent and sits on that top gradient.

## Never

- `viewer_chrome` or any semi-transparent black plate (`#99000000` or similar)
- A white or light hairline on the bar edge
- `RenderEffect` / backdrop blur on viewer bars
- A `ViewerChrome` helper that swaps those backgrounds in

Slide motion on the bars can stay. The fill cannot change to frost, blur, or a solid translucent slab.
