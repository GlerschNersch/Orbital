# Changelog

## 0.2.0 — 2026-10-25

Navigation cleanup release.

- Bubble Physics toggle wired end-to-end (sheet row, handler, persistence) — it
  had been half-wired and was breaking the build.
- Grid chrome diet: the permanent Overview, Unravel and topology-cycler pills
  are gone; the grid shows only the Cluster pill, the centred-item pill and one
  List/Grid pill.
- Options sheet grouped into Layout / Behavior / Actions, with ON/OFF chips on
  toggles and chevrons on actions.
- New Cluster Layout picker: all 11 layouts listed with subtitles, live
  geometry previews and the current layout checked. Replaces tap-to-cycle.
- Layered Back behavior driven by a unit-tested priority model (NavBack):
  Back in List view returns to the grid; Back in the picker returns to the
  sheet. Back at the face falls through to the system when Watch Reborn is
  not the default home app.
- One-time coach hints teach the gesture-only features: pinch for Overview,
  right-edge drag for crown zoom, A–Z edge scrubbing in List view, and
  long-press to pin a bubble. Each hint retires once its gesture is used (or
  when tapped), and the state persists.
- App Search is the single search door in the sheet (List keeps its own bar).
- Sheets compress their rows on short screens so they always fit.
- Instrumented launch smoke test added (`connectedDebugAndroidTest`).

## 0.1.0

Initial development builds: watch face, honeycomb cluster with 11 topologies,
List view, Smart Stack, Control Center, folders and live widget bubbles.
