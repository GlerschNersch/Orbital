# Watch Reborn

An Android home screen modelled on watchOS 27's: a watch face, the app cluster and honeycomb
grid, the List view and the Smart Stack. It is a clean-room rebuild: the layout and motion are
measured from screen recordings of a real watch (see [docs/RE_NOTES.md](docs/RE_NOTES.md)), and
every face, icon shape and drawing is our own. No Apple code, artwork or fonts are included.

Sibling of XMB, PS4, X360, Switch, C64U, VCS and TSF Reborn.

## Using it

- **The face** shows battery, date and next alarm along the top, a large time with the seconds
  in your accent colour, a timeline of the coming hours (with your alarm marked), and your three
  most-used apps along the bottom. A ring of ticks round the edge counts the seconds.
  - **Tap** the face to open your apps (tap one of the bottom three to open that app).
  - **Swipe up** for the Smart Stack; **swipe down** for Android's notifications.
  - **Hold** the face to change the accent colour or the home app.
- **Home** works like the watch's crown: on the face it opens your apps, anywhere else it goes
  back to the face. **Back** steps out one layer at a time — layout picker, options sheet,
  folder, widget pod, List view — and only then to the face.
- **Apps** open on a cluster: the app you use most in the middle, the next six around it, and
  everything else blurred behind. **Drag** to spread out into the full honeycomb, which shrinks
  icons toward the edges and settles with an app in the middle when you let go.
  **List View** at the bottom switches to an A-Z list (and **Grid View** back); your choice is
  remembered. **Hold** an app for App Info or Uninstall. In the full grid, **hold and drag**
  any bubble to place it on the hex you want — it stays pinned there (hold without
  dragging still opens the menu).
- **⚙ Cluster** (top right of the grid) opens the options sheet, grouped into **Layout**,
  **Behavior** and **Actions**. The **Cluster Topology** row opens a picker for all 11 cluster
  layouts (honeycomb, orbits, river, spiral, bezel, nebula, gravity, helix, hourglass, thumb-arc
  dock and zero-G billiards) — tap one to apply it, no cycling. **Pinch out** on the grid for an
  overview of the whole cluster; double-tap empty space to recenter.
- **Smart Stack**: date and time, then cards for your next alarm, battery and recent apps.
  Scroll through them; pull down from the first card to go back to the face.
- Work-profile apps show with Android's briefcase badge. Private Space apps stay out of the
  lists, behind their own lock.

## On a watch: Zepp OS face (`zepp-face/`)

The same face, redrawn for round 480 x 480 Zepp OS watches (Amazfit T-Rex 3 and T-Rex 3 Pro
48 mm): a seconds ring, battery / date / heart rate, a large time with accent seconds, a timeline
of the coming hours, and steps / calories / distance. Built with Zepp's Zeus CLI
(`npm i -g @zeppos/zeus-cli`): `zeus preview` in `zepp-face/` makes a QR code to scan in the Zepp
app with developer mode on, and `zeus dev` runs it in the Zepp OS Simulator.
`python -I tools/make_ticks.py` redraws the tick images.

`zepp-hex/` is a second face, **Watch Reborn Hex**: the watchOS app cluster's honeycomb, but
every bubble is a reading. The time sits in the middle; heart rate, steps, battery, weather
(high / low), calories and distance ring it; seconds, blood oxygen, stress, stand hours, PAI and
the next sunrise or sunset fill the small bubbles toward the edge. On each wake the bubbles
zoom in and settle, like the watch's own face-to-apps zoom. Same `zeus` commands.

`zepp-app/` is **Hex**, the draggable version (watch faces can't be dragged): 19 bubbles of
readings on the measured honeycomb with its fisheye; drag it and it glides to rest with the
nearest bubble in the middle; the up/down buttons step through it like a crown, and select or a
tap opens the bubble's own watch app. `zeus preview -t "Amazfit T-Rex 3"` (this project uses the
newer config, which names devices).

## Building

Android Studio or `./gradlew :app:assembleDebug` (JDK 17, Android SDK 36). Release builds are
signed from `REBORN_*` properties in `~/.gradle/gradle.properties` if present, otherwise with the
debug key.

## Licence

MIT. watchOS and Apple Watch are Apple's; this project isn't affiliated with or endorsed by
Apple.
