package dev.watchreborn.ui

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
        // = 0.42 + 0.16 - 0.49 = 0.09 — the drawn radius and the
        // solver's own clearance radius cancel, leaving (most of)
        // the margin. Centre untouched, rect never shrinks.
        val o = IslandLayout.obstacleFor(0.5f, -1.25f, 1.1f, 0.55f, 1f, 1f)
        assertEquals(0.5f, o.centerX, 0.001f)
        assertEquals(-1.25f, o.centerY, 0.001f)
        assertEquals(1.19f, o.halfW, 0.001f)
        assertEquals(0.64f, o.halfH, 0.001f)
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
        assertEquals(0.64f, o.halfH, 0.001f)
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
            0.99f, IslandLayout.obstacleFor(0f, 0f, 0.9f, 0.9f, 1.4f, 1.4f).halfW, 0.001f)
        assertEquals(
            0.99f,
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

    @Test fun denseFieldMoatIsHalvedByThePullBandObstacle() {
        // The dense-field guard for the 3.6.4 tightening — the
        // shape of a real phone, where every slot is claimed, so
        // the moat around an island is set by the obstacle, not
        // by which slots happen to be empty. 127 populated homes,
        // the rim scenario's island; the gap is the nearest
        // bubble's edge distance to the island's TRUE rect.
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
        // Measured on the solver: 1.91 before, 1.06 after — the
        // after value is the construction's floor (face excess +
        // clearance radius, quantized to the next hex row).
        assertTrue("retired moat was wide (was $gapBefore)", gapBefore > 1.8f)
        assertTrue("moat at least halved-ish (was $gapAfter)", gapAfter < gapBefore - 0.5f)
        assertTrue("moat at the construction floor (was $gapAfter)", gapAfter < 1.10f)
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
}
