package dev.watchreborn.ui

/**
 * Fit-to-view overview scale (3.2.3).
 *
 * The overview used to be a hard-coded 0.48 zoom: fine when the field
 * held a screenful of bubbles, but with a full catalog plus several
 * widget islands the field is far bigger than the screen at 0.48, so
 * "pinch out to see everything" didn't actually show everything
 * (Matt's phone report, 2026-10-26). WatchView now measures the
 * content's bounding box in world pixels (bubble slots at their
 * overview positions, plus island rects) and asks here for the scale
 * that fits it into the viewport.
 *
 * The result never exceeds [MAX_ZOOM] (small fields keep the classic
 * overview) and never goes below [MIN_ZOOM] (past that, bubbles stop
 * being legible; huge fields fit as much as readability allows).
 */
object OverviewFit {

    const val MAX_ZOOM = 0.48f
    const val MIN_ZOOM = 0.20f

    /**
     * @param extentX half-width of the content in world px, measured
     *   from the field origin (max of |min| / |max|, radius included)
     * @param extentY half-height of the content in world px, same basis
     * @param availHalfW half the usable viewport width in screen px
     * @param availHalfH half the usable viewport height in screen px
     *   (the smaller of the above-centre / below-centre room)
     */
    fun fitZoom(extentX: Float, extentY: Float, availHalfW: Float, availHalfH: Float): Float {
        if (extentX <= 0f || extentY <= 0f || availHalfW <= 0f || availHalfH <= 0f) return MAX_ZOOM
        val fit = minOf(availHalfW / extentX, availHalfH / extentY, MAX_ZOOM)
        return fit.coerceAtLeast(MIN_ZOOM)
    }
}
