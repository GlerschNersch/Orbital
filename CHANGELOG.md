# Changelog

## 1.9.1 — 2026-10-25

- **TalkBack coverage is complete** — the three surfaces that still had
  no accessibility nodes now expose them, all activating through the
  real tap handler: the topology picker (each design, the current one
  announced, plus Back), the widget pod's buttons (spoken labels
  captured from the pod's own draw — "Play", "Previous track", "1
  minute timer preset", … — plus the Close header), and the Control
  Center's tiles (Wi-Fi, Bluetooth, Flashlight, Sound, Folders,
  Notifications, volume and more, each spoken with its live state).

## 1.9.0 — 2026-10-25

- **The Cluster Options sheet is half the size** — it had grown to 17
  rows. It now opens compact with just the essentials: Cluster Topology,
  Grid Layout, Bubble Physics, Folder Open Style, Category Clustering,
  App Search and Edit Layout. A "More Options" row at the bottom expands
  the rest in place (Home Screen, Per-Layout Pins, Usage Growth,
  Mini-Icon Tap, Live Grid Widgets, System Widgets, Default Launcher,
  Clear Pins) and collapses back with "Show Fewer". Hidden Folders still
  surfaces on its own whenever folders are hidden. Row subtitles were
  shortened throughout (the ON/OFF chip already says the state), and the
  sheet is retitled "Cluster Options".

## 1.8.1 — 2026-10-25

- **Fix: folder members in Bloom mode can be long-pressed again** — the
  bloom registered its member bubbles without slot indices, so a
  long-press on a member inside a bloomed folder never picked it up:
  the member Context Card ("Remove from …"), drag-to-rearrange and
  drag-out-to-remove only worked in the full-screen Folder view. Bloom
  members now carry their member index like the full view, the bloom
  draws the carried member (with origin placeholder and hover ring)
  while dragging, and drop targeting inside a folder only considers
  that folder's members. Found by live-testing 1.8.0 on a phone in
  Bloom mode; the full-screen view was unaffected.

## 1.8.0 — 2026-10-25

- **Battery: the pulse loop only runs when it can be seen** — the ~75ms
  redraw cycle that animates notification pulse rings and the icon jiggle
  now only runs while a badged bubble is actually on screen. An unread
  notification sitting on an off-screen bubble no longer keeps the whole
  launcher redrawing at full tilt; the UI falls back to its 1-second tick.
- **TalkBack coverage grows** — the Context Card's rows and the Cluster
  sheet's rows (with toggle states spoken, e.g. "Bubble Physics, on") are
  now accessibility nodes, focusable and activatable like the grid
  bubbles. Still unmapped: the topology picker, widget pod and Control
  Center.
- Verified along the way: the watch face at a two-digit hour (10:43) —
  the shrink-to-fit holds, the full time with seconds fits the screen.

## 1.7.0 — 2026-10-25

- **Dock polish** — the four dock icons are now drawn in one line style
  (clock, hex cluster, list, stacked cards) instead of mixed font glyphs
  and an emoji; cleaner active-segment highlight and spacing.
