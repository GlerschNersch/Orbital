package dev.watchreborn.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class OverviewFitTest {

    private val eps = 0.001f

    @Test
    fun smallContentKeepsTheClassicOverview() {
        // Content half-extent 200px in a 500px half-viewport: raw fit
        // would be 2.5, capped at the legacy 0.48.
        assertEquals(0.48f, OverviewFit.fitZoom(200f, 200f, 500f, 500f), eps)
    }

    @Test
    fun wideContentIsBoundByWidth() {
        // 2000px half-width into 500px: 0.25.
        assertEquals(0.25f, OverviewFit.fitZoom(2000f, 400f, 500f, 900f), eps)
    }

    @Test
    fun tallContentIsBoundByHeight() {
        // Island column: 3000px half-height into 900px: 0.30, even
        // though width alone would allow 0.48.
        assertEquals(0.30f, OverviewFit.fitZoom(600f, 3000f, 500f, 900f), eps)
    }

    @Test
    fun extremeContentFloorsAtTheLegibilityLimit() {
        // Raw fit 0.05 clamps to MIN_ZOOM rather than shrinking
        // bubbles into specks.
        assertEquals(0.20f, OverviewFit.fitZoom(10000f, 10000f, 500f, 500f), eps)
    }

    @Test
    fun degenerateInputsFallBackToTheClassicOverview() {
        assertEquals(0.48f, OverviewFit.fitZoom(0f, 100f, 500f, 500f), eps)
        assertEquals(0.48f, OverviewFit.fitZoom(100f, 100f, 0f, 500f), eps)
        assertEquals(0.48f, OverviewFit.fitZoom(-5f, -5f, 500f, 500f), eps)
    }
}
