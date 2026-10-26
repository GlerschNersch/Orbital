# Watch Reborn — improvements roadmap

Living list. The navigation cleanup of 2026-10-25 (commits `c5ad977`, `f8b7158`, `c201aca`,
`a334f54`) is the baseline; items move between sections as they land.

## UX test findings (2026-10-25, full pass at 181 grid items) — ALL FIXED in 0.3.5

- **Ring layouts saturate** → FIXED: Orbitals/Dock rings generalise via pure
  `RingMath` (capacities grow ring by ring, pan to explore); the Bezel's outer
  ring is a spin-through carousel window (18 at a time, HUD shows the window).
- **Quick-menu chips clip at screen edges** → FIXED: chip targets (and their hit
  circles) clamp into the view in drawQuickChips.
- **Constellation category ribbon overlaps** → FIXED: chips flow-wrap at natural
  widths, header has a solid backdrop, star field clips below the header and
  clipped stars take no hits.
- **Magnetic Dock floats / core buried** → FIXED: home bubble sits at the
  bottom-center pivot; arcs no longer land on it; vertical drag pans the field.
- **Face time slightly overflows** → FIXED: shrink-to-fit for two-digit hours.
- **Sheet row positions shift ~40px between opens** → FIXED: no slide-in offset
  (rows are at final positions from frame one) and the height math always
  reserves the conditional Clear Pins row.
- Back from the Smart Stack exited the app once, never reproduced → HARDENED:
  Back treats a visibly-open stack/control as open even mid-animation, and the
  duplicate onKeyDown Back handler was removed (single callback path).
- Also in 0.3.5: River/Helix start higher, Fibonacci spiral widened + center
  hub on top, Nebula pills and overview labels clamp to the screen edges.
- Verified working in the same pass: all 11 layouts apply + persist; all 9 sheet
  toggles flip + persist both ways; App Search typing/filter; Edit Layout enter/exit;
  drag-place + Undo; List view + A–Z scrub; Control pod + flashlight; Smart Stack;
  system-widgets dialog; default-home settings door.

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

## Done in 0.3.5 (2026-10-25, ninth pass)

- **UX fix pass** — every finding from the full UX test pass above: ring-layout
  saturation (RingMath + Bezel carousel), chip edge-clipping, constellation
  header wrap/backdrop/clip, Dock anchoring + pan, Face time shrink-to-fit,
  sheet/picker row stability, Back-from-Stack hardening, plus River/Helix
  anchoring, Fibonacci spacing/z-order, and label edge clamps.

## Done in 0.3.4 (2026-10-25, eighth pass)

- **Quick menu blooms from the bubble** — bottom drop zones replaced by chips that
  sprout from the picked-up bubble's home spot in an arc (Unpin when pinned, Info,
  Uninstall when allowed), staggered 45ms apart with an easeOutBack overshoot pop;
  arc flips downward near the top edge; chip under the finger grows and lights.
  Release on a chip runs it; folders/widgets keep the top unpin zone.

## Done in 0.3.3 (2026-10-25, seventh pass)

- **Quick options on long-press** — App Info / Uninstall drop zones appear at the
  bottom while carrying an app (Uninstall only for uninstallable apps: non-system,
  main user); routed ahead of the unpin zone in finishPlace. Full menu unchanged
  on release-without-drag.

## Done in 0.3.2 (2026-10-25, sixth pass)

- **Bubble motion pass** — per-item world-position glide (110ms exponential damping,
  snap beyond 4.5 pitches) for placements/filing/layout switches and folder-view
  reorders; carried-bubble spring (55ms trail, 90ms scale pop to 1.12×); 240ms
  ease-out drop glide into the slot (shrinks into folders when filing). Pure helpers
  in `Motion` with unit tests; Liquid Pool opts out (own simulated motion).

## Done in 0.3.1 (2026-10-25, fifth pass)

- **Usage Growth** — bubbles scale with the recency-decayed launch score (log curve,
  0.85×–1.5×; folders follow their hottest member). Behavior toggle, on by default;
  pure math in `UsageScale` with unit tests. Verified on-device with temporarily
  seeded usage counts (real counts restored afterwards).

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

Queued by Matt, 2026-10-25 evening (after trying 1.6.0):

- **Dock polish pass** (S) — the 1.6.0 bottom dock works but looks rough: the four
  segments mix text glyphs (⌂ ▦ ☰) with one emoji (🗂), the glyph+label stack is
  cramped in the 48dp bar, and the active-segment highlight is a plain tint box.
  Redraw the four icons as one consistent drawn set, tune spacing and baselines,
  and give the active segment a cleaner treatment (consider a slightly taller bar).
- **Folder delete people can actually find** (S) — Delete/Rename ship in 1.6.0 but
  only on a custom folder's long-press Context Card; Matt went looking and never
  found them, and automatic category folders can't be deleted at all. Add
  Rename/Delete to the open folder's own header, and give category folders a
  "Hide folder" (spill the members loose and suppress the folder) so every folder
  bubble has an obvious way out.
