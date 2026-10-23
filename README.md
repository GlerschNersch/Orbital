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
  back to the face. **Back** also returns to the face.
- **Apps** open on a cluster: the app you use most in the middle, the next six around it, and
  everything else blurred behind. **Drag** to spread out into the full honeycomb, which shrinks
  icons toward the edges and settles with an app in the middle when you let go.
  **List View** at the bottom switches to an A-Z list (and **Grid View** back); your choice is
  remembered. **Hold** an app for App Info or Uninstall.
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

## Building

Android Studio or `./gradlew :app:assembleDebug` (JDK 17, Android SDK 36). Release builds are
signed from `REBORN_*` properties in `~/.gradle/gradle.properties` if present, otherwise with the
debug key.

## Licence

MIT. watchOS and Apple Watch are Apple's; this project isn't affiliated with or endorsed by
Apple.
