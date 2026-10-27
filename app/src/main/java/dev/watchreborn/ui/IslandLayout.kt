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

    // ── Island footprint vs the fisheye pull (3.6.4) ────────────
    // Islands render at their nominal size wherever they sit
    // (3.6.3); bubbles still shrink toward the rim, and every
    // centre rides the fisheye pull. The solver therefore clears
    // bubbles for the tile's SCREEN footprint, not its anchor
    // rect. 3.6.3 approximated that by scaling the whole rect by
    // the inverse SIZE falloff at the anchor — safe but blunt:
    // the size table falls to ~0 at the rim (floored at 0.45, a
    // 2.2x obstacle), while the screen distance between centres
    // is governed by the PULL, whose local compression is far
    // milder (tangential never below 0.75; the radial derivative
    // dips to ~0.59 in one distance band). The moat it left was
    // up to a tile-height of dead space.
    //
    // The tighter construction works per axis. Along one axis, a
    // bubble whose centre sits t anchor units from the tile's
    // centre shows up κ·t screen units away, where κ is the
    // pull's compression along that axis at the island's anchor;
    // the tile's edge stays at its full half extent, and the
    // bubble's drawn radius is at most REF_DRAWN_RADIUS. For the
    // screen gap between the bubble's edge and the tile's edge
    // to come out at CLEARANCE_MARGIN, the obstacle half extent
    // must be (half + REF_DRAWN_RADIUS + CLEARANCE_MARGIN) / κ,
    // minus the clearance radius the solver's circle test adds
    // on top (REF_CLEARANCE_RADIUS): in screen space that radius
    // comes back compressed by the same κ while the drawn radius
    // shrinks by the size falloff, and the two effects cancel to
    // within a few hundredths of a pitch across the field — so
    // crediting it back loses nothing and keeps the band slim.

    /** Screen-space breathing room (pitch units) the solver
     *  arranges between a tile's edge and the nearest bubble's
     *  edge, at any anchor. (3.6.5: halved from 0.16 — "just a
     *  hair tighter," per Matt after using 3.6.4. It stays
     *  non-zero: it is the only term that guarantees a bubble
     *  never quite touches the tile.) */
    const val CLEARANCE_MARGIN = 0.08f

    /** A bubble's drawn radius in pitch units, upper reference:
     *  the nominal GRID_CENTER_D radius is 0.40; the headroom
     *  covers usage-grown bubbles (their clearance radius grows
     *  in step, which is what keeps the cancellation honest). */
    const val REF_DRAWN_RADIUS = 0.42f

    /** The clearance radius [assign]'s circle test adds around
     *  the obstacle for a standard bubble (0.45 growth base +
     *  0.04, see WatchView.islandClearanceRadius). */
    const val REF_CLEARANCE_RADIUS = 0.49f

    /** The smallest pull compression the solver compensates
     *  for; the true table minimum is ~0.59, so this is a guard
     *  against pathological inputs, not a working value. */
    const val MIN_PULL_COMPRESSION = 0.5f

    /** The pull's compression along the screen axes at a point
     *  whose fisheye distance is [d] (elliptical screen widths —
     *  the draw pass's own measure) with normalized coords
     *  ([nx], [ny]): returns [κx, κy]. The pull is radial in
     *  normalized space with singular values pulled'(d) (radial)
     *  and pulled(d)/d (tangential); the axis compressions are
     *  the Jacobian's diagonal elements, mixing the two by each
     *  axis's squared share of the direction. Both are 1 at the
     *  centre, where the pull is the identity. */
    fun axisCompressions(d: Float, nx: Float, ny: Float): FloatArray {
        if (d.isNaN() || d < 1e-4f) return floatArrayOf(1f, 1f)
        val tangential = Fisheye.pulled(d) / d
        val radial = pullDerivative(d)
        val shareX = (nx * nx) / (d * d)
        val shareY = (ny * ny) / (d * d)
        return floatArrayOf(
            tangential + (radial - tangential) * shareX,
            tangential + (radial - tangential) * shareY,
        )
    }

    /** d(pulled)/dd of the fisheye pull at [d], central
     *  difference (exact on the table's quadratic segments). */
    private fun pullDerivative(d: Float): Float {
        val h = 0.01f
        return if (d <= h) Fisheye.pulled(h) / h
        else (Fisheye.pulled(d + h) - Fisheye.pulled(d - h)) / (2f * h)
    }

    /** The solver obstacle for an island centred at ([centerX],
     *  [centerY]) with nominal half extents, given the pull's
     *  per-axis compressions at its anchor ([compressionX],
     *  [compressionY]; both 1 at the centre). See the block
     *  comment above for the construction. */
    fun obstacleFor(
        centerX: Float, centerY: Float, halfW: Float, halfH: Float,
        compressionX: Float, compressionY: Float,
    ): Obstacle {
        fun axis(half: Float, k: Float): Float {
            val kc = if (k.isNaN()) 1f else k.coerceIn(MIN_PULL_COMPRESSION, 1f)
            return (half + REF_DRAWN_RADIUS + CLEARANCE_MARGIN) / kc - REF_CLEARANCE_RADIUS
        }
        return Obstacle(
            centerX, centerY,
            axis(halfW, compressionX), axis(halfH, compressionY),
        )
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

    /**
     * Coarse spoken position of an island anchor in the field
     * (3.2.4, for TalkBack node labels): a 3x3 reading of the anchor
     * in pitch units — "centre", "upper left", "lower", and so on.
     * Screen Y grows downward, so a positive anchorY is "lower".
     */
    fun positionName(anchorX: Float, anchorY: Float): String {
        val h = when {
            anchorX < -0.75f -> "left"
            anchorX > 0.75f -> "right"
            else -> ""
        }
        val v = when {
            anchorY < -0.75f -> "upper"
            anchorY > 0.75f -> "lower"
            else -> ""
        }
        return when {
            v.isEmpty() && h.isEmpty() -> "centre"
            v.isEmpty() -> h
            h.isEmpty() -> v
            else -> "$v $h"
        }
    }

    // ── Island resize (3.2.5) ─────────────────────────────────────
    // A resized island persists one scale factor; the slot's half
    // extents are the base extents times this scale, so every
    // consumer (draw, solver, walls, overview fit) sees the resize.

    const val MIN_ISLAND_SCALE = 0.6f
    const val MAX_ISLAND_SCALE = 1.8f

    /** Grip-tap presets, ascending; the cycle wraps to the first. */
    val ISLAND_SCALE_PRESETS = floatArrayOf(0.8f, 1.0f, 1.3f, 1.6f)

    fun clampScale(scale: Float): Float =
        if (scale.isNaN()) 1f else scale.coerceIn(MIN_ISLAND_SCALE, MAX_ISLAND_SCALE)

    /** The next preset above [current] (with a small dead zone so a
     *  scale already sitting on a preset advances), wrapping around. */
    fun nextPresetScale(current: Float): Float {
        for (p in ISLAND_SCALE_PRESETS) if (p > current + 0.05f) return p
        return ISLAND_SCALE_PRESETS.first()
    }
}