- **Folder actions where you'd look for them** — an open folder's header
  now carries its own buttons: ✎ Rename and 🗑 Delete on custom folders,
  and Hide on automatic category folders (members spill loose; restore
  from the Cluster sheet's "Hidden Folders" row, or Undo). Deleting the
  folder you're standing in closes it cleanly.
- **Getting apps out of folders** — long-pressing a member inside a
  custom folder now opens its card with "Remove from …" as a first-class
  row, and dragging a member to the "✕ Remove from folder" zone at the
  top un-files it.

## 1.6.0 — 2026-10-25

- **Bottom navigation dock** — the lone List/Grid pill becomes a compact
  four-segment dock (Face · Cluster · List · Stack) in the same spot:
  Face and the Smart Stack are now one tap from anywhere instead of
  gesture-only, the current mode's segment is lit in the accent, and
  tapping Cluster while already in the cluster recentres the field (the
  recenter that used to hide behind the Back button).
- **Folder management on the Context Card** — long-pressing a custom
  folder now shows Rename Folder and (in red, last) Delete Folder
  directly on its card, instead of burying both under More Options.
  Removing an app stays one long-press away inside the folder.

## 1.5.1 — 2026-10-25

- **Folder swallow animation** — filing an app into a folder (or creating
  one) now reads as being eaten: the carried bubble accelerates into the
  folder and shrinks to a speck over 300ms, and the folder does a quick
  "gulp" swell when it lands. Folder creation previously cancelled its
  drop glide entirely (the target app's key is replaced by the folder's
  in the rebuild) and the name prompt covered the moment — the swallow
  now flies to a fixed endpoint, and the prompt waits ~300ms so the
  animation finishes first.

## 1.5.0 — 2026-10-25

- **Custom folders** — until now folders were only the fixed auto
  categories. Now you can make your own:
  - **Create:** drag an app onto another app and hold it there for a
    moment — the target lights up with "Release to create folder";
    release, type a name, done. Dragging onto an app that already lives
    in a custom folder just adds yours to that folder instead.
  - **Add:** drag any app onto a custom folder bubble (or onto a member
    app) — it moves in, leaving whatever folder it was in before.
  - **Remove:** open the folder, long-press a member, "Remove from …".
    A folder left with one app dissolves back to loose apps.
  - **Manage:** a custom folder's long-press menu gains Rename and
    Delete (its apps stay on the grid). Members reorder by drag inside
    the folder, folders pin like any bubble, and custom folders show in
    both the folder grid and the flat grid. Membership rules (one folder
    per app, dissolve below two) live in a pure `CustomFolders` helper.

## 1.4.0 — 2026-10-25

- **Zero-G Billiards actually looks like a pool now** — the tank was sized
  for ~20 bubbles but a real catalog has ~187, so it ran ~9× over capacity:
  collisions shoved bubbles through the wall every frame (the wall clamp
  ran before the collision passes, never after), the seeds started on hex
  positions far outside the tank, and no wall was ever drawn — the result
  was a full-screen overlapping jumble. Now (new pure `PoolMath`):
  bubbles are sized to fit the catalog into the tank by area packing,
  seeds start inside the tank, a final containment pass after collisions
  makes the wall real, the rendered size matches the physics size, and
  the tank itself is drawn — a softly filled disc with an accent rim at
  exactly the wall radius. Small catalogs keep the big natural bubbles.

## 1.3.0 — 2026-10-25

- **Notification jiggle** — a bubble with a pending notification now wobbles
  alongside its breathing pulse ring: a ±1.7° rotation oscillation on a
  ~0.47s period, phase-offset per icon so notified bubbles don't jiggle in
  lockstep. The whole icon (bitmap, pulse ring, badges) rotates together.
  Transient copies stay steady: the carried bubble while dragging, the
  drop glide, and the Context Card's lifted copy don't jiggle.

## 1.2.0 — 2026-10-25 (prototype)

- **Context Card replaces Center Stage** — Center Stage (1.1.0) took up the
  whole screen; Matt sent the iOS Haptic Touch menu as the model instead.
  The Context Card keeps the grid visible under a light dim, lifts the
  pressed bubble in place with an accent ring, and anchors one compact
  rounded card next to it (below when there's room, above when there
  isn't, clamped on-screen). Rows follow the iOS layout — label left,
  glyph right, hairline dividers: Pin to Hex / Unpin, App Info (or Open
  Folder / Open Pod), More Options (the classic full menu), Edit Home
  Screen (enters Edit Layout), and a red Remove App last when the app can
  be uninstalled. Rows are a normal second tap; tap-outside, Back (the
  CARD_MENU layer) or Home dismisses the card.

## 1.1.0 — 2026-10-25 (prototype)

- **Center Stage replaces the quick-menu bloom** — Matt wasn't a fan of the
  hold-and-aim chips that bloomed out of a carried bubble (0.3.4), so the
  bloom is gone: a long-press is now purely a pickup for drag-to-place.
  Releasing without a drag opens Center Stage instead of the classic menu:
  the item animates to the centre of the screen, enlarged over a dimmed
  grid, with its name and Hex slot underneath and its actions as large
  pills below (Pin Here / Unpin, App Info, Uninstall when allowed, Open
  Folder / Open Pod, and More Options → the classic full menu). Pills are
  tapped as a normal second tap; tapping anywhere else, Back, or Home
  dismisses the stage. Inside a folder or Edit Layout, release-without-drag
  still opens the classic menu. Back priority gained a CENTER_STAGE layer
  (pops before everything; unit-tested, 41 tests).

## 1.0.0 — 2026-10-25

First production release.

- **Release build, properly signed** — the release variant now builds clean
  and signs with the real release keystore (`CN=Reborn Launchers`); verified
  installed and running on-device (grid, watch face, Smart Stack).
- **Everything is committed** — the previously uncommitted WIP (interactive
  Smart Stack cards with transport controls, Now Playing listener service,
  Turn Off accessibility service, Control Center, app classifier + test) is
  in git, so a clean checkout reproduces the app exactly.
- **TalkBack support (first pass)** — the single canvas View now exposes a
  virtual accessibility hierarchy (framework `AccessibilityNodeProvider`,
  no new dependencies): every grid/list/search/folder/overview bubble with
  its name and tap bounds, the watch face's favourite-app dock, and Smart
  Stack cards. Activating a node runs the same tap path as a finger, and
  explore-by-touch moves accessibility focus. Verified against the
  framework: the host advertises its virtual children and accessibility
  clients can fetch them all. Still host-only (not yet node-mapped): the
  Cluster sheet, topology picker, widget pod and Control Center.
- **Back fixed for real** — the "Back from the Smart Stack sometimes exits
  the app" gremlin is root-caused: MainActivity is a HOME-category activity,
  and for those the framework never translates KEYCODE_BACK into the
  OnBackInvokedCallback (verified by instrumentation on Android 13 and 16 —
  the key arrives, the callback doesn't, the default handler finishes the
  activity). Back now also travels dispatchKeyEvent, both paths funnel into
  one handler with a 350 ms guard against double-pops. Verified end-to-end:
  Stack → Back → grid → face → grid, app never exits.
- Version scheme moves to 1.0.0 (versionCode 10); 40 unit tests green.

## 0.3.5 — 2026-10-25

- **UX-test fix pass** — everything the full UX test pass flagged:
  - **Ring layouts no longer saturate**: Orbitals spreads the catalog over
    rings of 6, 10, 14, 18, … bubbles (pan to explore) instead of cramming
    ~160 apps onto one outer ring; the Chrono Bezel's outer ring is now a
    carousel — 18 bubbles ride it and spinning the bezel scrolls through
    the rest (the HUD readout shows the visible window); the Thumb Arc
    Dock's arcs grow 4, 6, 8, 10, … the same way. Ring math in pure,
    unit-tested `RingMath`.
  - **Dock anchored like a dock**: the home bubble sits at the bottom-center
    pivot under your thumb (the first arc's top bubble used to land on it),
    and vertical drags pan the arc field so higher arcs come into reach.
  - **Quick-menu chips stay on screen**: bloom targets clamp into the view,
    so a bubble near an edge no longer pushes its outer chip halfway off it.
  - **Constellation header fixed**: category chips flow-wrap at their real
    widths (they were squeezed into one equal-width row and rendered as
    overlapping gibberish), the header gets a solid backdrop, and the star
    field is clipped below it — stars can no longer shine or be tapped
    through the chips.
  - **Face time fits**: a two-digit hour ("12:59:59") shrinks the time to
    fit instead of clipping the seconds at the right edge.
  - **Sheet rows stay put**: the options sheet and topology picker fade in
    at their final position (no slide-up drift), and the sheet's height math
    always reserves the Clear Pins row, so rows sit in the same place on
    every open.
  - **Back from the Smart Stack hardened**: Back now treats a visibly-open
    Stack/Control Center as open even mid-animation, and the duplicate
    key-event Back handler is gone (Back travels the one
    OnBackInvokedCallback path).
  - Polish: River and Double Helix start higher on screen; the Fibonacci
    spiral breathes a little wider and its center hub draws on top; Nebula
    island pills and overview folder labels clamp inside the screen edges.

## 0.3.4 — 2026-10-25

- **Quick menu morphs out of the bubble**: the bottom buttons are gone.
  Picking up an app now blooms option chips out of the bubble's home spot in
  an arc — **✕ Unpin** (when pinned), **ℹ️ Info**, **🗑 Uninstall** (when
  allowed) — staggered, with a springy overshoot pop. Drag onto a chip (it
  grows and lights up under your finger) and let go to run it. The arc flips
  downward for bubbles near the top of the screen. Folders keep the top
  unpin zone; the full menu still opens on release without dragging.

## 0.3.3 — 2026-10-25

- **Quick options on long-press**: while you're holding an app bubble, two
  quick-action zones appear along the bottom — **ℹ️ App Info** and
  **🗑 Uninstall**. Drag onto one and let go to run it, no menu needed.
  Uninstall only appears for apps that can actually be uninstalled (system
  apps and other profiles are excluded). They join the existing
  "Drop here to unpin" zone, and the full long-press menu still opens if you
  let go without dragging.

## 0.3.2 — 2026-10-25

- **Bubble motion pass**: moving bubbles no longer teleport.
  - **Rearrangement glide** — when a placement, filing or layout switch changes a
    bubble's slot, its position springs to the new slot (~110ms damping) instead
    of snapping; pan, zoom and fisheye still apply on top. Folder-view members
    glide the same way when reordered.
  - **Carried-bubble spring** — a picked-up bubble pops smoothly to full drag
    size and trails the finger by a few pixels, so it feels held, not glued.
  - **Drop glide** — on release, the bubble flies from your finger into its slot
    over 240ms (ease-out); filing into a folder glides in and shrinks away.
  - Pure motion math in `Motion` (damping/easing), unit-tested.

## 0.3.1 — 2026-10-25

- **Usage Growth** (Cluster sheet → Behavior, on by default): bubbles grow with
  how much you use their app. Size follows the same recency-decayed launch score
  that already orders the cluster — most-used app up to 1.5×, never-opened apps
  at 0.85×, on a log curve so a couple of launches already show. Folder bubbles
  grow with their most-used member. Applies to the grid, the focus cluster,
  bloomed members and the Folder view; the pure scaling math is unit-tested
  (`UsageScale`).

## 0.3.0 — 2026-10-25

The drag-to-place follow-through release — everything the 0.2.1 notes proposed:

- **Edit Layout mode**: Cluster sheet → Edit Layout (or a bubble's long-press menu)
  makes every bubble draggable immediately, no long-press per bubble. A status
  pill with **✓ Done** sits above the List/Grid pill; Back also exits. Edit mode
  is a proper layer in the NavBack priority, with tests.
- **Drag onto a folder to file an app into it**: dropping an app on a folder
  bubble moves it into that folder's group; dropping it on its own folder just
  places it.
- **Unpin by dragging**: while carrying a pinned bubble, a red
  "✕ Drop here to unpin" zone appears at the top of the grid.
- **Undo for every placement mutation**: placing, filing into a folder, unpinning
  and folder reordering all show a 6-second undo bar that restores the exact
  previous state — including a pin occupant evicted by the placement.
- **Folder-view arranging**: in the full-screen Folder view (Folder Open Style:
  full-screen), long-press a member and drag to reorder it; the order persists
  per folder.
- **Per-Layout Pins** (Cluster sheet → Behavior, off by default): each cluster
  layout keeps its own pin set instead of sharing one global set.

## 0.2.1 — 2026-10-25

- **Drag-to-place**: long-press any bubble in the full grid to pick it up, drag
  it over the hex that should hold it (the target slot rings in the accent
  colour, with a haptic tick as the target changes), and release to pin it
  there. Releasing without dragging still opens the classic long-press menu.
  Dropping a bubble on an occupied slot unpins the previous occupant, matching
  the existing pin rules. The drop logic is unit-tested (`PlaceSlots`) and the
  full gesture is covered by an instrumented test (`DragPlaceTest`).

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
