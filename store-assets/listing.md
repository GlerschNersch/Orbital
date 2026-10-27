# Orbital — Play Store listing pack

Prepared 2026-10-26. Everything here is ready to paste into Play Console.

## Store listing

- **App name (30 chars):** Orbital — Bubble Launcher  *(28)*
- **Short description (80 chars):** A home screen of bubbles that grow with use — your top app sits in the centre.  *(79)*
- **Category:** Personalization
- **Contains ads:** No
- **Contact email:** m.vincent43@gmail.com
- **Privacy policy:** LIVE at https://glerschnersch.github.io/Orbital/privacy-policy.html
  (source: `docs/privacy-policy.html`, GitHub Pages on `main`/`docs`)

**Full description:**

> Orbital is a home-screen launcher built around one idea: the apps you use most
> should be the easiest to reach.
>
> Your apps float as bubbles on a gentle lattice. The more you use an app, the
> bigger its bubble grows — and your single most-used app takes the centre of the
> cluster, always under your thumb. Everything re-sorts itself as your habits
> change; there is nothing to arrange by hand.
>
> When a notification arrives, it peeks out of its app's bubble — a little speech
> pill with the title and first line — then tucks itself back into a badge dot.
> Tap the peek to jump straight to the app.
>
> • A Widgets page that hosts real Android widgets (clock, weather, calendar…)
> • Folders, pins, and drag-to-rearrange with long-press edit mode
> • Multiple cluster topologies, from Classic Honeycomb to Liquid Pool
> • Full TalkBack support, reduced-motion support, and one-thumb ergonomics
>
> Orbital is private by design: it doesn't even hold the internet permission, so
> nothing about your usage can leave your phone. No accounts, no ads, no
> analytics — ever.

## Assets in this folder

- `icon-512.png` — store icon (512×512)
- `feature-graphic-1024x500.png` — feature graphic
- Screenshots: take from the live app (phone, 16:9 or taller). Good set:
  cluster at rest, notification peek, Widgets page, Settings,
  folder bloom. Minimum 2, aim for 5–6.

## Play Console declarations (draft answers)

**Data safety:** No data collected. No data shared. (Orbital has no INTERNET
permission; all processing is on-device. Notification content, launch counts and
step readings are processed locally and never transmitted.)

**Notification access (NotificationListenerService):** "Orbital is a launcher.
Notification access is core to two visible features: per-app notification badges
on the home-screen bubbles, and a transient preview ('peek') that grows out of
the notifying app's bubble showing the notification's title and first line of
text. Content is rendered on-device only, held in memory while the notification
exists, and never stored, logged, or transmitted — the app holds no internet
permission. Access is optional and user-granted; the launcher works without it."

**Physical activity (ACTIVITY_RECOGNITION):** "Used only to read the device step
counter for display in Orbital's step widget. Readings are shown, not stored or
transmitted."

**Accessibility API:** None. Orbital declares no accessibility service.
(The TurnOff screen-lock service and the Control Center it lived in were removed
in 3.0.0 — Android's own Quick Settings shade covers the toggles, so the Play
accessibility declaration is no longer needed.)

**Permissions declaration form:** REQUEST_DELETE_PACKAGES was removed in 2.9.2
(uninstall uses the system ACTION_DELETE flow); EXPAND_STATUS_BAR,
ACCESS_WIFI_STATE and BLUETOOTH were removed in 3.0.0 with the Control Center.
The only remaining declared permission is ACTIVITY_RECOGNITION (above).

## Console steps that need Matt personally

1. Play Console developer account — DONE: identity verified 2026-05-26,
   developer name "Vincus" (confirmed from Gmail 2026-10-26).
2. If the account is a *personal* account: production requires a closed test
   with **12+ opted-in testers for 14 consecutive days** first. Start this as
   early as possible — it is the critical path.
3. Application ID — DECIDED 2026-10-26: `dev.orbital` (renamed in 3.0.1,
   before first upload; the code namespace stays `dev.watchreborn`).
