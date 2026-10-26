package dev.watchreborn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance tests for Phase 2 (Strata Sovereign-path trial): the launch
 * operation Frecency's class doc describes — age the current score to
 * the launch moment, then add the launch — as one tested operation.
 * Written before the implementation; see Frecency.recordLaunch.
 */
class FrecencyRecordLaunchTest {

    private val day = 86_400_000L
    private val t0 = 1_700_000_000_000L

    @Test fun firstEverLaunchScoresOne() {
        assertEquals(1f, Frecency.recordLaunch(null, 0, 0L, t0), 0.001f)
    }

    @Test fun storedScoreIncrementsWhenNoTimePassed() {
        assertEquals(9f, Frecency.recordLaunch(8f, 0, t0, t0), 0.001f)
    }

    @Test fun storedScoreAgesOneHalfLifeThenIncrements() {
        val tenDays = (Frecency.HALF_LIFE_DAYS * day).toLong()
        assertEquals(5f, Frecency.recordLaunch(8f, 0, t0, t0 + tenDays), 0.01f)
    }

    @Test fun storedScoreAgesTwoHalfLivesThenIncrements() {
        val twentyDays = (2 * Frecency.HALF_LIFE_DAYS * day).toLong()
        assertEquals(3f, Frecency.recordLaunch(8f, 0, t0, t0 + twentyDays), 0.01f)
    }

    @Test fun legacyOnlyLaunchSeedsCappedThenIncrements() {
        assertEquals(Frecency.SEED_CAP + 1f, Frecency.recordLaunch(null, 10_000, t0, t0), 0.001f)
    }

    @Test fun legacyOnlyLaunchSeedsAgedThenIncrements() {
        val tenDays = (Frecency.HALF_LIFE_DAYS * day).toLong()
        assertEquals(6f, Frecency.recordLaunch(null, 10, t0, t0 + tenDays), 0.01f)
    }

    @Test fun storedZeroStillTakesStoredPathNotLegacySeed() {
        // An app with an s: score of 0 but a huge legacy count must NOT be
        // re-seeded: the stored score is authoritative once it exists.
        assertEquals(1f, Frecency.recordLaunch(0f, 500, t0, t0), 0.001f)
    }

    @Test fun unknownLastLaunchDoesNotAgeBeforeIncrement() {
        assertEquals(6f, Frecency.recordLaunch(5f, 0, 0L, t0), 0.001f)
    }

    @Test fun dailyLaunchesThroughRecordLaunchSettleNearEquilibrium() {
        var stored: Float? = null
        var last = t0
        repeat(60) {
            val now = last + day
            stored = Frecency.recordLaunch(stored, 0, last, now)
            last = now
        }
        assertTrue("daily equilibrium was $stored", stored!! in 13f..16f)
    }
}