- **Remove apps from folders, visibly** (S–M) — the only way out today is a
  classic-menu row inside the folder view; undiscoverable, and the bloom has
  none. Put "Remove from folder" on the member's press card inside folders
  (bloom + full view) and finish **Drag out of a folder** (below) so un-filing
  is a gesture, not a menu hunt.

## Ideas backlog (comprehensive pass, 2026-10-25)

Sizes: S = an hour-ish, M = an afternoon, L = a bigger project.

### Arranging & folders

- **Bloom-mode member arranging** (M) — reorder members while a folder is bloomed in
  place; 0.3.0 covers the full-screen Folder view only.
- **Drag out of a folder** (M) — drag a bloomed/folder-view member back onto the grid to
  un-file it (today filing is one-way by drag; the menu is the way back).
- **Swap instead of evict** (S) — setting: dropping on an occupied slot swaps the two
  bubbles instead of unpinning the occupant.
- **Pin to a chosen hex number** (S) — long-press menu gains "Pin to Hex #…", closing
  the last placement gap (menu only offers current/center slots today).
- **Folder rename + custom folders** (M) — folders are auto-grouped and fixed-name
  today; allow renaming and creating an empty folder to drag apps into.
- **Batch edit** (M) — in Edit Layout mode, tap several bubbles to select, then move or
  file them together.
- **Drag between grid and List view** (L) — carry a bubble across the List/Grid switch
  and drop it in the other view.

### Discoverability & learning

- **Widget pod discoverability** (S) — live widget bubbles bloom on tap, but nothing
  signals it; a subtle pulse on first grid entry could teach it.
- **Coach hints for the new gestures** (S) — hints exist for pinch/crown/scrub/pin, but
  not for drag-to-place refinements: the release zone, the undo bar, Edit Layout mode,
  filing onto folders. One hint each, same retire-on-use mechanics.
- **Guided first-run tour** (M) — three-step overlay walkthrough (grid → drag a bubble →
  sheet) shown once on a fresh install, skippable.
- **"What's new" sheet entry** (S) — after an update, the sheet shows one line naming
  the new gesture(s) until dismissed.

### Layouts & theming

- **Per-topology accents** (M) — accent colour is global; per-layout defaults would make
  layouts feel distinct (Gravity already reads as "the green one").
- **Per-topology zoom/pan memory** (M) — remember zoom/pan per layout instead of
  resetting on switch (Nebula already special-cases zoom).
- **Physics presets** (S) — Bubble Physics is one toggle; offer Calm / Lively / Wild
  spring-and-jello presets instead.
- **New topology: Constellation Drift** (M) — slow ambient drift of the whole cluster
  (positions from the existing seeded generators + a time-based offset).

### Data safety & settings

- **Backup / restore settings** (M) — export pins, folder order, group overrides and
  sheet settings to a JSON file (and import it back). Would have made the 2026-10-25
  test-wipe a non-event.
- **Stale pin cleanup** (S) — pins/order entries for uninstalled apps linger forever;
  prune them during the app-list load.
- **Reset layout action** (S) — sheet action that clears pins + folder order (with
  confirm), distinct from Clear Pins.
- **Sheet search** (S) — the sheet now has 12+ rows; a filter field at the top once it
  grows further.

### Widgets, face & his WIP

- **Finish the in-progress modules** (L) — NowPlaying, Shortcuts, ControlCenter and
  AppGroups are written but uncommitted/untested; bring them to shippable, one at a
  time, with tests before wiring UI.
- **Configurable face complications** (M) — choose which stats ring the clock hub
  (battery is fixed today).
- **More live widget kinds** (M) — e.g. next-calendar-event and step-count bubbles in
  the same live-bubble family.

### Under the hood

- **Extract a PlaceController** (M) — drag/place state and overlays out of the
  4,300-line WatchView into their own class (same pattern as NavBack/PlaceSlots).
- **Screenshot tests for sheet & picker** (M) — the two most layout-fragile surfaces;
  catch row-compression regressions automatically.
- **Instrumented coverage for 0.3.0 paths** (S) — undo restore, per-layout pin scoping,
  folder-view reorder (DragPlaceTest covers place + edit-mode today).
- **One-command ship script** (S) — build + unit + instrumented + install + state
  check as a single PC-side script, from the manual sequence used all day on
  2026-10-25 (including the prefs-restore verification).
- **Idle physics pause** (S) — stop spring integration when the grid is settled and
  untouched, for battery.

## Later (bigger bets)

- **Accessibility pass (remaining)** — DONE in 1.0.0 for bubbles, the face dock and
  Stack cards (framework `AccessibilityNodeProvider`, see `WatchAccessibility.kt`).
  Still to map: Cluster sheet rows, topology picker rows, widget pod actions and
  Control Center toggles, which draw in their own rect systems.
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
