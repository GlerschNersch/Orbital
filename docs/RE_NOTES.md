# Watch Reborn: reverse-engineering notes

Source: the user's own `Watch7,12_27.0.1_24R365_Restore.ipsw` (watchOS 27.0.1, build 24R365, an
Apple Watch Ultra-class device, board n230). Everything extracted lives in `re/out/` (gitignored).
None of Apple's code, artwork, fonts or strings ship in this app. These notes record structure,
names and measured values, as for the other Reborn launchers.

## Getting at the firmware (Windows)

1. `ipsw` 3.1.733 (blacktop/ipsw, sha256 checked against the release's checksums.txt).
2. The system volume `043-05165-555.dmg.aea` is an Apple Encrypted Archive. `ipsw fw aea -o <dir>
   <file>` fetches its key from Apple's key service and decrypts it to a 9.1 GB APFS image.
   `ipsw extract --files` then fails on Windows: it wants `apfs-fuse` to mount the image.
3. 7-Zip reads APFS directly: `7z l -slt <dmg>` lists all 484,891 paths (`re/out/paths.txt`) and
   `7z x <dmg> "System\Library\..." -r` extracts.
4. The UI code is in the dyld shared cache (`System/Library/Caches/com.apple.dyld/`, 48 parts,
   4.2 GB). `ipsw class-dump <cache> <image>` gives ObjC declarations (`re/out/headers/`), and
   Only class, property and method names are taken from these (what exists and what it's
   called). Apple's code isn't disassembled or ported: sizes, curves and timings come from footage.
   The decrypted image and the cache were deleted after this survey.

`re/tools/`: `scan_paths.py` (summarise the file list), `plist_dump.py`, `face_names.py`.

## Where the home screen lives

- `Carousel.app` (System/Library/CoreServices) is the watch's SpringBoard. Its executable is a
  190 KB stub; the code is `Carousel.framework` in the shared cache (1,647 ObjC classes), with
  `CarouselAnimations.framework` and `PepperUICore.framework` (watch UI kit, crown scrolling).
- Its launchd plist (`com.apple.Carousel.plist`) doubles as a feature list: clockzoom (face ↔
  apps), tilenavigation, iconpositionstore, crown_scrolling_service, Smart Stack relevance
  (`com.apple.SmartStackUI.*`, relevanced), nightstand, theater mode, flashlight, LiveActivities,
  underwater depth mode, dock status.

## The app grid ("field of icons")

Classes: `CSLFieldOfIconsViewController`, `CSLHexAppGraph` / `CSLHexAppNode` (which app sits on
which hex), and the `CSLHexLayout` family:

- `CSLHexLayout` (base): `contentOffset`, `zoomPercent`, `percentComplete`, `centeredHex`,
  `targetHex`, `rubberbandVector`, `hexAtPoint:`, `layoutAttributesForItemAtHex:`,
  `scrolledPointFromContentPoint:` / `contentPointFromScrolledPoint:`.
- `CSLUniformHexLayout`: plain grid, `diameter` and `hexSideSize`.
- `CSLFullScreenIconHexLayout`: the normal honeycomb; wraps a uniform layout and keeps a
  per-hex attribute cache, `diameterScale`, `width`, `targetPoint`.
- `CSLOverviewHexLayout`: the zoomed-out view; `maxScale`, `falloffPercentage`, `diameterScale`,
  `fitsToQuadrantSize`, `normalizedGraphQuadrantSize`.
- `CSLInterpolatedHexLayout`: blends a start and end layout (for zooms).
- Hexes use axial coordinates (`Hex{q, r}`, two ints). `CSLHexAppGraph` stores its row count,
  normalised horizontal/vertical radius and top/bottom, and can be "vertical only".

`HexLayoutConfiguration` (passed to every layout's `initWithConfiguration:`), in field order:
`preStretchOffsetFactor`, `stretchSensitivity`, `stretchOffsetFactor`, `edgesMutingFactor`,
`diameterAdjustmentFactor`, `defaultPixelDiameter`, `screenScale`, `contentSize`, `hexSideSize`,
`maxPreStretchOffset`, `maxStretchOffset`. These names only say what is tunable; every value
we use is measured from footage of a real watch (see "Measurements").

Face ↔ apps zoom: `CSLIconFieldZoomAnimator` is a spring (`naturalFrequency`, `dampingRatio`,
`dampedFrequency`, `initialSpringDelaySeconds`, critically-damped flag), driven by a display
link. `CSLLauncherAnimationSettings`: `centersBeforeZooming`, `startZoomLevel`, `endZoomLevel`.

List view: `CSLLauncherListCollectionView` with `CSLLauncherPlatterLayout` (`iconSize`,
`iconPadding`, `iconToText`, `rightPadding`, `preferredCellHeight`).

## Watch faces

`System/Library/NanoTimeKit/FaceBundles/*FaceBundle.bundle`, codenames to names (from their
English strings): Foghorn = Modular Ultra, WhistlerSubdials = Modular Compact, Infograph,
InfographModular = Infograph Modular, Utility, Simple, ExtraLarge = X-Large, BigNumerals =
Numerals Mono/Duo, Solars = Solar, Globetrotter = World Time, Activity, Kuiper = Metropolitan,
Dolomite = Flux, Crosswind = Palette, Greyhound = Typograph, Proteus = Contour, Seltzer = Lunar,
Galleon = Wayfinder, Aegir = Astronomy, Exactitudes = Exactograph, Warlock = Flow, Cloudraker
(digital + 3 complications), Hero27 = Siri Modular, and the Hermès, Nike and Pride faces.

## Measurements

Source: YouTube `mvfRFisKbYk` ("Retrain Your Fingers for watchOS 27"), which uses Apple Watch
Mirroring, so the watch's own screen output is shown, about 350 x 399 px in the 1080p frame
(the Ultra's display is 422 x 514). Mirroring runs at about 15 fps: frames repeat in pairs, so
timings are good to about 1/15 s. All sizes are in screen widths (W), from `re/tools/measure_grid.py`
(icon centre = bounding-box centre, diameter = mean of box width and height).
A second video, `ExP2L85amSk`, is filmed of a real watch and was only used for the flow.

### Focus cluster (the app view the crown opens, 2:30)
watchOS 27 opens Apps on a cluster: one app in the centre, six around it, everything else blurred
and dimmed behind. The cluster is flat-topped (neighbours straight above and below):
- ring distance 0.328 W; neighbours at (0, ±0.317) and (±0.286, ±0.160);
- centre icon 0.276 W, ring icons 0.241 W (so the centre is 1.15x);
- the rest: blurred (several px at 350 px wide) and roughly a quarter opacity.

### Full grid (zoomed out, 5:50)
Pointy-topped rows, centred on one app:
- same-row pitch 0.274 W; rows at ±0.235 W, offset half a pitch (±0.131);
- second rows at ±0.43 W (an even lattice would put them at ±0.47: positions pull in ~8%);
- sizes: centre 0.242 W, first ring 0.21 W, (±0.373, ±0.21) 0.18 W, second rows 0.16-0.18 W.

### List view (5:30)
Icon 0.22 W, rows every 0.26 W, icon centres at 0.16 W from the left, labels from 0.33 W, label
text about 0.085 W, white and bold. A "Grid View" pill (0.13 W tall) floats at the bottom.

### Face to apps (2:26)
The face dims to about half and blurs in one step; the app icons arrive larger (about 1.35x) and
spread out, blurred, and pull in to their places with an ease-out and no visible overshoot.
About 0.3 s in all.

### Smart Stack (7:00-7:25)
Turning the crown on the face: the face shrinks to a header (date, large time), and cards follow,
0.91 W wide, about 0.37 W tall, corners about 0.06 W, tinted by their app. The next card peeks
from behind, narrower. Scrolled, the header becomes one line (date, time).

### Controls (the video's own summary, 9:30)
Press the crown: Apps. Turn the crown: Widgets (Smart Stack). Side button: Control Center.
Hold the crown: Siri. Pinch once: open, pinch twice: move, flick the wrist: back.
