package dev.watchreborn.ui

import kotlin.math.abs

/**
 * Quadrant snap placement for islands (3.6.0, Part C — Matt's
 * Windows Snap Layouts analogy, corrected 2026-10-27: the snap is
 * POSITIONAL, not a size change). While an island is dragged in
 * edit mode, the drag position relative to the usable field (the
 * fit-first field extents, in anchor units) engages a snap zone:
 *
 *   - beyond [ENGAGE_FRACTION] of the half-extent on BOTH axes ->
 *     that corner's QUADRANT
 *   - beyond it on ONE axis -> that side's HALF
 *   - otherwise -> free placement (pre-3.6.0 behaviour)
 *
 * Releasing over a zone sets the island's anchor so the TILE
 * CENTRE sits at the ZONE CENTRE, clamped fit-first so the whole
 * tile stays in-field, and persists through the normal
 * island_<id>_ax/ay path. The island's scale is never touched —
 * sizing stays with the 3.2.5 grip / presets.
 *
 * Anchor space: x grows right, y grows DOWN (positive anchorY is
 * the lower half of the field, as in IslandLayout.positionName).
 * Pure math; the view supplies the field half-extents.
 */
object SnapMath {

    enum class Zone {
        NONE,
        QUADRANT_TL, QUADRANT_TR, QUADRANT_BL, QUADRANT_BR,
        HALF_LEFT, HALF_RIGHT, HALF_TOP, HALF_BOTTOM,
    }

    /** How far toward an edge (as a fraction of the field
     *  half-extent) the drag must reach before a zone engages. */
    const val ENGAGE_FRACTION = 0.55f

    /** The fit-first margin (anchor units) kept between the tile
     *  and the field edge — mirrors the placement heal. */
    const val FIT_MARGIN = 0.12f

    /** Which zone a drag point falls in. [ax]/[ay] are the drag
     *  position in anchor units; [fieldHalfX]/[fieldHalfY] the
     *  usable field's half-extents in the same units. */
    fun classify(ax: Float, ay: Float, fieldHalfX: Float, fieldHalfY: Float): Zone {
        if (fieldHalfX <= 0f || fieldHalfY <= 0f) return Zone.NONE
        val overX = abs(ax) > fieldHalfX * ENGAGE_FRACTION
        val overY = abs(ay) > fieldHalfY * ENGAGE_FRACTION
        return when {
            overX && overY ->
                if (ax < 0f) (if (ay < 0f) Zone.QUADRANT_TL else Zone.QUADRANT_BL)
                else (if (ay < 0f) Zone.QUADRANT_TR else Zone.QUADRANT_BR)
            overX -> if (ax < 0f) Zone.HALF_LEFT else Zone.HALF_RIGHT
            overY -> if (ay < 0f) Zone.HALF_TOP else Zone.HALF_BOTTOM
            else -> Zone.NONE
        }
    }

    /** The zone's rectangle in anchor units as [left, top, right,
     *  bottom], or null for NONE. */
    fun zoneRect(zone: Zone, fieldHalfX: Float, fieldHalfY: Float): FloatArray? = when (zone) {
        Zone.NONE -> null
        Zone.QUADRANT_TL -> floatArrayOf(-fieldHalfX, -fieldHalfY, 0f, 0f)
        Zone.QUADRANT_TR -> floatArrayOf(0f, -fieldHalfY, fieldHalfX, 0f)
        Zone.QUADRANT_BL -> floatArrayOf(-fieldHalfX, 0f, 0f, fieldHalfY)
        Zone.QUADRANT_BR -> floatArrayOf(0f, 0f, fieldHalfX, fieldHalfY)
        Zone.HALF_LEFT -> floatArrayOf(-fieldHalfX, -fieldHalfY, 0f, fieldHalfY)
        Zone.HALF_RIGHT -> floatArrayOf(0f, -fieldHalfY, fieldHalfX, fieldHalfY)
        Zone.HALF_TOP -> floatArrayOf(-fieldHalfX, -fieldHalfY, fieldHalfX, 0f)
        Zone.HALF_BOTTOM -> floatArrayOf(-fieldHalfX, 0f, fieldHalfX, fieldHalfY)
    }

    /** The zone's centre in anchor units, or null for NONE. */
    fun zoneCenter(zone: Zone, fieldHalfX: Float, fieldHalfY: Float): FloatArray? {
        val r = zoneRect(zone, fieldHalfX, fieldHalfY) ?: return null
        return floatArrayOf((r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f)
    }

    /** The anchor a drop in [zone] lands on: the tile centre at
     *  the zone centre, then the fit-first clamp so the whole
     *  tile (half extents [halfW]/[halfH]) stays inside the field
     *  with [FIT_MARGIN] to spare. Null for NONE (free drop). */
    fun snappedAnchor(
        zone: Zone,
        fieldHalfX: Float,
        fieldHalfY: Float,
        halfW: Float,
        halfH: Float,
    ): FloatArray? {
        val c = zoneCenter(zone, fieldHalfX, fieldHalfY) ?: return null
        val limX = (fieldHalfX - halfW - FIT_MARGIN).coerceAtLeast(0f)
        val limY = (fieldHalfY - halfH - FIT_MARGIN).coerceAtLeast(0f)
        return floatArrayOf(
            c[0].coerceIn(-limX, limX),
            c[1].coerceIn(-limY, limY),
        )
    }
}
