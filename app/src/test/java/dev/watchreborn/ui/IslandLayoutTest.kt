package dev.watchreborn.ui

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandLayoutTest {

    private fun centers(hexes: List<Hex>, scale: Float): List<Pair<Float, Float>> {
        val out = FloatArray(2)
        return hexes.map { h ->
            pointyCenter(h, scale, out)
            out[0] to out[1]
        }
    }

    @Test fun noObstaclesKeepsEveryHomeSlot() {
        val homes = Hex.spiral(37)
        val radii = FloatArray(37) { 0.55f }
        val assigned = IslandLayout.assign(homes, 1f, radii, emptyList())
        assertEquals(homes, assigned)
    }

    @Test fun islandAtCenterDisplacesOnlyBlockedItems() {
        val homes = Hex.spiral(61)
        val radii = FloatArray(61) { 0.55f }
        // A Focus-Bar-sized island centred on the cluster.
        val island = IslandLayout.Obstacle(0f, 0f, 1.55f, 1.05f)
        val assigned = IslandLayout.assign(homes, 1f, radii, listOf(island))

        // Nobody is lost and nobody shares a slot.
        assertEquals(61, assigned.size)
        assertEquals(61, assigned.toSet().size)

        val homeCenters = centers(homes, 1f)
        val assignedCenters = centers(assigned, 1f)
        var moved = 0
        for (i in homes.indices) {
            val wasBlocked = IslandLayout.circleIntersects(
                homeCenters[i].first, homeCenters[i].second, radii[i], island)
            if (wasBlocked) {
                moved++
                // A displaced item must land somewhere clear.
                assertFalse(
                    "item $i still intersects the island",
                    IslandLayout.circleIntersects(
                        assignedCenters[i].first, assignedCenters[i].second, radii[i], island),
                )
                assertTrue("item $i should have moved", assigned[i] != homes[i])
            } else {
                // An unaffected item keeps its exact home slot.
                assertEquals("item $i moved unnecessarily", homes[i], assigned[i])
            }
        }
        assertTrue("the island should displace a ring of bubbles", moved in 6..24)
    }

    @Test fun islandOffCenterLeavesTheCoreAlone() {
        val homes = Hex.spiral(61)
        val radii = FloatArray(61) { 0.55f }
        // Left edge at x=1.9: the core (max |x| = 1) plus its clearance
        // radius (0.55) still falls short of the island, so indices 0..6
        // keep their slots — while slots under the island do move.
        val island = IslandLayout.Obstacle(2.6f, 0f, 0.7f, 0.8f)
        val assigned = IslandLayout.assign(homes, 1f, radii, listOf(island))
        for (i in 0..6) assertEquals(homes[i], assigned[i])
        assertEquals(61, assigned.toSet().size)
        assertTrue(homes.indices.any { assigned[it] != homes[it] })
    }

    @Test fun biggerBubblesNeedMoreClearance() {
        val homes = Hex.spiral(19)
        val island = IslandLayout.Obstacle(0f, 0f, 0.9f, 0.9f)
        val small = IslandLayout.assign(homes, 1f, FloatArray(19) { 0.40f }, listOf(island))
        val large = IslandLayout.assign(homes, 1f, FloatArray(19) { 0.80f }, listOf(island))
        val smallMoved = homes.indices.count { small[it] != homes[it] }
        val largeMoved = homes.indices.count { large[it] != homes[it] }
        assertTrue("large bubbles should displace at least as many", largeMoved >= smallMoved)
        // Every large bubble's assigned centre really is clear at its radius.
        val largeCenters = centers(large, 1f)
        for (i in homes.indices) {
            assertFalse(
                IslandLayout.circleIntersects(
                    largeCenters[i].first, largeCenters[i].second, 0.80f, island),
            )
        }
    }

    @Test fun islandBrushingTheCoreDoesNotEvictTheCentreApp() {
        val homes = Hex.spiral(61)
        val radii = FloatArray(61) { 0.55f }
        // Rect x in [0.5, 2.6]: the centre bubble's clearance circle
        // (r=0.55 around (0,0)) reaches into it, but the centre point
        // itself is outside — the core app must keep its home.
        val island = IslandLayout.Obstacle(1.55f, 0f, 1.05f, 0.80f)
        val assigned = IslandLayout.assign(homes, 1f, radii, listOf(island))
        assertEquals(homes[0], assigned[0])
        assertEquals(61, assigned.toSet().size)
        // The bubble whose home is inside the rect still moves out.
        val idx = homes.indices.first { it != 0 && assigned[it] != homes[it] }
        assertTrue(idx > 0)
    }

    @Test fun islandCoveringTheCentrePointEvictsEvenTheCentreApp() {
        val homes = Hex.spiral(61)
        val radii = FloatArray(61) { 0.55f }
        // Rect x in [-0.4, 1.2], y in [-0.8, 0.8]: covers (0,0) itself.
        val island = IslandLayout.Obstacle(0.4f, 0f, 0.8f, 0.8f)
        val assigned = IslandLayout.assign(homes, 1f, radii, listOf(island))
        assertTrue(assigned[0] != homes[0])
        val c = centers(assigned, 1f)
        assertFalse(
            IslandLayout.circleIntersects(c[0].first, c[0].second, radii[0], island),
        )
        assertEquals(61, assigned.toSet().size)
    }

    @Test fun positionNameReadsTheFieldInNineZones() {
        assertEquals("centre", IslandLayout.positionName(0f, 0f))
        assertEquals("upper", IslandLayout.positionName(0.2f, -2.05f))
        assertEquals("lower", IslandLayout.positionName(0f, 2.05f))
        assertEquals("upper left", IslandLayout.positionName(-1.6f, -1.5f))
        assertEquals("upper right", IslandLayout.positionName(1.6f, -0.8f))
        assertEquals("lower left", IslandLayout.positionName(-0.8f, 1.2f))
        assertEquals("lower right", IslandLayout.positionName(2f, 2f))
        assertEquals("left", IslandLayout.positionName(-3f, 0.4f))
        assertEquals("right", IslandLayout.positionName(3f, -0.4f))
        // The 0.75 dead zone keeps near-centre anchors "centre".
        assertEquals("centre", IslandLayout.positionName(0.75f, -0.75f))
        assertEquals("lower right", IslandLayout.positionName(0.76f, 0.76f))
    }

    @Test fun clampScaleKeepsTheResizeRange() {
        assertEquals(1.0f, IslandLayout.clampScale(1.0f), 0.001f)
        assertEquals(0.6f, IslandLayout.clampScale(0.2f), 0.001f)
        assertEquals(1.8f, IslandLayout.clampScale(4.0f), 0.001f)
        assertEquals(0.6f, IslandLayout.clampScale(0.6f), 0.001f)
        assertEquals(1.8f, IslandLayout.clampScale(1.8f), 0.001f)
        // A corrupt persisted value heals to neutral instead of NaN-ing the field.
        assertEquals(1.0f, IslandLayout.clampScale(Float.NaN), 0.001f)
    }

    @Test fun nextPresetScaleCyclesUpwardAndWraps() {
        assertEquals(0.8f, IslandLayout.nextPresetScale(0.6f), 0.001f)
        assertEquals(1.0f, IslandLayout.nextPresetScale(0.8f), 0.001f)
        assertEquals(1.3f, IslandLayout.nextPresetScale(1.0f), 0.001f)
        assertEquals(1.6f, IslandLayout.nextPresetScale(1.3f), 0.001f)
        assertEquals(0.8f, IslandLayout.nextPresetScale(1.6f), 0.001f)
        // A free-dragged scale advances to the next preset above it.
        assertEquals(1.3f, IslandLayout.nextPresetScale(1.12f), 0.001f)
    }

    @Test fun obstacleForAtTheCentreAddsOnlyTheMarginBand() {
        // κ = 1 on both axes: the band beyond the true rect is
        // REF_DRAWN_RADIUS + CLEARANCE_MARGIN - REF_CLEARANCE_RADIUS
        // = 0.42 + 0.08 - 0.49 = 0.01 — the drawn radius and the
        // solver's own clearance radius cancel, leaving a sliver
        // of the (3.6.5-halved) margin. Centre untouched, rect
        // never shrinks.
        val o = IslandLayout.obstacleFor(0.5f, -1.25f, 1.1f, 0.55f, 1f, 1f)
        assertEquals(0.5f, o.centerX, 0.001f)
        assertEquals(-1.25f, o.centerY, 0.001f)
        assertEquals(1.11f, o.halfW, 0.001f)
        assertEquals(0.56f, o.halfH, 0.001f)
    }

    @Test fun obstacleForWidensEachAxisByItsOwnCompression() {
        // κx = 0.5 (deep rim, radial), κy = 1 (centre-like): the
        // axes are compensated independently — the whole rect is
        // NOT scaled by the worst factor, which is what made the
        // 3.6.3 moat so wide.
        val band = IslandLayout.REF_DRAWN_RADIUS + IslandLayout.CLEARANCE_MARGIN
        val o = IslandLayout.obstacleFor(2f, 3f, 1.1f, 0.55f, 0.5f, 1f)
        assertEquals(2f, o.centerX, 0.001f)
        assertEquals(3f, o.centerY, 0.001f)
        assertEquals(
            (1.1f + band) / 0.5f - IslandLayout.REF_CLEARANCE_RADIUS, o.halfW, 0.001f)
        assertEquals(0.56f, o.halfH, 0.001f)
        assertTrue(o.halfW > 1.1f && o.halfH > 0.55f)
    }

    @Test fun obstacleForFloorsTheCompression() {
        // A pathological compression floors at
        // MIN_PULL_COMPRESSION instead of dividing the field away;
        // compressions above 1 and NaN are not real and read as 1.
        val band = IslandLayout.REF_DRAWN_RADIUS + IslandLayout.CLEARANCE_MARGIN
        val o = IslandLayout.obstacleFor(0f, 0f, 0.9f, 0.9f, 0.05f, 0.05f)
        assertEquals(
            (0.9f + band) / IslandLayout.MIN_PULL_COMPRESSION -
                IslandLayout.REF_CLEARANCE_RADIUS,
            o.halfW, 0.001f)
        assertEquals(
            0.91f, IslandLayout.obstacleFor(0f, 0f, 0.9f, 0.9f, 1.4f, 1.4f).halfW, 0.001f)
        assertEquals(
            0.91f,
            IslandLayout.obstacleFor(0f, 0f, 0.9f, 0.9f, Float.NaN, Float.NaN).halfW,
            0.001f)
    }

    @Test fun axisCompressionsMatchThePullJacobian() {
        // Centre: the pull is the identity there.
        val c = IslandLayout.axisCompressions(0f, 0f, 0f)
        assertEquals(1f, c[0], 0.001f)
        assertEquals(1f, c[1], 0.001f)
        // Due east at d = 0.618 (a rim anchor's distance on the
        // emulator): x is the radial axis, y the tangential one.
        val d = 0.618f
        val e = IslandLayout.axisCompressions(d, d, 0f)
        assertEquals(Fisheye.pulled(d) / d, e[1], 0.001f)
        // Radial = pulled'(d); pulled is quadratic on this table
        // segment, so a wide central difference is near-exact too.
        val radial = (Fisheye.pulled(d + 0.05f) - Fisheye.pulled(d - 0.05f)) / 0.1f
        assertEquals(radial, e[0], 0.01f)
        assertTrue("radial compresses harder than tangential", e[0] < e[1])
        assertTrue("both within the table's real range", e[0] > 0.55f && e[1] < 1f)
        // Due north the axes swap.
        val n = IslandLayout.axisCompressions(d, 0f, d)
        assertEquals(e[1], n[0], 0.001f)
        assertEquals(e[0], n[1], 0.001f)
        // Diagonal: both axes land strictly between the two.
        val g = IslandLayout.axisCompressions(d, d * 0.7071f, d * 0.7071f)
        assertTrue(g[0] > e[0] && g[0] < e[1])
        assertEquals(g[0], g[1], 0.001f)
    }

    @Test fun rimIslandObstacleClearsBubblesForTheFullFootprint() {
        // End-to-end: with the per-axis-compensated obstacle,
        // every assigned slot is clear of the FULL obstacle — and
        // the obstacle is honestly tighter than 3.6.3's blanket
        // inverse-size scaling (which floored at 1/0.45 = 2.22x)
        // while still wider than the nominal rect on both axes.
        val homes = Hex.spiral(61)
        val radii = FloatArray(61) { 0.55f }
        val nominal = IslandLayout.Obstacle(2.05f, 0f, 1.55f, 1.05f)
        // Due-east rim anchor at d = 0.618: κx = pulled'(d) ≈ 0.694
        // (radial), κy = pulled(d)/d ≈ 0.872 (tangential).
        val obstacle = IslandLayout.obstacleFor(2.05f, 0f, 1.55f, 1.05f, 0.694f, 0.872f)
        val assigned = IslandLayout.assign(homes, 1f, radii, listOf(obstacle))
        assertEquals(61, assigned.toSet().size)
        val assignedCenters = centers(assigned, 1f)
        for (i in homes.indices) {
            if (i == 0) continue // the protected centre may stay by design
            assertFalse(
                "item $i intersects the island obstacle",
                IslandLayout.circleIntersects(
                    assignedCenters[i].first, assignedCenters[i].second, radii[i], obstacle),
            )
        }
        assertTrue(obstacle.halfW > nominal.halfW && obstacle.halfW < nominal.halfW / 0.45f)
        assertTrue(obstacle.halfH > nominal.halfH && obstacle.halfH < nominal.halfH / 0.45f)
    }

    @Test fun denseFieldMoatUnderThePullBandObstacle() {
        // The dense-field guard for the 3.6.4 tightening, kept
        // current with 3.6.5's halved margin — the shape of a
        // real phone, where every slot is claimed, so the moat
        // around an island is set by the obstacle, not by which
        // slots happen to be empty. 127 populated homes, the
        // rim scenario's island; the gap is the nearest bubble's
        // edge distance to the island's TRUE rect.
        val homes = Hex.spiral(127)
        val radii = FloatArray(127) { 0.49f }
        val trueRect = IslandLayout.Obstacle(2.05f, 0f, 1.55f, 1.05f)
        fun minGap(obstacle: IslandLayout.Obstacle): Float {
            val assigned = IslandLayout.assign(homes, 1f, radii, listOf(obstacle))
            val cs = centers(assigned, 1f)
            var best = Float.MAX_VALUE
            for (i in homes.indices) {
                val dx = maxOf(0f, kotlin.math.abs(cs[i].first - trueRect.centerX) - trueRect.halfW)
                val dy = maxOf(0f, kotlin.math.abs(cs[i].second - trueRect.centerY) - trueRect.halfH)
                val g = kotlin.math.sqrt(dx * dx + dy * dy) - radii[i]
                if (g < best) best = g
            }
            return best
        }
        // 3.6.3's construction at this anchor (its fisheye factor
        // floored at 0.45): the moat was nearly two slots wide.
        val retired = IslandLayout.Obstacle(2.05f, 0f, 1.55f / 0.45f, 1.05f / 0.45f)
        val gapBefore = minGap(retired)
        val gapAfter = minGap(
            IslandLayout.obstacleFor(2.05f, 0f, 1.55f, 1.05f, 0.694f, 0.872f))
        // Measured on the solver: 1.91 under the retired 3.6.3
        // construction, 1.06 under 3.6.4's 0.16 margin, 1.01
        // under 3.6.5's 0.08. Halving the margin moves the band
        // edge ~0.09-0.12 per axis, but the minimum gap follows
        // by only 0.05 — the rest is absorbed by hex-row
        // quantization (0.866 spacing): the gap sits at the
        // construction's floor (face excess + clearance radius,
        // quantized to the next hex row) either way.
        assertTrue("retired moat was wide (was $gapBefore)", gapBefore > 1.8f)
        assertTrue("moat at least halved-ish (was $gapAfter)", gapAfter < gapBefore - 0.5f)
        assertTrue("moat at the measured 3.6.5 floor (was $gapAfter)", gapAfter in 0.95f..1.05f)
        assertTrue("clearance stays positive (was $gapAfter)", gapAfter > 0f)
    }

    @Test fun gravityScaleKeepsAssignmentsUniqueAndClear() {
        val homes = Hex.spiral(91)
        val radii = FloatArray(91) { i -> if (i == 0) 0.80f else 0.58f }
        val island = IslandLayout.Obstacle(0f, 2.05f, 1.55f, 1.05f)
        val assigned = IslandLayout.assign(homes, 1.10f, radii, listOf(island))
        assertEquals(91, assigned.size)
        assertEquals(91, assigned.toSet().size)
        val assignedCenters = centers(assigned, 1.10f)
        for (i in homes.indices) {
            assertFalse(
                "gravity item $i intersects the island",
                IslandLayout.circleIntersects(
                    assignedCenters[i].first, assignedCenters[i].second, radii[i], island),
            )
        }
    }

    // ── 3.7.0: off-lattice island hugging ─────────────────────

    private fun centersArray(hexes: List<Hex>, scale: Float): Array<FloatArray> =
        Array(hexes.size) { i ->
            val a = FloatArray(2)
            pointyCenter(hexes[i], scale, a)
            a
        }

    /** True screen-space edge-to-edge gap between a bubble
     *  and a tile: rect distance in the draw pass's pulled
     *  frame minus the bubble's drawn radius there — the
     *  quantity refineHug targets. */
    private fun screenGapOf(
        cx: Float, cy: Float, clearanceR: Float,
        rect: IslandLayout.Obstacle, snx: Float, sny: Float,
    ): Float {
        val s = FloatArray(2)
        IslandLayout.fisheyeScreenPos(cx, cy, snx, sny, s)
        val sc = FloatArray(2)
        IslandLayout.fisheyeScreenPos(rect.centerX, rect.centerY, snx, sny, sc)
        val dx = maxOf(0f, abs(s[0] - sc[0]) - rect.halfW)
        val dy = maxOf(0f, abs(s[1] - sc[1]) - rect.halfH)
        val d = hypot(cx * snx, cy * sny)
        return hypot(dx, dy) - IslandLayout.drawnRadius(clearanceR) * Fisheye.size(d)
    }

    /** Anchor-space edge-to-edge gap (the 3.6.x lineage
     *  measure): rect distance in pitch units minus the
     *  clearance radius. */
    private fun anchorGapOf(
        cx: Float, cy: Float, clearanceR: Float, rect: IslandLayout.Obstacle,
    ): Float {
        val dx = maxOf(0f, abs(cx - rect.centerX) - rect.halfW)
        val dy = maxOf(0f, abs(cy - rect.centerY) - rect.halfH)
        return hypot(dx, dy) - clearanceR
    }

    private fun hugIsland(
        rect: IslandLayout.Obstacle,
        obstacle: IslandLayout.Obstacle,
    ) = IslandLayout.HugIsland(rect, obstacle)

    @Test
    fun hugIsDeterministicAndLeavesTheFarFieldOnLattice() {
        val homes = Hex.spiral(90)
        val radii = FloatArray(90) { 0.49f }
        // One island mid-field (κ = 1 construction; judged in
        // a frame where the island sits near the centre, so
        // the two agree).
        val trueRect = IslandLayout.Obstacle(0.5f, 0.5f, 1.2f, 0.8f)
        val obstacle = IslandLayout.obstacleFor(0.5f, 0.5f, 1.2f, 0.8f, 1f, 1f)
        val assigned = IslandLayout.assign(homes, 1f, radii, listOf(obstacle))
        val centers = centersArray(assigned, 1f)
        val isl = listOf(hugIsland(trueRect, obstacle))

        val snapBefore = centers.map { it.toList() }
        val refined = IslandLayout.refineHug(centers, radii, isl, 0.3f, 0.3f)
        val refined2 = IslandLayout.refineHug(centers, radii, isl, 0.3f, 0.3f)
        // Determinism: byte-identical reruns.
        for (i in centers.indices) {
            assertEquals(refined[i][0], refined2[i][0], 0f)
            assertEquals(refined[i][1], refined2[i][1], 0f)
        }
        // The input is never mutated.
        for (i in centers.indices) {
            assertEquals(snapBefore[i][0], centers[i][0], 0f)
            assertEquals(snapBefore[i][1], centers[i][1], 0f)
        }
        // Regression fence: every bubble outside the
        // neighbourhood box keeps its exact pre-hug centre,
        // and so does the protected centre item.
        val boxX = obstacle.halfW + IslandLayout.HUG_NEIGHBOURHOOD
        val boxY = obstacle.halfH + IslandLayout.HUG_NEIGHBOURHOOD
        var refinedCount = 0
        for (i in centers.indices) {
            val outside = abs(centers[i][0] - obstacle.centerX) > boxX ||
                abs(centers[i][1] - obstacle.centerY) > boxY
            if (outside || i == 0) {
                assertEquals(
                    "fence broke at $i", centers[i][0], refined[i][0], 0f)
                assertEquals(
                    "fence broke at $i", centers[i][1], refined[i][1], 0f)
            } else if (refined[i][0] != centers[i][0] || refined[i][1] != centers[i][1]) {
                refinedCount++
            }
            // No bubble overlaps the tile in true screen
            // terms (the protected centre item may sit wherever
            // assign put it — skip it).
            if (i != 0) {
                val gap = screenGapOf(
                    refined[i][0], refined[i][1], radii[i], trueRect, 0.3f, 0.3f)
                assertTrue("item $i overlaps the tile (gap $gap)", gap >= -0.02f)
            }
        }
        assertTrue("expected some bubbles to hug, got $refinedCount", refinedCount in 1..24)
        // Moved bubbles actually landed AT the hug margin in
        // true screen terms.
        for (i in centers.indices) {
            if (i == 0) continue
            if (refined[i][0] != centers[i][0] || refined[i][1] != centers[i][1]) {
                val gap = screenGapOf(
                    refined[i][0], refined[i][1], radii[i], trueRect, 0.3f, 0.3f)
                assertTrue(
                    "moved item $i should sit at the margin, gap $gap",
                    gap in 0.07f..0.14f)
            }
        }
    }

    @Test
    fun hugClosesTheDenseFieldMoat() {
        // THE 3.7.0 measurement contract — the same dense
        // scenario as denseFieldMoatUnderThePullBandObstacle
        // (127 homes, scale 1, radii 0.49, the rim island):
        // the lattice solver's 1.01-pitch anchor-space moat
        // must close to <= 0.35 pitch with the hug, in BOTH
        // the lineage (anchor) measure and true screen terms,
        // with no tile intersection and no pairwise overlap.
        val homes = Hex.spiral(127)
        val radii = FloatArray(127) { 0.49f }
        val trueRect = IslandLayout.Obstacle(2.05f, 0f, 1.55f, 1.05f)
        val obstacle = IslandLayout.obstacleFor(2.05f, 0f, 1.55f, 1.05f, 0.694f, 0.872f)
        val assigned = IslandLayout.assign(homes, 1f, radii, listOf(obstacle))
        val centers = centersArray(assigned, 1f)
        var anchorBefore = Float.MAX_VALUE
        for (i in homes.indices) {
            val g = anchorGapOf(centers[i][0], centers[i][1], radii[i], trueRect)
            if (g < anchorBefore) anchorBefore = g
        }
        assertTrue(
            "pre-hug anchor moat should be ~1.01 (was $anchorBefore)",
            anchorBefore in 0.95f..1.05f)

        val refined = IslandLayout.refineHug(
            centers, radii, listOf(hugIsland(trueRect, obstacle)), 0.3015f, 0.3015f)
        var anchorAfter = Float.MAX_VALUE
        var screenAfter = Float.MAX_VALUE
        for (i in homes.indices) {
            val ga = anchorGapOf(refined[i][0], refined[i][1], radii[i], trueRect)
            if (ga < anchorAfter) anchorAfter = ga
            val gs = screenGapOf(
                refined[i][0], refined[i][1], radii[i], trueRect, 0.3015f, 0.3015f)
            if (gs < screenAfter) screenAfter = gs
            assertTrue(
                "item $i intersects the tile after the hug (screen gap $gs)",
                gs >= -0.02f)
        }
        assertTrue(
            "hug closes the anchor moat to <= 0.35 (was $anchorAfter)",
            anchorAfter <= 0.35f)
        // The anchor moat's floor is the OVERVIEW bound, not
        // zero: at the rim the drawn bubble shrinks toward a
        // speck, so a hugged bubble's centre may sit inside
        // the 0.49 clearance disc in anchor terms — but its
        // anchor-space rect distance must still clear its
        // overview drawn radius (0.88 of nominal), or the
        // overview draw would overlap the tile.
        for (i in homes.indices) {
            val dx = maxOf(0f, abs(refined[i][0] - trueRect.centerX) - trueRect.halfW)
            val dy = maxOf(0f, abs(refined[i][1] - trueRect.centerY) - trueRect.halfH)
            val aDist = hypot(dx, dy)
            assertTrue(
                "item $i would overlap the tile in overview (anchor dist $aDist)",
                aDist >= IslandLayout.drawnRadius(radii[i]) * 0.88f - 0.005f)
        }
        assertTrue(
            "hug closes the screen moat to <= 0.35 (was $screenAfter)",
            screenAfter <= 0.35f)

        // Regression fence on the shared scenario: bubbles
        // outside the neighbourhood box are byte-identical.
        val boxX = obstacle.halfW + IslandLayout.HUG_NEIGHBOURHOOD
        val boxY = obstacle.halfH + IslandLayout.HUG_NEIGHBOURHOOD
        for (i in homes.indices) {
            val outside = abs(centers[i][0] - obstacle.centerX) > boxX ||
                abs(centers[i][1] - obstacle.centerY) > boxY
            if (outside) {
                assertEquals("dense fence broke at $i", centers[i][0], refined[i][0], 0f)
                assertEquals("dense fence broke at $i", centers[i][1], refined[i][1], 0f)
            }
        }

        // No pairwise overlap across the refined set, judged
        // in true screen terms: separation at least the sum
        // of the drawn radii there.
        val si = FloatArray(2)
        val sj = FloatArray(2)
        for (i in homes.indices) {
            IslandLayout.fisheyeScreenPos(
                refined[i][0], refined[i][1], 0.3015f, 0.3015f, si)
            val di = hypot(refined[i][0] * 0.3015f, refined[i][1] * 0.3015f)
            val ri = IslandLayout.drawnRadius(radii[i]) * Fisheye.size(di)
            for (j in i + 1 until homes.size) {
                IslandLayout.fisheyeScreenPos(
                    refined[j][0], refined[j][1], 0.3015f, 0.3015f, sj)
                val dj = hypot(refined[j][0] * 0.3015f, refined[j][1] * 0.3015f)
                val rj = IslandLayout.drawnRadius(radii[j]) * Fisheye.size(dj)
                val sep = hypot(si[0] - sj[0], si[1] - sj[1])
                assertTrue(
                    "items $i/$j overlap after the hug: sep $sep < ${ri + rj}",
                    sep >= ri + rj - 0.005f)
                // And in the overview frame: anchor separation
                // vs the 0.88-size drawn radii.
                val sepA = hypot(refined[i][0] - refined[j][0], refined[i][1] - refined[j][1])
                val needA = (IslandLayout.drawnRadius(radii[i]) +
                    IslandLayout.drawnRadius(radii[j])) * 0.88f
                assertTrue(
                    "items $i/$j overlap in overview: sep $sepA < $needA",
                    sepA >= needA - 0.005f)
            }
        }
    }

    @Test
    fun hugNeverMovesTheProtectedCentre() {
        val homes = Hex.spiral(30)
        val radii = FloatArray(30) { 0.49f }
        // The tile covers the centre point, so assign evicts
        // even item 0 — but refineHug must still never refine
        // the protected index itself.
        val trueRect = IslandLayout.Obstacle(0f, 0f, 1.2f, 0.9f)
        val obstacle = IslandLayout.obstacleFor(0f, 0f, 1.2f, 0.9f, 1f, 1f)
        val assigned = IslandLayout.assign(homes, 1f, radii, listOf(obstacle))
        val centers = centersArray(assigned, 1f)
        val refined = IslandLayout.refineHug(
            centers, radii, listOf(hugIsland(trueRect, obstacle)), 0.29f, 0.29f)
        assertEquals(centers[0][0], refined[0][0], 0f)
        assertEquals(centers[0][1], refined[0][1], 0f)
    }

    @Test
    fun hugInGravityKeepsWeightedClearance() {
        // Gravity shape: lattice scale 1.10, weight-scaled
        // clearance radii (hero bubbles at 0.80, the rest at
        // 0.58 — the same radii the existing gravity solver
        // test uses), a south island, and a portrait fisheye
        // frame (the anchor->normalized scales a tall phone
        // view produces). The obstacle's compressions come
        // from the solver's own Jacobian helper at the
        // island's normalized anchor, exactly as the view
        // computes them. Heroes must hug at THEIR drawn
        // radii: no refined bubble may overlap the tile, and
        // claimed bubbles' true screen gaps must only shrink.
        val homes = Hex.spiral(91)
        val radii = FloatArray(91) { i -> if (i % 13 == 0) 0.80f else 0.58f }
        val trueRect = IslandLayout.Obstacle(0f, 3.1f, 1.55f, 1.05f)
        val snx = 0.3015f
        val sny = 0.1586f
        val dN = hypot(trueRect.centerX * snx, trueRect.centerY * sny)
        val kk = IslandLayout.axisCompressions(
            dN, trueRect.centerX * snx, trueRect.centerY * sny)
        val obstacle = IslandLayout.obstacleFor(
            trueRect.centerX, trueRect.centerY, trueRect.halfW, trueRect.halfH, kk[0], kk[1])
        val assigned = IslandLayout.assign(homes, 1.10f, radii, listOf(obstacle))
        val centers = centersArray(assigned, 1.10f)
        val isl = listOf(hugIsland(trueRect, obstacle))
        val refined = IslandLayout.refineHug(centers, radii, isl, snx, sny)
        val refined2 = IslandLayout.refineHug(centers, radii, isl, snx, sny)
        for (i in homes.indices) {
            assertEquals(refined[i][0], refined2[i][0], 0f)
            assertEquals(refined[i][1], refined2[i][1], 0f)
        }
        val boxX = obstacle.halfW + IslandLayout.HUG_NEIGHBOURHOOD
        val boxY = obstacle.halfH + IslandLayout.HUG_NEIGHBOURHOOD
        var claimed = 0
        var moved = 0
        var gapSumBefore = 0f
        var gapSumAfter = 0f
        for (i in homes.indices) {
            val gapBefore = screenGapOf(
                centers[i][0], centers[i][1], radii[i], trueRect, snx, sny)
            val gapAfter = screenGapOf(
                refined[i][0], refined[i][1], radii[i], trueRect, snx, sny)
            assertTrue(
                "gravity item $i overlaps the tile (gap $gapAfter)", gapAfter >= -0.02f)
            assertTrue(
                "gravity item $i's gap grew: $gapBefore -> $gapAfter",
                gapAfter <= gapBefore + 0.005f)
            val outside = abs(centers[i][0] - obstacle.centerX) > boxX ||
                abs(centers[i][1] - obstacle.centerY) > boxY
            if (outside || i == 0) {
                assertEquals("gravity fence broke at $i", centers[i][0], refined[i][0], 0f)
                assertEquals("gravity fence broke at $i", centers[i][1], refined[i][1], 0f)
            } else {
                claimed++
                gapSumBefore += gapBefore
                gapSumAfter += gapAfter
                if (refined[i][0] != centers[i][0] || refined[i][1] != centers[i][1]) {
                    moved++
                }
            }
        }
        assertTrue("expected claimed bubbles in gravity, got $claimed", claimed >= 4)
        assertTrue("expected some gravity bubbles to hug, got $moved", moved >= 2)
        assertTrue(
            "claimed gaps should shrink overall: $gapSumBefore -> $gapSumAfter",
            gapSumAfter <= gapSumBefore - 0.03f * claimed)
    }

    @Test
    fun hugFallsBackToTheSlotWhenTheHugWouldCrowd() {
        // Two bubbles stacked north-east of a tile in the
        // centre frame. The nearer one hugs; the farther
        // one's hug spot is exactly where the first one
        // landed, so it must KEEP its lattice slot rather
        // than overlap (constraint 2 + fallback).
        val trueRect = IslandLayout.Obstacle(0f, 0f, 1f, 1f)
        val obstacle = IslandLayout.obstacleFor(0f, 0f, 1f, 1f, 1f, 1f)
        val centers = arrayOf(
            floatArrayOf(-4f, -4f), // protected stand-in, far away
            floatArrayOf(1f, 1.9f),
            floatArrayOf(2.2f, 2.2f),
        )
        val radii = FloatArray(3) { 0.49f }
        val refined = IslandLayout.refineHug(
            centers, radii, listOf(hugIsland(trueRect, obstacle)), 0.3f, 0.3f)
        // Item 1 slid to its hug spot (measured from the
        // shipped implementation; the hug is deterministic,
        // so this pins the slide + the radius-refinement
        // rounds exactly).
        assertEquals(0.967f, refined[1][0], 0.01f)
        assertEquals(1.523f, refined[1][1], 0.01f)
        // Item 2 fell back to its exact slot.
        assertEquals(2.2f, refined[2][0], 0f)
        assertEquals(2.2f, refined[2][1], 0f)
    }
}
