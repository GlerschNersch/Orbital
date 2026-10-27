package dev.watchreborn.ui

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Slot assignment for widget islands in the lattice layouts (3.2.1).
 *
 * Honeycomb and Gravity place bubbles on a hex lattice. When a widget
 * island sits on that lattice, the bubbles whose circles would run into
 * the island are reassigned to the nearest free lattice slots; every
 * other bubble keeps its home slot. The result is deterministic: the
 * same island position always produces the same layout and no two
 * bubbles ever share a slot. (3.7.0 refines the assignment's centres
 * off-lattice in each island's immediate neighbourhood so bubbles
 * hug the tile — see [refineHug]. The assignment itself still works
 * exactly as described.)
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

    // ── Off-lattice island hugging (3.7.0) ────────────────────
    // The assignment above keeps every bubble on an exact
    // lattice point — which makes the moat around an island
    // structural: the ring of slots around a tile belongs to
    // bubbles whose homes are there (they never move), and the
    // bubbles the island displaced land beyond that ring, so
    // no clearance margin, however tuned, can close the
    // visible gap (3.6.3–3.6.5 proved the point: the dense
    // field's moat went 1.91 → 1.01 pitch and no further).
    // refineHug relaxes the rule LOCALLY: after assignment,
    // each bubble in an island's neighbourhood slides toward
    // the tile until it sits HUG_MARGIN from the tile's true
    // edge — a continuous position, off-lattice.
    //
    // Judging is in TRUE screen terms, using the draw pass's
    // own radial map: an anchor point's rest-state screen
    // position is the point scaled by pulled(d)/d (see
    // fisheyeScreenPos — the pull factor is uniform per
    // point, so this is exact, not a linearization), the
    // tile renders at its full size around its own pulled
    // centre (the 3.6.3 exemption), and a bubble's drawn
    // radius shrinks by Fisheye.size at its own distance.
    // The slide is computed in screen space toward the
    // rect's closest point and mapped back to an anchor
    // position by inverting the pull (fixed-point, fixed
    // iteration count); the travel is refined over a fixed
    // four evaluations because the bubble's drawn radius
    // changes as it moves (a bubble sliding in from the
    // size falloff grows on the way in). Hard constraints,
    // in order: a refined bubble never overlaps any tile
    // (true screen gap at the candidate's own drawn radius);
    // refined bubbles never overlap each other (true screen
    // separation ≥ both drawn radii + HUG_PAIR_MARGIN);
    // movement is (near-)minimal — straight at the closest
    // point, and a correction that would push the bubble
    // back past its start abandons the hug. One more bound:
    // the OVERVIEW frame (pull off, bubbles drawn at 0.88 of
    // nominal around their anchor centres — where a rim
    // bubble that is a speck at working zoom is nearly full
    // size again) must stay overlap-free too, so the travel
    // is also capped by an anchor-space floor on the rect
    // distance. A bubble that cannot satisfy the constraints
    // KEEPS its lattice
    // slot; the protected centre item is never refined; a
    // bubble already at or inside the margin is never
    // pushed away. Deterministic throughout: islands in
    // list order, members nearest-the-tile first (ties by
    // anchor distance to the island centre, then index),
    // fixed iteration counts, no randomness, no time —
    // same inputs, byte-identical outputs. Solve-time
    // only, cached by the caller; nothing here runs per
    // frame.

    /** Target screen-space edge-to-edge gap (in pitch
     *  units) between a hugging bubble and the tile's true
     *  edge — tight against the tile, off the lattice. */
    const val HUG_MARGIN = 0.10f

    /** Extra screen-space separation (in pitch units)
     *  required between two bubbles' drawn edges wherever
     *  the hug judges them against each other. */
    const val HUG_PAIR_MARGIN = 0.04f

    /** The size factor the overview draw applies to every
     *  bubble (the draw's 0.88 overview term): in overview a
     *  bubble's drawn radius is its nominal drawn radius
     *  times this, around its un-pulled anchor centre. */
    private const val OVERVIEW_SIZE = 0.88f

    /** Cushion (pitch units) the hug keeps beyond the
     *  overview floor, so overview edges never quite kiss. */
    private const val OVERVIEW_CUSHION = 0.02f

    /** Neighbourhood radius (in pitch units): a bubble is
     *  a hug candidate for an island when its assigned
     *  centre lies inside the island's compensated obstacle
     *  expanded by this much. 1.5 pitch reaches past the
     *  first full ring of slots beyond the obstacle in both
     *  lattice orientations (pointy rows step 0.866): at
     *  1.3 the dense field's first populated row past a rim
     *  island's obstacle fell 0.01 outside the box and the
     *  tile's long faces never hugged, while its short
     *  faces did. Claiming a bubble never forces a move —
     *  it still hugs only when its true screen gap exceeds
     *  the margin and its spot is free. */
    const val HUG_NEIGHBOURHOOD = 1.5f

    /** The flat part of the view's islandClearanceRadius —
     *  subtracted back out in [drawnRadius]. */
    private const val CLEARANCE_FLAT = 0.04f

    /** A bubble's DRAWN radius inside its clearance radius:
     *  the drawn base radius (0.4416 pitch, from the glyph
     *  draw: GRID_CENTER_D / GRID_PITCH / 2 = 0.242 / 0.274
     *  / 2) over the clearance base (0.45) — phonetic
     *  bubbles draw at 0.4416 / (0.45 + 0.04) of their
     *  clearance radius (the view's 0.45·growth·weight +
     *  0.04 flat). */
    private const val DRAWN_PER_CLEARANCE = 0.9813f

    /** The drawn radius (pitch units) of the bubble whose
     *  solver clearance radius is [clearanceRadius] — the
     *  inverse of the view's islandClearanceRadius, so hug
     *  geometry is judged against what is actually drawn,
     *  not the generous clearance disc. The 0.05 floor keeps
     *  degenerate scales sane. */
    fun drawnRadius(clearanceRadius: Float): Float =
        max((clearanceRadius - CLEARANCE_FLAT) * DRAWN_PER_CLEARANCE, 0.05f)

    /** One island as [refineHug] needs it: the tile's true
     *  rect (its rendered footprint) and the compensated
     *  obstacle [assign] cleared around — the obstacle
     *  defines the hug neighbourhood. */
    data class HugIsland(
        val rect: Obstacle,
        val obstacle: Obstacle,
    )

    /** Rest-state screen position (in pitch units) of an
     *  anchor point, in the draw pass's own terms: the
     *  fisheye pull scales the point radially by
     *  pulled(d)/d, where d is the point's elliptical
     *  normalized distance ([normScaleX], [normScaleY] map
     *  anchor units to it). This is the position the point
     *  is actually drawn at when pan and overview are at
     *  rest — the frame [refineHug] judges in. */
    fun fisheyeScreenPos(
        x: Float, y: Float, normScaleX: Float, normScaleY: Float, out: FloatArray,
    ) {
        val d = hypot(x * normScaleX, y * normScaleY)
        val k = if (d < 1e-4f) 1f else Fisheye.pulled(d) / d
        out[0] = x * k
        out[1] = y * k
    }

    /** Elliptical normalized fisheye distance of a point. */
    private fun fisheyeDist(
        x: Float, y: Float, normScaleX: Float, normScaleY: Float,
    ): Float = hypot(x * normScaleX, y * normScaleY)

    /** Inverse of [fisheyeScreenPos]: the anchor point that
     *  is drawn at screen position ([sx], [sy]). Fixed-point
     *  iteration with a FIXED count, so the result is
     *  deterministic; the pull factor stays within
     *  [0.75, 1], so eight rounds converge to float
     *  precision. */
    private fun anchorFromScreen(
        sx: Float, sy: Float, normScaleX: Float, normScaleY: Float, out: FloatArray,
    ) {
        if (normScaleX <= 0f || normScaleY <= 0f) {
            out[0] = sx
            out[1] = sy
            return
        }
        var px = sx
        var py = sy
        repeat(8) {
            val d = hypot(px * normScaleX, py * normScaleY)
            val k = if (d < 1e-4f) 1f else Fisheye.pulled(d) / d
            px = sx / k
            py = sy / k
        }
        out[0] = px
        out[1] = py
    }

    /** Distance from point ([px], [py]) to the rect centred
     *  at ([rcx], [rcy]) with the given half extents — 0
     *  when the point is inside the rect. */
    private fun rectDist(
        px: Float, py: Float, rcx: Float, rcy: Float, halfW: Float, halfH: Float,
    ): Float {
        val dx = max(abs(px - rcx) - halfW, 0f)
        val dy = max(abs(py - rcy) - halfH, 0f)
        return hypot(dx, dy)
    }

    /**
     * Refines assigned centres (pitch units, the solver's
     * centres as pointyCenter produces from [assign]'s hexes)
     * so bubbles near
     * each island hug its true edges. Returns a NEW centre
     * array; the input is never mutated. A bubble is
     * claimed by the island whose expanded obstacle
     * contains its assigned centre (nearest obstacle centre
     * when several claim it, ties by island order); claimed
     * bubbles are refined nearest-the-tile first (ties by
     * anchor distance to the island centre, then item
     * index). Unclaimed bubbles — and claimed ones whose
     * hug cannot satisfy the constraints, and the
     * [protectedIndex] item — keep their exact input
     * centres: the regression fence the tests assert.
     * Without view geometry ([normScaleX] / [normScaleY]
     * ≤ 0) the input is returned unchanged: there is no
     * screen frame to judge in.
     */
    fun refineHug(
        centers: Array<FloatArray>,
        radii: FloatArray,
        islands: List<HugIsland>,
        normScaleX: Float,
        normScaleY: Float,
        protectedIndex: Int = 0,
    ): Array<FloatArray> {
        val n = centers.size
        val out = Array(n) { i -> floatArrayOf(centers[i][0], centers[i][1]) }
        if (n == 0 || islands.isEmpty()) return out
        require(radii.size == n) { "radii must match item count" }
        if (normScaleX <= 0f || normScaleY <= 0f) return out
        val drawn = FloatArray(n) { drawnRadius(radii[it]) }

        // Claim pass: nearest claiming island per bubble.
        val claim = IntArray(n) { -1 }
        val claimDist = FloatArray(n) { Float.MAX_VALUE }
        for (i in 0 until n) {
            if (i == protectedIndex) continue
            for (k in islands.indices) {
                val ob = islands[k].obstacle
                if (abs(centers[i][0] - ob.centerX) <= ob.halfW + HUG_NEIGHBOURHOOD &&
                    abs(centers[i][1] - ob.centerY) <= ob.halfH + HUG_NEIGHBOURHOOD
                ) {
                    val dx = centers[i][0] - ob.centerX
                    val dy = centers[i][1] - ob.centerY
                    val d2 = dx * dx + dy * dy
                    if (d2 < claimDist[i]) {
                        claimDist[i] = d2
                        claim[i] = k
                    }
                }
            }
        }

        // Each tile's screen centre, computed once.
        val tileScreen = Array(islands.size) { k ->
            val a = FloatArray(2)
            fisheyeScreenPos(
                islands[k].rect.centerX, islands[k].rect.centerY,
                normScaleX, normScaleY, a)
            a
        }
        val sTmp = FloatArray(2)
        val pTmp = FloatArray(2)

        for (k in islands.indices) {
            val rect = islands[k].rect
            val tcx = tileScreen[k][0]
            val tcy = tileScreen[k][1]
            val members = (0 until n).filter { claim[it] == k }
            // Nearest the tile first: by the assigned
            // centre's screen distance to the rect, then by
            // anchor distance to the island centre, then by
            // index — a total order, so the pass is
            // deterministic.
            val ordered = members.sortedWith(
                compareBy(
                    { i: Int ->
                        fisheyeScreenPos(
                            centers[i][0], centers[i][1],
                            normScaleX, normScaleY, sTmp)
                        rectDist(sTmp[0], sTmp[1], tcx, tcy, rect.halfW, rect.halfH)
                    },
                    { i: Int ->
                        val dx = centers[i][0] - rect.centerX
                        val dy = centers[i][1] - rect.centerY
                        dx * dx + dy * dy
                    },
                    { i: Int -> i },
                ),
            )
            for (i in ordered) {
                fisheyeScreenPos(
                    centers[i][0], centers[i][1], normScaleX, normScaleY, sTmp)
                val ox = sTmp[0] - tcx
                val oy = sTmp[1] - tcy
                val qx = ox.coerceIn(-rect.halfW, rect.halfW)
                val qy = oy.coerceIn(-rect.halfH, rect.halfH)
                val ex = ox - qx
                val ey = oy - qy
                val dist0 = hypot(ex, ey)
                if (dist0 < 1e-4f) continue // centre inside the tile: leave it
                val rStart = drawn[i] * Fisheye.size(
                    fisheyeDist(centers[i][0], centers[i][1], normScaleX, normScaleY))
                if (dist0 - rStart <= HUG_MARGIN) continue // already hugging
                val ux = ex / dist0
                val uy = ey / dist0
                // Slide toward the rect in screen space. The
                // bubble's drawn radius changes as it moves,
                // so refine the travel over a fixed four
                // evaluations; the travel never goes negative
                // (a correction past the start abandons the
                // hug — the slot is the fallback). The step is
                // the MIN of the two frames' errors: the
                // working-zoom screen gap (target HUG_MARGIN)
                // and the overview floor (the candidate's
                // anchor-space rect distance must stay at
                // drawn * OVERVIEW_SIZE + OVERVIEW_CUSHION) —
                // whichever binds first stops the slide.
                var travel = dist0 - rStart - HUG_MARGIN
                var cand: FloatArray? = null
                var abandoned = false
                for (round in 0..3) {
                    anchorFromScreen(
                        tcx + ox - ux * travel, tcy + oy - uy * travel,
                        normScaleX, normScaleY, pTmp)
                    val px = pTmp[0]
                    val py = pTmp[1]
                    fisheyeScreenPos(px, py, normScaleX, normScaleY, sTmp)
                    val distP = rectDist(
                        sTmp[0], sTmp[1], tcx, tcy, rect.halfW, rect.halfH)
                    val rP = drawn[i] * Fisheye.size(
                        fisheyeDist(px, py, normScaleX, normScaleY))
                    cand = floatArrayOf(px, py)
                    val errScreen = distP - rP - HUG_MARGIN
                    val aDistP = rectDist(
                        px, py, rect.centerX, rect.centerY, rect.halfW, rect.halfH)
                    val errAnchor = aDistP - drawn[i] * OVERVIEW_SIZE - OVERVIEW_CUSHION
                    if (abs(errScreen) < 0.005f && errAnchor >= -0.005f) break
                    if (abs(errAnchor) < 0.005f && errScreen >= -0.005f) break
                    if (round == 3) break
                    travel += min(errScreen, errAnchor)
                    if (travel < 0f) {
                        abandoned = true
                        break
                    }
                }
                if (abandoned) continue
                val c = cand ?: continue
                // Constraint 1: clear of EVERY tile, judged
                // in true screen terms at the candidate's own
                // drawn radius.
                fisheyeScreenPos(c[0], c[1], normScaleX, normScaleY, sTmp)
                val csx = sTmp[0]
                val csy = sTmp[1]
                val rC = drawn[i] * Fisheye.size(
                    fisheyeDist(c[0], c[1], normScaleX, normScaleY))
                var ok = true
                for (m in islands.indices) {
                    val rm = islands[m].rect
                    val dm = rectDist(
                        csx, csy, tileScreen[m][0], tileScreen[m][1],
                        rm.halfW, rm.halfH)
                    if (dm < rC - 0.002f) {
                        ok = false
                        break
                    }
                    // Overview frame: un-pulled anchor
                    // distance vs the 0.88-size drawn radius.
                    val am = rectDist(c[0], c[1], rm.centerX, rm.centerY, rm.halfW, rm.halfH)
                    if (am < drawn[i] * OVERVIEW_SIZE - 0.002f) {
                        ok = false
                        break
                    }
                }
                if (!ok) continue
                // Constraint 2: pairwise separation from
                // every other item's final position, in true
                // screen terms (refined positions are already
                // in `out`; the rest are still at their
                // lattice centres, so this test can only get
                // stricter as more bubbles refine).
                for (j in 0 until n) {
                    if (j == i) continue
                    fisheyeScreenPos(
                        out[j][0], out[j][1], normScaleX, normScaleY, sTmp)
                    val sep = hypot(csx - sTmp[0], csy - sTmp[1])
                    val rJ = drawn[j] * Fisheye.size(
                        fisheyeDist(out[j][0], out[j][1], normScaleX, normScaleY))
                    if (sep < rC + rJ + HUG_PAIR_MARGIN - 0.002f) {
                        ok = false
                        break
                    }
                    // Overview frame: un-pulled anchor
                    // separation vs the 0.88-size radii.
                    val sepA = hypot(c[0] - out[j][0], c[1] - out[j][1])
                    if (sepA < (drawn[i] + drawn[j]) * OVERVIEW_SIZE - 0.002f) {
                        ok = false
                        break
                    }
                }
                if (!ok) continue // fallback: keep the lattice slot
                out[i][0] = c[0]
                out[i][1] = c[1]
            }
        }
        return out
    }
}
