package dev.watchreborn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrecencyTest {

    private val day = 86_400_000L
    private val t0 = 1_700_000_000_000L

    @Test fun noTimePassedIsIdentity() {
        assertEquals(8f, Frecency.decay(8f, t0, t0), 0.001f)
    }

    @Test fun oneHalfLifeHalves() {
        val tenDays = (Frecency.HALF_LIFE_DAYS * day).toLong()
        assertEquals(4f, Frecency.decay(8f, t0, t0 + tenDays), 0.01f)
    }

    @Test fun twoHalfLivesQuarter() {
        val twentyDays = (2 * Frecency.HALF_LIFE_DAYS * day).toLong()
        assertEquals(2f, Frecency.decay(8f, t0, t0 + twentyDays), 0.01f)
    }

    @Test fun zeroStaysZero() {
        assertEquals(0f, Frecency.decay(0f, t0, t0 + 90 * day), 0.001f)
    }

    @Test fun unknownLastLaunchDoesNotAge() {
        assertEquals(5f, Frecency.decay(5f, 0L, t0), 0.001f)
    }

    @Test fun seedCapsHugeLegacyCounts() {
        assertEquals(Frecency.SEED_CAP, Frecency.seedFromLegacy(10_000, t0, t0), 0.001f)
    }

    @Test fun seedAgesWithStaleness() {
        val tenDays = (Frecency.HALF_LIFE_DAYS * day).toLong()
        assertEquals(5f, Frecency.seedFromLegacy(10, t0, t0 + tenDays), 0.01f)
        assertEquals(0f, Frecency.seedFromLegacy(0, t0, t0), 0.001f)
    }

    @Test fun dailyUseSettlesNearEquilibrium() {
        // Simulate two months of one launch a day: age a day, add 1.
        var s = 0f
        var last = t0
        repeat(60) {
            val now = last + day
            s = Frecency.decay(s, last, now) + 1f
            last = now
        }
        assertTrue("daily equilibrium was $s", s in 13f..16f)
    }

    @Test fun abandonedAppFadesBelowWeeklyApp() {
        // A daily app abandoned for a month vs one opened 3x this week.
        var daily = 0f
        var last = t0
        repeat(30) { val now = last + day; daily = Frecency.decay(daily, last, now) + 1f; last = now }
        val abandoned = Frecency.decay(daily, last, last + 30 * day)
        var weekly = 0f
        var wLast = last + 27 * day
        repeat(3) { val now = wLast + day; weekly = Frecency.decay(weekly, wLast, now) + 1f; wLast = now }
        assertTrue("abandoned $abandoned should trail weekly $weekly", abandoned < weekly)
    }
}
