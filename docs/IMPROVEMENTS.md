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

## Next (scoped, ready to build)

1. **Coach hints for hidden gestures** — pinch-overview, edge crown zoom, A–Z scrubber and
   long-press-to-pin are invisible. One-time, dismiss-on-use hint chips (prefs-flagged),
   drawn only until each gesture is used once.
2. **Picker previews** — render a miniature of each layout next to its row in the topology
   picker (reuse `computeTopologyWorldPos` at small scale) so layouts are recognisable
   before applying.
3. **Sheet scroll safety** — with pins present the sheet gains a row; on shorter screens it
   can crowd the Done button. Cap sheet height and scroll the rows region.
4. **Predictive-back polish** — the `OnBackInvokedCallback` currently consumes every Back;
   when `back()` returns false (face, face-first mode), fall through to the system instead
   of swallowing it.
5. **Instrumented smoke test** — one `androidTest` that launches MainActivity, opens the
   sheet and the picker, and asserts no crash; catches regressions adb scripts miss.
6. **Release hygiene** — versionName is still `0.1.0` while the app has grown far past it;
   bump and add a CHANGELOG section per release.

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
