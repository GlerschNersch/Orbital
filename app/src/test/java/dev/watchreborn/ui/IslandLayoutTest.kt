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

    @Test fun obstacleForIsIdentityAtTheCentre() {
        val o = IslandLayout.obstacleFor(0.5f, -1.25f, 1.1f, 0.55f, 1f)
        assertEquals(0.5f, o.centerX, 0.001f)
        assertEquals(-1.25f, o.centerY, 0.001f)
        assertEquals(1.1f, o.halfW, 0.001f)
        assertEquals(0.55f, o.halfH, 0.001f)
    }

    @Test fun obstacleForInflatesByTheInverseFalloff() {
        // At a 0.5 fisheye factor the tile renders twice as wide as
        // the world rect the old solver cleared for — so the
        // obstacle doubles, centre untouched.
        val o = IslandLayout.obstacleFor(2f, 3f, 1.1f, 0.55f, 0.5f)
        assertEquals(2f, o.centerX, 0.001f)
        assertEquals(3f, o.centerY, 0.001f)
        assertEquals(2.2f, o.halfW, 0.001f)
        assertEquals(1.1f, o.halfH, 0.001f)
    }

    @Test fun obstacleForCapsTheInflation() {
        // The fisheye table runs to 0 at the extreme rim; the
        // compensation floors at MIN_COMPENSATED_FISHEYE instead of
        // dividing by ~0 and clearing the whole field.
        val o = IslandLayout.obstacleFor(0f, 0f, 0.9f, 0.9f, 0.05f)
        assertEquals(
            0.9f / IslandLayout.MIN_COMPENSATED_FISHEYE, o.halfW, 0.001f)
        // Factors above 1 and NaN are not real falloffs: no change.
        assertEquals(0.9f, IslandLayout.obstacleFor(0f, 0f, 0.9f, 0.9f, 1.4f).halfW, 0.001f)
        assertEquals(0.9f, IslandLayout.obstacleFor(0f, 0f, 0.9f, 0.9f, Float.NaN).halfW, 0.001f)
    }

    @Test fun rimIslandObstacleClearsBubblesForTheFullFootprint() {
        // End-to-end: with the inflated obstacle, every assigned
        // slot is clear of the FULL rect, not just the nominal one.
        val homes = Hex.spiral(61)
        val radii = FloatArray(61) { 0.55f }
        val nominal = IslandLayout.Obstacle(2.05f, 0f, 1.55f, 1.05f)
        val inflated = IslandLayout.obstacleFor(2.05f, 0f, 1.55f, 1.05f, 0.6f)
        val assigned = IslandLayout.assign(homes, 1f, radii, listOf(inflated))
        assertEquals(61, assigned.toSet().size)
        val assignedCenters = centers(assigned, 1f)
        for (i in homes.indices) {
            if (i == 0) continue // the protected centre may stay by design
            assertFalse(
                "item $i intersects the inflated island",
                IslandLayout.circleIntersects(
                    assignedCenters[i].first, assignedCenters[i].second, radii[i], inflated),
            )
        }
        // And the inflation has teeth: the nominal rect alone would
        // have left at least the inflated-only ring occupied.
        assertTrue(inflated.halfW > nominal.halfW)
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
