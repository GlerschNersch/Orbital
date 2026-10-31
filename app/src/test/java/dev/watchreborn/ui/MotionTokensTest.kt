package dev.watchreborn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTokensTest {

    @Test fun launchPulsePeaksMidwayForTappedRing() {
        val peak = MotionTokens.launchPulse(MotionTokens.LAUNCH_BLOOM_PULSE_MS / 2f, 0f)
        assertEquals(1f, peak, 0.001f)
    }

    @Test fun launchPulseStaggersByRing() {
        // Ring 2 has not started while ring 0 is already pulsing.
        assertEquals(0f, MotionTokens.launchPulse(10f, 2f), 0.001f)
        assertTrue(MotionTokens.launchPulse(10f, 0f) > 0f)
        // Ring 1 peaks exactly one stagger after ring 0 would.
        val p1 = MotionTokens.launchPulse(
            MotionTokens.LAUNCH_BLOOM_STAGGER_MS + MotionTokens.LAUNCH_BLOOM_PULSE_MS / 2f, 1f,
        )
        assertEquals(1f, p1, 0.001f)
    }

    @Test fun launchPulseRespectsRangeAndEnds() {
        assertEquals(
            0f,
            MotionTokens.launchPulse(100f, MotionTokens.LAUNCH_BLOOM_RANGE_RINGS + 0.5f),
            0.001f,
        )
        assertEquals(
            0f,
            MotionTokens.launchPulse(MotionTokens.LAUNCH_BLOOM_TOTAL_MS + 50f, 0f),
            0.001f,
        )
        // The whole envelope stays inside the ~350ms launch budget.
        assertTrue(MotionTokens.LAUNCH_BLOOM_TOTAL_MS <= 350f)
    }

    @Test fun launchRippleGrowsAndFades() {
        assertEquals(0f, MotionTokens.launchRippleRings(0f), 0.001f)
        assertEquals(
            MotionTokens.LAUNCH_BLOOM_RANGE_RINGS,
            MotionTokens.launchRippleRings(MotionTokens.LAUNCH_BLOOM_TOTAL_MS),
            0.001f,
        )
        assertEquals(1f, MotionTokens.launchRippleAlpha(0f), 0.001f)
        assertEquals(
            0f,
            MotionTokens.launchRippleAlpha(MotionTokens.LAUNCH_BLOOM_TOTAL_MS),
            0.001f,
        )
    }

    @Test fun centralisedValuesKeepTheirTunedNumbers() {
        // The 3.8.3 token pass centralises; it does not retune.
        assertEquals(300f, MotionTokens.THEME_FADE_MS, 0f)
        assertEquals(650f, MotionTokens.INTENT_BLOOM_TOTAL_MS, 0f)
        assertEquals(28f, MotionTokens.INTENT_BLOOM_STAGGER_MS, 0f)
        assertEquals(220f, MotionTokens.INTENT_BLOOM_STAGGER_CAP_MS, 0f)
        assertEquals(430f, MotionTokens.INTENT_BLOOM_FLY_MS, 0f)
        assertEquals(200f, MotionTokens.INTENT_BLOOM_EXIT_MS, 0f)
        assertEquals(150f, MotionTokens.HOMECOMING_DELAY_PER_UNIT_MS, 0f)
        assertEquals(240f, MotionTokens.HOMECOMING_DURATION_MS, 0f)
        assertEquals(300f, MotionTokens.GHOST_FADE_MS, 0f)
        assertEquals(18f, MotionTokens.LAUNCH_BLOOM_STAGGER_MS, 0f)
        assertEquals(170f, MotionTokens.LAUNCH_BLOOM_PULSE_MS, 0f)
    }
}
