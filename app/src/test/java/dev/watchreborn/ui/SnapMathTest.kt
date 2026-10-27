package dev.watchreborn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.6.0: quadrant snap placement (pure). Field 10 x 8 anchor
 *  units (half-extents 5 x 4) unless stated. */
class SnapMathTest {

    private val fx = 5f
    private val fy = 4f

    @Test
    fun centreIsFree() {
        assertEquals(SnapMath.Zone.NONE, SnapMath.classify(0f, 0f, fx, fy))
        assertEquals(SnapMath.Zone.NONE, SnapMath.classify(2.7f, 2.1f, fx, fy))
        assertEquals(SnapMath.Zone.NONE, SnapMath.classify(-2.7f, -2.1f, fx, fy))
    }

    @Test
    fun cornersAreQuadrants() {
        assertEquals(SnapMath.Zone.QUADRANT_TL, SnapMath.classify(-4f, -3f, fx, fy))
        assertEquals(SnapMath.Zone.QUADRANT_TR, SnapMath.classify(4f, -3f, fx, fy))
        assertEquals(SnapMath.Zone.QUADRANT_BL, SnapMath.classify(-4f, 3f, fx, fy))
        assertEquals(SnapMath.Zone.QUADRANT_BR, SnapMath.classify(4f, 3f, fx, fy))
    }

    @Test
    fun singleAxisIsHalf() {
        assertEquals(SnapMath.Zone.HALF_LEFT, SnapMath.classify(-4f, 0.5f, fx, fy))
        assertEquals(SnapMath.Zone.HALF_RIGHT, SnapMath.classify(4f, -0.5f, fx, fy))
        assertEquals(SnapMath.Zone.HALF_TOP, SnapMath.classify(0.5f, -3f, fx, fy))
        assertEquals(SnapMath.Zone.HALF_BOTTOM, SnapMath.classify(-0.5f, 3f, fx, fy))
    }

    @Test
    fun thresholdIsStrict() {
        // Exactly at 55% of the half-extent does NOT engage.
        val tx = fx * SnapMath.ENGAGE_FRACTION
        val ty = fy * SnapMath.ENGAGE_FRACTION
        assertEquals(SnapMath.Zone.NONE, SnapMath.classify(tx, ty, fx, fy))
        assertEquals(SnapMath.Zone.QUADRANT_BR, SnapMath.classify(tx + 0.01f, ty + 0.01f, fx, fy))
    }

    @Test
    fun degenerateFieldNeverSnaps() {
        assertEquals(SnapMath.Zone.NONE, SnapMath.classify(9f, 9f, 0f, fy))
        assertEquals(SnapMath.Zone.NONE, SnapMath.classify(9f, 9f, fx, -1f))
    }

    @Test
    fun zoneRectsAndCentres() {
        assertNull(SnapMath.zoneRect(SnapMath.Zone.NONE, fx, fy))
        assertTrue(
            SnapMath.zoneRect(SnapMath.Zone.QUADRANT_TL, fx, fy)!!
                .contentEquals(floatArrayOf(-5f, -4f, 0f, 0f)),
        )
        assertTrue(
            SnapMath.zoneRect(SnapMath.Zone.HALF_BOTTOM, fx, fy)!!
                .contentEquals(floatArrayOf(-5f, 0f, 5f, 4f)),
        )
        assertTrue(
            SnapMath.zoneCenter(SnapMath.Zone.QUADRANT_BR, fx, fy)!!
                .contentEquals(floatArrayOf(2.5f, 2f)),
        )
        assertTrue(
            SnapMath.zoneCenter(SnapMath.Zone.HALF_LEFT, fx, fy)!!
                .contentEquals(floatArrayOf(-2.5f, 0f)),
        )
    }

    @Test
    fun snappedAnchorCentresTileInZone() {
        // Small tile: no clamping needed, centre = zone centre.
        val a = SnapMath.snappedAnchor(SnapMath.Zone.QUADRANT_TL, fx, fy, 1f, 0.6f)!!
        assertEquals(-2.5f, a[0], 0.001f)
        assertEquals(-2f, a[1], 0.001f)
    }

    @Test
    fun snappedAnchorClampsFitFirst() {
        // A tile nearly as wide/tall as a quadrant: its centre
        // must pull back so the whole tile stays in-field.
        val a = SnapMath.snappedAnchor(SnapMath.Zone.QUADRANT_TR, fx, fy, 2.4f, 2.1f)!!
        assertEquals(fx - 2.4f - SnapMath.FIT_MARGIN, a[0], 0.001f)
        assertEquals(-(fy - 2.1f - SnapMath.FIT_MARGIN), a[1], 0.001f)
        // Half snap with a tall tile: x centres in the half,
        // y clamps to the field.
        val b = SnapMath.snappedAnchor(SnapMath.Zone.HALF_RIGHT, fx, fy, 1f, 3.5f)!!
        assertEquals(2.5f, b[0], 0.001f)
        assertEquals(0f, b[1], 0.001f)
        assertNull(SnapMath.snappedAnchor(SnapMath.Zone.NONE, fx, fy, 1f, 1f))
    }
}
