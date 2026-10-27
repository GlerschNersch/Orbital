package dev.watchreborn.ui

/**
 * Island snap PLACEMENT (3.6.0 Part C, reworked 3.6.1): in edit
 * mode, dragging an island toward a corner or edge of the screen
 * engages a snap zone — corners are quadrants, a single axis past
 * the threshold is that side's half — previewed as a zone wash
 * with the tile's drop ghost centred in it; on release the tile
 * keeps the zone-centre anchor, fit-first clamped. A centre drag
 * engages nothing and the drop stays free, exactly as before.
 * The island's scale (3.2.5 resize) is never touched.
 *
 * 3.6.1: snapping is a SCREEN-space gesture against the ENTIRE
 * CANVAS — the full cluster area between the header pill and the
 * dock, in view pixels at the current camera. Classification,
 * zone rects and zone centres all live in that canvas space, so a
 * quadrant is a true quarter of what the user sees at ANY zoom.
 * Only the landing converts back to anchor space (the view
 * inverts its camera transform, then [clampToField] applies the
 * fit-first clamp). 3.6.0 classified the tile's anchor against
 * the world field: at working zoom the field extends far past
 * the viewport, so zones engaged late and in the wrong places,
 * and the preview — the field rect pushed through the fisheye —
 * collapsed into a floating capsule around the tile.
 */
object SnapMath {

    enum class Zone {
        NONE,
        QUADRANT_TL, QUADRANT_TR, QUADRANT_BL, QUADRANT_BR,
        HALF_LEFT, HALF_RIGHT, HALF_TOP, HALF_BOTTOM,
    }

    /** Past this fraction of the canvas half-extent (per axis,
     *  measured from the canvas centre) a zone engages. 0.55 keeps
     *  the middle of the canvas a free-drop region while a
     *  deliberate push toward a corner or edge engages decisively,
     *  well before the finger reaches the glass edge. */
    const val ENGAGE_FRACTION = 0.55f

    /** Fit-first margin, in hex-pitch units: a snapped (or any
     *  healed) anchor keeps the whole tile this far inside the
     *  placeable field. Mirrors the load-time heal's margin in
     *  MainActivity (3.2.2) — one number, two consumers. */
    const val FIT_MARGIN = 0.12f

    /** Classify a pointer position (view px) against the canvas
     *  rect: both axes past the engage fraction = the corner's
     *  quadrant; one axis = that side's half; neither = NONE. */
    fun classify(
        px: Float, py: Float,
        left: Float, top: Float, right: Float, bottom: Float,
    ): Zone {
        val hw = (right - left) / 2f
        val hh = (bottom - top) / 2f
        if (hw <= 0f || hh <= 0f) return Zone.NONE
        val dx = (px - (left + hw)) / hw
        val dy = (py - (top + hh)) / hh
        val xPast = kotlin.math.abs(dx) > ENGAGE_FRACTION
        val yPast = kotlin.math.abs(dy) > ENGAGE_FRACTION
        return when {
            xPast && yPast -> when {
                dx < 0f && dy < 0f -> Zone.QUADRANT_TL
                dx > 0f && dy < 0f -> Zone.QUADRANT_TR
                dx < 0f -> Zone.QUADRANT_BL
                else -> Zone.QUADRANT_BR
            }
            xPast -> if (dx < 0f) Zone.HALF_LEFT else Zone.HALF_RIGHT
            yPast -> if (dy < 0f) Zone.HALF_TOP else Zone.HALF_BOTTOM
            else -> Zone.NONE
        }
    }

    /** The zone's rect within the canvas, as [left, top, right,
     *  bottom] in the same px space as the inputs. Zones tile the
     *  canvas edge-to-edge, split at the canvas midlines — a
     *  quadrant is exactly one quarter of the canvas, a half
     *  exactly one half. Null for NONE. */
    fun zoneRect(
        zone: Zone,
        left: Float, top: Float, right: Float, bottom: Float,
    ): FloatArray? {
        if (zone == Zone.NONE) return null
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        return when (zone) {
            Zone.QUADRANT_TL -> floatArrayOf(left, top, cx, cy)
            Zone.QUADRANT_TR -> floatArrayOf(cx, top, right, cy)
            Zone.QUADRANT_BL -> floatArrayOf(left, cy, cx, bottom)
            Zone.QUADRANT_BR -> floatArrayOf(cx, cy, right, bottom)
            Zone.HALF_LEFT -> floatArrayOf(left, top, cx, bottom)
            Zone.HALF_RIGHT -> floatArrayOf(cx, top, right, bottom)
            Zone.HALF_TOP -> floatArrayOf(left, top, right, cy)
            Zone.HALF_BOTTOM -> floatArrayOf(left, cy, right, bottom)
            Zone.NONE -> null
        }
    }

    /** The zone's centre in canvas px — where the tile lands. */
    fun zoneCenter(
        zone: Zone,
        left: Float, top: Float, right: Float, bottom: Float,
    ): FloatArray? {
        val r = zoneRect(zone, left, top, right, bottom) ?: return null
        return floatArrayOf((r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f)
    }

    /** The other zones of the engaged zone's layout: the three
     *  sibling quadrants, or the opposite half. The drag preview
     *  outlines them faintly so the canvas tiling reads (the
     *  Windows Snap Layouts geometry); empty for NONE. */
    fun siblingZones(zone: Zone): List<Zone> = when (zone) {
        Zone.QUADRANT_TL -> listOf(Zone.QUADRANT_TR, Zone.QUADRANT_BL, Zone.QUADRANT_BR)
        Zone.QUADRANT_TR -> listOf(Zone.QUADRANT_TL, Zone.QUADRANT_BL, Zone.QUADRANT_BR)
        Zone.QUADRANT_BL -> listOf(Zone.QUADRANT_TL, Zone.QUADRANT_TR, Zone.QUADRANT_BR)
        Zone.QUADRANT_BR -> listOf(Zone.QUADRANT_TL, Zone.QUADRANT_TR, Zone.QUADRANT_BL)
        Zone.HALF_LEFT -> listOf(Zone.HALF_RIGHT)
        Zone.HALF_RIGHT -> listOf(Zone.HALF_LEFT)
        Zone.HALF_TOP -> listOf(Zone.HALF_BOTTOM)
        Zone.HALF_BOTTOM -> listOf(Zone.HALF_TOP)
        Zone.NONE -> emptyList()
    }

    /** Fit-first clamp of an anchor into the placeable field: the
     *  tile's centre may range only where the whole tile (plus
     *  [FIT_MARGIN]) stays inside the field. A point outside is
     *  pulled to the NEAREST placeable point — the clamp never
     *  re-centres, it only trims what the tile's size forbids. An
     *  axis whose tile is wider than the field pins to 0. */
    fun clampToField(
        ax: Float, ay: Float,
        fieldHalfX: Float, fieldHalfY: Float,
        halfW: Float, halfH: Float,
    ): FloatArray {
        val limX = (fieldHalfX - halfW - FIT_MARGIN).coerceAtLeast(0f)
        val limY = (fieldHalfY - halfH - FIT_MARGIN).coerceAtLeast(0f)
        return floatArrayOf(
            ax.coerceIn(-limX, limX),
            ay.coerceIn(-limY, limY),
        )
    }
}
