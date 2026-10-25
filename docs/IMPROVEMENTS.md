# Watch Reborn — improvements roadmap

Living list. The navigation cleanup of 2026-10-25 (commits `c5ad977`, `f8b7158`, `c201aca`,
`a334f54`) is the baseline; items move between sections as they land.

## Done

- **Bubble Physics toggle wired end-to-end** — sheet row, handler, `bubble_physics` prefs
  key with startup restore. (It had been half-wired, which also broke compilation.)
- **Chrome diet** — removed the permanent Overview, Unravel and topology-cycler pills; the
  grid shows only the Cluster pill, the centred-item pill and one List/Grid pill.
- **Sectioned options sheet** — Layout / Behavior / Actions, ON/OFF chips on toggles,
  chevrons on actions, feature numbering removed.
- **Topology picker** — all 11 layouts listed with subtitles, current one checked; tap
  applies and persists. Replaces tap-to-cycle.
- **Explicit back stack** — priority lives in the pure, unit-tested `NavBack.layerToPop`;
  Back in List pops to the grid, Back in the picker returns to the sheet.
- **One search door** — the sheet action is "App Search"; List keeps its own search bar.

## Done in 0.2.0 (2026-10-25, second pass)

- **Coach hints** — one-time hint chip above the List/Grid pill teaches pinch-overview,
  long-press-to-pin and edge crown zoom; List view gets the A–Z scrubber hint. Each hint
  retires the first time its gesture is used (hooks in the scale detector, crown scrub,
  alphabet scrub and the pin setter) or when tapped; seen ids persist in `hints_seen`.
- **Picker previews** — every picker row renders a miniature of its layout's geometry
  (bounding-box normalised; Liquid Pool previews as its hex seed). `computeTopologyWorldPos`
  gained a topology-parameterised overload to make this possible.
- **Sheet fit safety** — options sheet and picker compress row height (floor 24dp) so the
  whole sheet, Done button included, fits screens shorter than the content.
- **Predictive-back polish** — when `back()` is unconsumed and Watch Reborn is not the
  default home app, Back now finishes the activity instead of being swallowed (both the
  predictive callback and the key handler).
- **Instrumented smoke test** — `SmokeTest` launches MainActivity, opens the sheet and
  asserts layered Back closes it, twice. Runs green on-device.
- **Release hygiene** — versionName `0.2.0` / versionCode 2, with `CHANGELOG.md` started.

## Done in 0.3.0 (2026-10-25, fourth pass)

The whole 0.2.1 ideas backlog, built:

- **Edit Layout mode** — sheet row or long-press menu entry; bubbles drag immediately;
  Done pill or Back exits; EDIT_MODE layer added to the NavBack priority (tested).
- **Drag onto a folder files the app into it** (same-group drops fall back to placement).
- **Unpin by dragging** — pinned bubbles show a red release zone during the drag.
- **Undo bar for placements** — place / file / unpin / folder-reorder all undoable for
  6s, restoring evicted occupants too.
- **Folder-view arranging** — long-press-drag members in the full-screen Folder view;
  order persists per folder (`forder:<group>` in the repo prefs). Bloom-mode member
  arranging is not covered — bloom members are placed via the grid.
- **Per-Layout Pins** — opt-in sheet toggle; pins stored per topology scope
  (`pin@<TOPOLOGY>:<key>`) when on, one global set when off.

## Done in 0.2.1 (2026-10-25, third pass)

- **Drag-to-place** — long-press picks a grid bubble up; drag highlights the target hex
  (with haptic ticks); drop pins it via the existing slot-pin store (occupant evicted,
  per the established rule). Release without dragging opens the classic menu, so nothing
  was lost. Pure drop logic in `PlaceSlots` with unit tests; full gesture covered by the
  instrumented `DragPlaceTest`.

## Next

Nothing queued — pick from Later below.

## Ideas backlog

Empty — the 2026-10-25 list shipped in 0.3.0. New candidates:

- **Bloom-mode member arranging** — reorder members while a folder is bloomed in place
  (0.3.0 covers the full-screen Folder view only).
- **Drag between grid and List view** — carry a bubble across the view switch.

## Later (bigger bets)

- **Accessibility pass** — the whole UI is one custom `View`; TalkBack needs a virtual
  view hierarchy (`ExploreByTouchHelper`) exposing bubbles, pills and sheet rows.
- **State-machine consolidation** — NavBack models Back priority; the next step is a
  single `NavState` reducer owning all layer transitions, retiring ad-hoc spring pokes.
- **Per-layout settings memory** — remember zoom/pan per topology instead of resetting on
  switch (Nebula already special-cases zoom).
- **Widget pod discoverability** — live widget bubbles bloom on tap, but nothing signals
  it; a subtle pulse on first grid entry could teach it.
- **Theming** — accent colour is global; per-topology accent defaults would make layouts
  feel distinct (Gravity already reads as "the green one").

## Principles that got us here

- One home per action: if it's in the sheet, it doesn't also get a pill.
- Toggles look like toggles, actions look like actions.
- Anything reachable by gesture only gets a visible door too, or a coach hint.
- Navigation rules live in pure functions with tests, not in touch handlers.
