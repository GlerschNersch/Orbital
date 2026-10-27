package dev.watchreborn.ui

import kotlin.math.abs
import kotlin.math.max

/**
 * Slot assignment for widget islands in the lattice layouts (3.2.1).
 *
 * Honeycomb and Gravity place bubbles on a hex lattice. When a widget
 * island sits on that lattice, the bubbles whose circles would run into
 * the island are reassigned to the nearest free lattice slots; every
 * other bubble keeps its home slot. The result is deterministic: the
 * same island position always produces the same layout, bubbles always
 * sit exactly on lattice points, and no two bubbles ever share a slot.
 *
 * All coordinates are in "pitch units": a bubble centre is
 * pointyCenter(hex, latticeScale), and island rectangles live in the
 * same space (1.0 = one base grid pitch; Gravity's lattice runs at
 * 1.10 because its world positions are pitched 10% wider).
 */
object IslandLayout {

    /** An island's rectangle in pitch units. */
    data class Obstacle(
        val centerX: Float,
        val centerY: Float,
        val halfW: Float,
        val halfH: Float,
    )

    /** True when a circle at ([cx], [cy]) with radius [r] touches [o]. */
    fun circleIntersects(cx: Float, cy: Float, r: Float, o: Obstacle): Boolean {
        val dx = max(abs(cx - o.centerX) - o.halfW, 0f)
        val dy = max(abs(cy - o.centerY) - o.halfH, 0f)
        return dx * dx + dy * dy < r * r
    }

    fun circleIntersectsAny(cx: Float, cy: Float, r: Float, obstacles: List<Obstacle>): Boolean {
        for (o in obstacles) if (circleIntersects(cx, cy, r, o)) return true
        return false
    }

    /**
     * Assigns each item a lattice slot. [original] is the item order's
     * home slots (a prefix of the hex spiral); [radii] is each item's
     * clearance radius in pitch units. Items whose home slot is clear
     * keep it; the rest move to the nearest clear, unclaimed candidate
     * slot, searching outward through an extended spiral.
     */
    fun assign(
        original: List<Hex>,
        latticeScale: Float,
        radii: FloatArray,
        obstacles: List<Obstacle>,
        extraSlots: Int = 384,
        protectedIndex: Int = 0,
    ): List<Hex> {
        if (original.isEmpty()) return emptyList()
        require(radii.size == original.size) { "radii must match item count" }
        if (obstacles.isEmpty()) return original

        val candidates = Hex.spiral(original.size + extraSlots)
        val centerTmp = FloatArray(2)
        fun centerOf(h: Hex, out: FloatArray) = pointyCenter(h, latticeScale, out)

        val origCenters = Array(original.size) { i ->
            val a = FloatArray(2)
            centerOf(original[i], a)
            a
        }
        val blocked = BooleanArray(original.size) { i ->
            if (i == protectedIndex) {
                // Centre protection (3.2.2): the core bubble keeps its
                // home unless an island actually covers the centre
                // point itself — a tile merely brushing its clearance
                // circle does not evict the cluster's most-used app.
                obstacles.any { o ->
                    abs(origCenters[i][0] - o.centerX) <= o.halfW &&
                        abs(origCenters[i][1] - o.centerY) <= o.halfH
                }
            } else {
                circleIntersectsAny(origCenters[i][0], origCenters[i][1], radii[i], obstacles)
            }
        }

        val result = arrayOfNulls<Hex>(original.size)
        val used = BooleanArray(candidates.size)
        // Unaffected items keep (and reserve) their home slots, so a
        // displaced neighbour can never steal a slot that was fine.
        for (i in original.indices) {
            if (!blocked[i]) {
                result[i] = original[i]
                if (i in used.indices) used[i] = true
            }
        }
        val candCenter = FloatArray(2)
        for (i in original.indices) {
            if (!blocked[i]) continue
            var best = -1
            var bestScore = Float.MAX_VALUE
            for (c in candidates.indices) {
                if (used[c]) continue
                centerOf(candidates[c], candCenter)
                if (circleIntersectsAny(candCenter[0], candCenter[1], radii[i], obstacles)) continue
                val dx = candCenter[0] - origCenters[i][0]
                val dy = candCenter[1] - origCenters[i][1]
                // Nearest clear slot wins; the tiny index term prefers
                // the more central slot on ties, keeping the cluster
                // compact instead of spraying displaced apps outward.
                val score = dx * dx + dy * dy + c * 0.00001f
                if (score < bestScore) {
                    bestScore = score
                    best = c
                }
            }
            if (best >= 0) {
                result[i] = candidates[best]
                used[best] = true
            } else {
                // No clear slot in range (pathological island size):
                // stay home rather than vanish.
                result[i] = original[i]
            }
        }
        return result.map { it!! }
    }
}
