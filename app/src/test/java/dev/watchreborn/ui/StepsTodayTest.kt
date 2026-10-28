package dev.watchreborn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepsTodayTest {

    private val d = 20_000L

    @Test fun firstReadingStartsAtZero() {
        val s = StepsToday.fold(null, 48_000L, d)
        assertEquals(0L, StepsToday.stepsOn(s, d))
    }

    @Test fun sameDayCountsFromBaseline() {
        var s = StepsToday.fold(null, 48_000L, d)
        s = StepsToday.fold(s, 48_250L, d)
        s = StepsToday.fold(s, 49_000L, d)
        assertEquals(1_000L, StepsToday.stepsOn(s, d))
    }

    @Test fun newDayRebasesOnLastReading() {
        var s = StepsToday.fold(null, 10_000L, d)
        s = StepsToday.fold(s, 12_000L, d)
        s = StepsToday.fold(s, 12_300L, d + 1)
        assertEquals(300L, StepsToday.stepsOn(s, d + 1))
    }

    @Test fun rebootSameDayCarriesTodaysSteps() {
        var s = StepsToday.fold(null, 10_000L, d)
        s = StepsToday.fold(s, 10_800L, d)
        s = StepsToday.fold(s, 50L, d) // counter restarted
        assertEquals(850L, StepsToday.stepsOn(s, d))
        s = StepsToday.fold(s, 150L, d)
        assertEquals(950L, StepsToday.stepsOn(s, d))
    }

    @Test fun rebootOnANewDayDropsYesterday() {
        var s = StepsToday.fold(null, 10_000L, d)
        s = StepsToday.fold(s, 10_800L, d)
        s = StepsToday.fold(s, 40L, d + 1)
        assertEquals(40L, StepsToday.stepsOn(s, d + 1))
    }

    @Test fun staleStateReadsZeroAfterMidnight() {
        var s = StepsToday.fold(null, 10_000L, d)
        s = StepsToday.fold(s, 12_000L, d)
        assertEquals(0L, StepsToday.stepsOn(s, d + 1))
        assertEquals(0L, StepsToday.stepsOn(null, d))
    }

    @Test fun savesBaselineChangesButThrottlesLast() {
        val s = StepsToday.fold(null, 1_000L, d)
        assertTrue(StepsToday.worthSaving(null, s))
        assertFalse(StepsToday.worthSaving(s, StepsToday.fold(s, 1_099L, d)))
        assertTrue(StepsToday.worthSaving(s, StepsToday.fold(s, 1_100L, d)))
        assertTrue(StepsToday.worthSaving(s, StepsToday.fold(s, 1_010L, d + 1)))
        assertTrue(StepsToday.worthSaving(s, StepsToday.fold(s, 5L, d)))
    }
}
