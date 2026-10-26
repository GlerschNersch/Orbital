package dev.watchreborn.ui

import dev.watchreborn.apps.AppClassifier
import dev.watchreborn.apps.AppGroup
import dev.watchreborn.apps.AppRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class GeometryTest {
    @Test fun spiralFillsRingsInOrder() {
        val s = Hex.spiral(19)
        assertEquals(Hex(0, 0), s[0])
        assertEquals(19, s.toSet().size)
        assertTrue(s.subList(1, 7).all { it.distanceTo(Hex(0, 0)) == 1 })
        assertTrue(s.subList(7, 19).all { it.distanceTo(Hex(0, 0)) == 2 })
    }

    @Test fun pointyLatticeMatchesTheMeasuredGrid() {
        // Same-row neighbour 0.274 W along; the next row ±0.131 across and 0.235 down.
        val p = FloatArray(2)
        pointyCenter(Hex(1, 0), Metrics.GRID_PITCH, p)
        assertEquals(0.274f, p[0], 1e-3f); assertEquals(0f, p[1], 1e-3f)
        pointyCenter(Hex(0, 1), Metrics.GRID_PITCH, p)
        assertEquals(0.137f, p[0], 0.01f); assertEquals(0.235f, p[1], 0.005f)
    }

    @Test fun flatClusterMatchesTheMeasuredRing() {
        // Neighbours straight above/below and at (±0.286, ±0.160).
        val p = FloatArray(2)
        flatCenter(Hex(0, -1), Metrics.FOCUS_RING, p)
        assertEquals(0f, p[0], 1e-3f); assertEquals(-0.328f, p[1], 0.012f)
        flatCenter(Hex(1, -1), Metrics.FOCUS_RING, p)
        assertEquals(0.286f, p[0], 0.006f); assertEquals(-0.160f, p[1], 0.006f)
        assertEquals(Metrics.FOCUS_RING, hypot(p[0], p[1]), 1e-3f)
    }

    @Test fun fisheyeShrinksAndPullsIn() {
        assertEquals(1f, Fisheye.size(0f), 1e-6f)
        assertEquals(0.87f, Fisheye.size(0.27f), 1e-3f)
        assertEquals(0f, Fisheye.size(2f), 1e-6f)
        // Second rows: lattice 0.47 W, drawn at 0.43 W.
        assertEquals(0.43f, Fisheye.pulled(0.47f), 0.005f)
        assertEquals(0.27f, Fisheye.pulled(0.27f), 1e-4f)
    }

    @Test fun springSettlesInThreeTenthsWithoutOvershoot() {
        val s = Spring(0f)
        s.target = 1f
        var t = 0f
        var max = 0f
        while (t < 0.3f) { s.step(1 / 60f); t += 1 / 60f; max = maxOf(max, s.value) }
        assertTrue("within 1% at 0.3 s: ${s.value}", s.value > 0.99f)
        assertTrue("no overshoot: $max", max <= 1.0001f)
    }

    @Test fun classifierSortsAppsIntoFolders() {
        assertEquals(AppGroup.SMART_HOME, AppClassifier.classify("Home Assistant", "io.homeassistant.companion.android", -1, false))
        assertEquals(AppGroup.FINANCE, AppClassifier.classify("Chase", "com.chase.sig.android", 7, false))
        assertEquals(AppGroup.GAMES, AppClassifier.classify("Minecraft", "com.mojang.minecraftpe", 0, true))
        assertEquals(AppGroup.SOCIAL, AppClassifier.classify("Phone", "com.google.android.dialer", -1, false, setOf(AppRole.DIALER)))
        assertEquals(AppGroup.TOOLS, AppClassifier.classify("Settings", "com.android.settings", -1, false))
    }
}
