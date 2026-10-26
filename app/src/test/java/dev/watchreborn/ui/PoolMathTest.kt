package dev.watchreborn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

class PoolMathTest {

    private val pitch = 326f // GRID_PITCH × unit on Matt's phone (u = 1190)

    @Test
    fun tankRadiusScalesWithPitch() {
        assertEquals(831.3f, PoolMath.tankRadius(pitch), 0.5f)
        assertEquals(2.55f, PoolMath.tankRadius(1f), 0.001f)
    }

    @Test
    fun smallCatalogKeepsNaturalBubbleSize() {
        // 10 bubbles fit easily at the natural radius — no shrinking.
        assertEquals(pitch * 0.45f, PoolMath.bubbleRadius(10, pitch), 0.01f)
        assertEquals(pitch * 0.45f, PoolMath.bubbleRadius(1, pitch), 0.01f)
        assertEquals(pitch * 0.45f, PoolMath.bubbleRadius(0, pitch), 0.01f)
    }

    @Test
    fun fullCatalogFitsInsideTank() {
        // The whole point: at a real catalog size, the bubbles' total area
        // must pack inside the tank instead of overflowing ~9×.
        val n = 187
        val r = PoolMath.bubbleRadius(n, pitch)
        val tankR = PoolMath.tankRadius(pitch)
        assertTrue("radius shrinks for a big catalog", r < pitch * 0.45f)
        val bubbleArea = PI * r * r * n
        val tankArea = PI * tankR * tankR
        assertTrue("packed area $bubbleArea within tank $tankArea", bubbleArea <= tankArea)
        // And the implied equilibrium disc of centres fits the usable radius.
        val discR = r * sqrt(n / 0.82f)
        assertTrue("centre disc $discR within usable ${tankR - r}", discR <= (tankR - r) * 1.12f)
    }

    @Test
    fun seedScalePullsSeedsInsideTank() {
        // Hex seeds for 187 items reach ~2300px — far outside the 831px tank.
        val s = PoolMath.seedScale(2300f, 187, pitch)
        assertTrue(s < 1f)
        assertTrue(2300f * s <= PoolMath.tankRadius(pitch))
    }

    @Test
    fun seedScaleLeavesFittingSeedsAlone() {
        assertEquals(1f, PoolMath.seedScale(100f, 10, pitch), 0.001f)
        assertEquals(1f, PoolMath.seedScale(0f, 187, pitch), 0.001f)
    }
}
