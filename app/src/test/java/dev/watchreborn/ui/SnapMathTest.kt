package dev.watchreborn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.6.1: snap classification lives in canvas px (the full cluster
 * area between header and dock). The canvas used below is
 * (0, 100) → (1080, 2300): centre (540, 1200), half extents
 * (540, 1100), engage thresholds at ±0.55 of the half extents —
 * x past 243 / 837, y past 595 / 1805.
 */
class SnapMathTest {

    private fun classify(px: Float, py: Float) =
        SnapMath.classify(px, py, 0f, 100f, 1080f, 2300f)

    @Test
    fun centreIsFree() {
        assertEquals(SnapMath.Zone.NONE, classify(540f, 1200f))
        assertEquals(SnapMath.Zone.NONE, classify(500f, 1100f))
        // Just inside both thresholds stays free.
        assertEquals(SnapMath.Zone.NONE, classify(244f, 596f))
    }

    @Test
    fun cornersAreQuadrants() {
        assertEquals(SnapMath.Zone.QUADRANT_TL, classify(100f, 200f))
        assertEquals(SnapMath.Zone.QUADRANT_TR, classify(1000f, 200f))
        assertEquals(SnapMath.Zone.QUADRANT_BL, classify(100f, 2200f))
        assertEquals(SnapMath.Zone.QUADRANT_BR, classify(1000f, 2200f))
        // The glass corners themselves engage — full stop.
        assertEquals(SnapMath.Zone.QUADRANT_TL, classify(0f, 100f))
        assertEquals(SnapMath.Zone.QUADRANT_BR, classify(1080f, 2300f))
    }

    @Test
    fun singleAxisIsHalf() {
        assertEquals(SnapMath.Zone.HALF_LEFT, classify(100f, 1200f))
        assertEquals(SnapMath.Zone.HALF_RIGHT, classify(1000f, 1200f))
        assertEquals(SnapMath.Zone.HALF_TOP, classify(540f, 200f))
        assertEquals(SnapMath.Zone.HALF_BOTTOM, classify(540f, 2200f))
    }

    @Test
    fun thresholdIsStrict() {
        // Exactly 0.55 of the half extent is NOT past (strict >):
        // x = 540 − 0.55·540 = 243, y = 1200 − 0.55·1100 = 595.
        assertEquals(SnapMath.Zone.NONE, classify(243f, 595f))
        assertEquals(SnapMath.Zone.HALF_LEFT, classify(242f, 1200f))
        assertEquals(SnapMath.Zone.QUADRANT_TL, classify(242f, 594f))
    }

    @Test
    fun degenerateCanvasNeverEngages() {
        assertEquals(
            SnapMath.Zone.NONE,
            SnapMath.classify(10f, 10f, 100f, 100f, 100f, 500f),
        )
        assertEquals(
            SnapMath.Zone.NONE,
            SnapMath.classify(10f, 10f, 0f, 300f, 500f, 300f),
        )
    }

    @Test
    fun zoneRectsTileTheCanvas() {
        val l = 0f; val t = 100f; val r = 1080f; val b = 2300f
        assertRect(
            floatArrayOf(0f, 100f, 540f, 1200f),
            SnapMath.zoneRect(SnapMath.Zone.QUADRANT_TL, l, t, r, b),
        )
        assertRect(
            floatArrayOf(540f, 1200f, 1080f, 2300f),
            SnapMath.zoneRect(SnapMath.Zone.QUADRANT_BR, l, t, r, b),
        )
        assertRect(
            floatArrayOf(0f, 100f, 540f, 2300f),
            SnapMath.zoneRect(SnapMath.Zone.HALF_LEFT, l, t, r, b),
        )
        assertRect(
            floatArrayOf(0f, 1200f, 1080f, 2300f),
            SnapMath.zoneRect(SnapMath.Zone.HALF_BOTTOM, l, t, r, b),
        )
        assertNull(SnapMath.zoneRect(SnapMath.Zone.NONE, l, t, r, b))
        // Quadrants abut at the midlines with no gaps: TL's right
        // edge is TR's left edge, TL's bottom is BL's top.
        val tl = SnapMath.zoneRect(SnapMath.Zone.QUADRANT_TL, l, t, r, b)!!
        val tr = SnapMath.zoneRect(SnapMath.Zone.QUADRANT_TR, l, t, r, b)!!
        val bl = SnapMath.zoneRect(SnapMath.Zone.QUADRANT_BL, l, t, r, b)!!
        assertEquals(tl[2], tr[0], 0f)
        assertEquals(tl[3], bl[1], 0f)
    }

    @Test
    fun zoneCentres() {
        val l = 0f; val t = 100f; val r = 1080f; val b = 2300f
        assertRect(
            floatArrayOf(270f, 650f),
            SnapMath.zoneCenter(SnapMath.Zone.QUADRANT_TL, l, t, r, b),
        )
        assertRect(
            floatArrayOf(810f, 1200f),
            SnapMath.zoneCenter(SnapMath.Zone.HALF_RIGHT, l, t, r, b),
        )
        assertNull(SnapMath.zoneCenter(SnapMath.Zone.NONE, l, t, r, b))
    }

    @Test
    fun siblingsCompleteTheLayout() {
        assertEquals(
            setOf(
                SnapMath.Zone.QUADRANT_TR,
                SnapMath.Zone.QUADRANT_BL,
                SnapMath.Zone.QUADRANT_BR,
            ),
            SnapMath.siblingZones(SnapMath.Zone.QUADRANT_TL).toSet(),
        )
        assertEquals(
            listOf(SnapMath.Zone.HALF_RIGHT),
            SnapMath.siblingZones(SnapMath.Zone.HALF_LEFT),
        )
        assertTrue(SnapMath.siblingZones(SnapMath.Zone.NONE).isEmpty())
    }

    @Test
    fun clampKeepsPlaceablePoints() {
        // The Vitals-sized tile from the emulator seed: half extents
        // (1.2097, 0.6048) against the view field (2.007, 4.461) —
        // limits (0.677, 3.736); a point inside passes through.
        val out = SnapMath.clampToField(0.5f, -2.0f, 2.007f, 4.461f, 1.2097f, 0.6048f)
        assertEquals(0.5f, out[0], 1e-4f)
        assertEquals(-2.0f, out[1], 1e-4f)
    }

    @Test
    fun clampPullsToNearestPlaceablePoint() {
        // A canvas-quadrant centre can sit outside the placeable
        // field (wide tile, or an overview camera): the clamp trims
        // to the nearest placeable point, never re-centres.
        val out = SnapMath.clampToField(1.0035f, -2.2305f, 2.007f, 4.461f, 1.2097f, 0.6048f)
        assertEquals(0.6773f, out[0], 1e-3f)
        assertEquals(-2.2305f, out[1], 1e-4f)
        // A tile wider than the field pins that axis to 0.
        val wide = SnapMath.clampToField(0.9f, 0f, 2.007f, 4.461f, 2.5f, 0.5f)
        assertEquals(0f, wide[0], 0f)
    }

    private fun assertRect(expected: FloatArray, actual: FloatArray?) {
        assertTrue(actual != null)
        assertEquals(expected.size, actual!!.size)
        for (i in expected.indices) {
            assertEquals("index $i", expected[i], actual[i], 1e-4f)
        }
    }
}
