package dev.watchreborn.ui

/**
 * Sizes measured from watchOS 27 footage (docs/RE_NOTES.md "Measurements"), in screen widths
 * (W): the watch is 422 px wide, so on a phone everything scales with the screen's width.
 */
object Metrics {
    // Focus cluster: the app view the crown (Home) opens.
    const val FOCUS_RING = 0.328f
    const val FOCUS_CENTER_D = 0.276f
    const val FOCUS_RING_D = 0.241f
    /** How bright the blurred apps behind the cluster are. */
    const val FOCUS_BACKDROP_ALPHA = 0.18f

    // Full grid (zoomed out): pointy-topped rows.
    const val GRID_PITCH = 0.274f
    const val GRID_CENTER_D = 0.242f

    // List view.
    const val LIST_ICON_D = 0.22f
    const val LIST_PITCH = 0.26f
    const val LIST_ICON_X = 0.16f
    const val LIST_LABEL_X = 0.33f
    const val LIST_TEXT = 0.085f
    const val PILL_H = 0.13f

    // Smart Stack.
    const val CARD_W = 0.91f
    const val CARD_H = 0.37f
    const val CARD_R = 0.06f
    const val CARD_GAP = 0.035f
    /** The next card peeks out this far below the one in front, and this much narrower. */
    const val CARD_PEEK = 0.035f
    const val CARD_PEEK_SCALE = 0.92f

    // Face to apps.
    const val ZOOM_SECONDS = 0.3f
    const val FACE_DIM = 0.5f
    const val ICONS_FROM_SCALE = 1.35f

    /** The watch's own screen shape (422 x 514); a taller phone stretches the fisheye vertically. */
    const val WATCH_ASPECT = 514f / 422f
}
