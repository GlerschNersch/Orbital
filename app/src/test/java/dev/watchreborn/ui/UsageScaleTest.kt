package dev.watchreborn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageScaleTest {

    @Test fun noUsageAnywhereIsNeutral() {
        assertEquals(1f, UsageScale.factor(0f, 0f), 0.001f)
        assertEquals(1f, UsageScale.factor(5f, 0f), 0.001f)
    }

    @Test fun neverUsedSitsAtMinimum() {
        assertEquals(UsageScale.MIN, UsageScale.factor(0f, 40f), 0.001f)
    }

    @Test fun mostUsedHitsMaximum() {
        assertEquals(UsageScale.MAX, UsageScale.factor(40f, 40f), 0.001f)
        assertEquals(UsageScale.MAX, UsageScale.factor(3f, 3f), 0.001f)
    }

    @Test fun growthIsMonotonic() {
        var prev = 0f
        for (s in listOf(0f, 1f, 2f, 5f, 10f, 25f, 60f)) {
            val f = UsageScale.factor(s, 60f)
            assertTrue("factor($s)=$f should be >= $prev", f >= prev)
            prev = f
        }
    }

    @Test fun lightUseAlreadyGrowsABubble() {
        // Log curve: 2 launches against a max of 50 should be well above the floor.
        val f = UsageScale.factor(2f, 50f)
        assertTrue("expected noticeable early growth, was $f", f > 1.0f)
        assertTrue(f < UsageScale.MAX)
    }
}
