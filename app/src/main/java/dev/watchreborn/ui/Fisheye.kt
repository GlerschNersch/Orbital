package dev.watchreborn.ui

/**
 * The full grid's magnification, fitted to the measured zoomed-out frame: icons shrink and pull
 * inward the farther they are from the screen's centre. Distances are in screen widths, measured
 * on an ellipse that matches the screen's shape (see [Metrics.WATCH_ASPECT]).
 */
object Fisheye {
    // (distance, icon diameter / centre diameter): 0, 1st ring, (±0.373, ±0.21), 2nd rows; then
    // our own continuation to nothing past the screen's edge.
    private val SIZE = floatArrayOf(
        0f, 1f,
        0.27f, 0.87f,
        0.43f, 0.75f,
        0.49f, 0.68f,
        0.62f, 0.45f,
        0.78f, 0f,
    )

    // (lattice distance, shown distance / lattice distance): rows two out sit at 0.43, not 0.47.
    private val PULL = floatArrayOf(
        0f, 1f,
        0.27f, 1f,
        0.47f, 0.915f,
        0.8f, 0.82f,
        1.5f, 0.75f,
    )

    /** Icon diameter relative to the centre icon's, at [d] screen widths from the centre. */
    fun size(d: Float): Float = lerpTable(SIZE, d)

    /** How far an icon at lattice distance [d] is drawn from the centre, in screen widths. */
    fun pulled(d: Float): Float = d * lerpTable(PULL, d)

    private fun lerpTable(t: FloatArray, x: Float): Float {
        if (x <= t[0]) return t[1]
        var i = 0
        while (i + 2 < t.size) {
            val x0 = t[i]; val y0 = t[i + 1]; val x1 = t[i + 2]; val y1 = t[i + 3]
            if (x <= x1) return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
            i += 2
        }
        return t[t.size - 1]
    }
}
