package dev.watchreborn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MorphMathTest {

    @Test fun delayIsZeroAtTheCentreAndFullAtTheRim() {
        assertEquals(0f, MorphMath.delayFor(0f), 0.001f)
        assertEquals(MorphMath.STAGGER_MS, MorphMath.delayFor(1f), 0.001f)
        assertEquals(MorphMath.STAGGER_MS / 2f, MorphMath.delayFor(0.5f), 0.001f)
    }

    @Test fun delayClampsOutsideTheUnitRange() {
        assertEquals(0f, MorphMath.delayFor(-0.5f), 0.001f)
        assertEquals(MorphMath.STAGGER_MS, MorphMath.delayFor(1.5f), 0.001f)
    }

    @Test fun progressWaitsOutTheDelayThenRunsOneItemFlight() {
        val delay = 120f
        assertEquals(0f, MorphMath.progress(0f, delay), 0.001f)
        assertEquals(0f, MorphMath.progress(delay, delay), 0.001f)
        assertEquals(0.5f, MorphMath.progress(delay + MorphMath.ITEM_MS / 2f, delay), 0.001f)
        assertEquals(1f, MorphMath.progress(delay + MorphMath.ITEM_MS, delay), 0.001f)
        assertEquals(1f, MorphMath.progress(delay + MorphMath.ITEM_MS * 3f, delay), 0.001f)
    }

    @Test fun theRimItemLandsExactlyAtTheEnvelopeEnd() {
        val rimDelay = MorphMath.delayFor(1f)
        assertEquals(1f, MorphMath.progress(MorphMath.TOTAL_MS, rimDelay), 0.001f)
        assertTrue(MorphMath.progress(MorphMath.TOTAL_MS - 1f, rimDelay) < 1f)
    }

    @Test fun easeEndpointsAreExact() {
        assertEquals(0f, MorphMath.ease(0f), 0.0001f)
        assertEquals(1f, MorphMath.ease(1f), 0.0001f)
    }

    @Test fun easeOvershootsSlightlyThenSettles() {
        var peak = 0f
        var t = 0f
        while (t <= 1f) {
            val v = MorphMath.ease(t)
            if (v > peak) peak = v
            t += 0.01f
        }
        // A slight overshoot: clearly past the target, nowhere near wild.
        assertTrue("peak $peak", peak > 1.02f && peak < 1.10f)
        // Before the overshoot the curve is still approaching, and
        // it gets most of the way early (the fast launch).
        assertTrue(MorphMath.ease(0.25f) < 1f)
        assertTrue(MorphMath.ease(0.25f) > 0.5f)
    }

    @Test fun skipThresholdSparesHomeItems() {
        assertTrue(MorphMath.shouldSkip(0f))
        assertTrue(MorphMath.shouldSkip(MorphMath.SKIP_DIST / 2f))
        assertFalse(MorphMath.shouldSkip(MorphMath.SKIP_DIST * 2f))
        assertFalse(MorphMath.shouldSkip(3.5f))
    }

    @Test fun envelopeSitsInTheDesignedWindow() {
        // The design brief: the whole flight reads as one gesture.
        assertTrue(MorphMath.TOTAL_MS >= 550f && MorphMath.TOTAL_MS <= 700f)
        assertEquals(MorphMath.TOTAL_MS, MorphMath.STAGGER_MS + MorphMath.ITEM_MS, 0.001f)
    }
}
