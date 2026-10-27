package dev.watchreborn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RingMathTest {

    @Test fun centerItemIsNotOnARing() {
        val loc = RingMath.locate(0, 6, 4)
        assertEquals(-1, loc.ring)
        assertEquals(0, loc.slot)
    }

    @Test fun orbitalsRingBoundaries() {
        // Orbitals capacities: 6, 10, 14, 18, … starting at indices 1, 7, 17, 31.
        assertEquals(RingMath.Loc(0, 0, 6, 1), RingMath.locate(1, 6, 4))
        assertEquals(RingMath.Loc(0, 5, 6, 1), RingMath.locate(6, 6, 4))
        assertEquals(RingMath.Loc(1, 0, 10, 7), RingMath.locate(7, 6, 4))
        assertEquals(RingMath.Loc(1, 9, 10, 7), RingMath.locate(16, 6, 4))
        assertEquals(RingMath.Loc(2, 0, 14, 17), RingMath.locate(17, 6, 4))
        assertEquals(RingMath.Loc(3, 0, 18, 31), RingMath.locate(31, 6, 4))
    }

    @Test fun dockArcBoundaries() {
        // Dock arc capacities: 4, 6, 8, 10, … starting at indices 1, 5, 11, 19.
        assertEquals(RingMath.Loc(0, 3, 4, 1), RingMath.locate(4, 4, 2))
        assertEquals(RingMath.Loc(1, 0, 6, 5), RingMath.locate(5, 4, 2))
        assertEquals(RingMath.Loc(2, 0, 8, 11), RingMath.locate(11, 4, 2))
        assertEquals(RingMath.Loc(3, 0, 10, 19), RingMath.locate(19, 4, 2))
    }

    @Test fun everyIndexLandsInsideItsRing() {
        for (total in intArrayOf(2, 17, 31, 181, 400)) {
            for (i in 1 until total) {
                val loc = RingMath.locate(i, 6, 4)
                assertTrue("slot in range for $i", loc.slot in 0 until loc.ringSize)
                assertEquals(i, loc.startIndex + loc.slot)
            }
        }
    }

    @Test fun ringCountCoversTheWholeCatalog() {
        // 181 items (Matt's grid): orbitals rings 6+10+14+18+22+26+30+34+38 = 198 ≥ 180.
        assertEquals(9, RingMath.ringCount(181, 6, 4))
        assertEquals(199, RingMath.capacityOf(9, 6, 4))
        assertEquals(0, RingMath.ringCount(1, 6, 4))
        assertEquals(1, RingMath.ringCount(7, 6, 4))
        // Dock arcs 4+6+…+26 = 180 exactly for the same catalog.
        assertEquals(12, RingMath.ringCount(181, 4, 2))
        assertEquals(181, RingMath.capacityOf(12, 4, 2))
    }

}
