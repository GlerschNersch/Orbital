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

    /** 3.10.0 dialects: STOCK is the tuned values verbatim, so a
     *  STOCK pack can never regress the shipped motion. */
    @Test fun stockDialectEqualsTheTunedConstants() {
        val p = MotionTokens.params(MotionDialect.STOCK)
        assertEquals(MotionTokens.THEME_FADE_MS, p.themeFadeMs, 0f)
        assertEquals(MotionTokens.INTENT_BLOOM_TOTAL_MS, p.intentBloomTotalMs, 0f)
        assertEquals(MotionTokens.INTENT_BLOOM_STAGGER_MS, p.intentBloomStaggerMs, 0f)
        assertEquals(MotionTokens.INTENT_BLOOM_STAGGER_CAP_MS, p.intentBloomStaggerCapMs, 0f)
        assertEquals(MotionTokens.INTENT_BLOOM_FLY_MS, p.intentBloomFlyMs, 0f)
        assertEquals(MotionTokens.INTENT_BLOOM_EXIT_MS, p.intentBloomExitMs, 0f)
        assertEquals(MotionTokens.HOMECOMING_DELAY_PER_UNIT_MS, p.homecomingDelayPerUnitMs, 0f)
        assertEquals(MotionTokens.HOMECOMING_DURATION_MS, p.homecomingDurationMs, 0f)
        assertEquals(MotionTokens.LAUNCH_BLOOM_STAGGER_MS, p.launchBloomStaggerMs, 0f)
        assertEquals(MotionTokens.LAUNCH_BLOOM_PULSE_MS, p.launchBloomPulseMs, 0f)
        assertEquals(MotionTokens.LAUNCH_BLOOM_TOTAL_MS, p.launchBloomTotalMs, 0f)
        assertEquals(1f, p.springScale, 0f)
        // The legacy two-arg launch functions are the STOCK dialect.
        assertEquals(
            MotionTokens.launchPulse(100f, 1f),
            MotionTokens.launchPulse(100f, 1f, p),
            0f,
        )
        assertEquals(
            MotionTokens.launchRippleRings(100f),
            MotionTokens.launchRippleRings(100f, p),
            0f,
        )
    }

    @Test fun crispDialectIsShorterAndSnappier() {
        val crisp = MotionTokens.params(MotionDialect.CRISP)
        val stock = MotionTokens.params(MotionDialect.STOCK)
        // The specified anchor: the theme fade drops to 220ms.
        assertEquals(220f, crisp.themeFadeMs, 0f)
        assertTrue(crisp.intentBloomTotalMs < stock.intentBloomTotalMs)
        assertTrue(crisp.intentBloomFlyMs < stock.intentBloomFlyMs)
        assertTrue(crisp.intentBloomStaggerMs < stock.intentBloomStaggerMs)
        assertTrue(crisp.homecomingDurationMs < stock.homecomingDurationMs)
        assertTrue(crisp.launchBloomTotalMs < stock.launchBloomTotalMs)
        assertTrue(crisp.springScale > stock.springScale)
        // Its launch pulse peaks earlier in wall-clock time.
        val crispPeak = crisp.launchBloomStaggerMs + crisp.launchBloomPulseMs / 2f
        val stockPeak = stock.launchBloomStaggerMs + stock.launchBloomPulseMs / 2f
        assertTrue(crispPeak < stockPeak)
        assertEquals(1f, MotionTokens.launchPulse(crispPeak, 1f, crisp), 0.001f)
    }

    @Test fun driftDialectIsLongerAndSofter() {
        val drift = MotionTokens.params(MotionDialect.DRIFT)
        val stock = MotionTokens.params(MotionDialect.STOCK)
        assertEquals(405f, drift.themeFadeMs, 0f) // 300 x 1.35
        assertTrue(drift.intentBloomTotalMs > stock.intentBloomTotalMs)
        assertTrue(drift.intentBloomFlyMs > stock.intentBloomFlyMs)
        assertTrue(drift.intentBloomStaggerMs > stock.intentBloomStaggerMs)
        assertTrue(drift.homecomingDelayPerUnitMs > stock.homecomingDelayPerUnitMs)
        assertTrue(drift.launchBloomTotalMs > stock.launchBloomTotalMs)
        assertTrue(drift.springScale < stock.springScale)
    }

    /** At STOCK the dialect bloom ease is MorphMath.ease exactly —
     *  the Intent Bloom's flight curve cannot drift. */
    @Test fun stockBloomEaseMatchesMorphEase() {
        val stock = MotionTokens.params(MotionDialect.STOCK)
        for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertEquals(MorphMath.ease(t), MotionTokens.bloomEase(t, stock), 0.0001f)
        }
        // CRISP overshoots harder mid-flight; DRIFT softer.
        val mid = 0.66f
        assertTrue(
            MotionTokens.bloomEase(mid, MotionTokens.params(MotionDialect.CRISP)) >
                MotionTokens.bloomEase(mid, stock),
        )
        assertTrue(
            MotionTokens.bloomEase(mid, MotionTokens.params(MotionDialect.DRIFT)) <
                MotionTokens.bloomEase(mid, stock),
        )
        // Endpoints stay exact in every dialect.
        for (d in MotionDialect.entries) {
            val p = MotionTokens.params(d)
            assertEquals(0f, MotionTokens.bloomEase(0f, p), 0.0001f)
            assertEquals(1f, MotionTokens.bloomEase(1f, p), 0.0001f)
        }
    }

    @Test fun switchWaveStaggersFromTheCentre() {
        // The centre item starts at once and peaks mid-pulse.
        assertTrue(MotionTokens.switchWavePulse(10f, 0f) > 0f)
        assertEquals(1f, MotionTokens.switchWavePulse(MotionTokens.SWITCH_WAVE_PULSE_MS / 2f, 0f), 0.001f)
        // An item 4 pitches out has not started while the centre pulses.
        assertEquals(0f, MotionTokens.switchWavePulse(10f, 4f), 0.001f)
        val p4 = MotionTokens.switchWavePulse(
            4f * MotionTokens.SWITCH_WAVE_STAGGER_MS + MotionTokens.SWITCH_WAVE_PULSE_MS / 2f, 4f,
        )
        assertEquals(1f, p4, 0.001f)
    }

    @Test fun switchWaveEndsInsideItsTotal() {
        // A realistic field radius (12 pitches) is done well inside.
        assertEquals(0f, MotionTokens.switchWavePulse(MotionTokens.SWITCH_WAVE_TOTAL_MS, 12f), 0.001f)
        assertTrue(MotionTokens.SWITCH_WAVE_TOTAL_MS <= 700f)
        // Nothing pulses beyond the max distance or before time zero.
        assertEquals(0f, MotionTokens.switchWavePulse(100f, MotionTokens.SWITCH_WAVE_MAX_DIST + 1f), 0.001f)
        assertEquals(0f, MotionTokens.switchWavePulse(-5f, 0f), 0.001f)
        // The max-distance pulse still completes by the total bound.
        val far = MotionTokens.SWITCH_WAVE_MAX_DIST
        assertEquals(
            0f,
            MotionTokens.switchWavePulse(
                far * MotionTokens.SWITCH_WAVE_STAGGER_MS + MotionTokens.SWITCH_WAVE_PULSE_MS + 1f, far,
            ),
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
