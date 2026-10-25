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

## Done in 0.2.1 (2026-10-25, third pass)

- **Drag-to-place** — long-press picks a grid bubble up; drag highlights the target hex
  (with haptic ticks); drop pins it via the existing slot-pin store (occupant evicted,
  per the established rule). Release without dragging opens the classic menu, so nothing
  was lost. Pure drop logic in `PlaceSlots` with unit tests; full gesture covered by the
  instrumented `DragPlaceTest`.

## Next

Nothing queued — pick from Later below.

## Ideas backlog (gathered 2026-10-25)

- **Drag onto a folder to file an app into it** — drop target detection already exists;
  a folder drop would call the same group-override the long-press Folder submenu uses.
- **Unpin by dragging** — drag a pinned bubble to a "Release pin" zone (or shake it off
  its slot) instead of the menu round-trip.
- **Edit mode** — one long-press enters a mode where every bubble is draggable without
  holding each time; Back/Done exits. Drag-to-place is the per-bubble version of this.
- **Undo for placement** — the "placed at Hex #N" toast gains an Undo action that
  restores the previous pin state (including the evicted occupant).
- **Folder-view arranging** — the same drag-to-place inside an open folder, reordering
  its members.
- **Per-topology pins** — a slot index means different geometry in different layouts;
  pins could optionally be remembered per layout instead of globally.

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
