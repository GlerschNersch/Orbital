package dev.watchreborn.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TintMathTest {

    // The default accent (ACCENTS "Green") and a saturated wallpaper blue.
    private val accent = (0xFF shl 24) or (166 shl 16) or (227 shl 8) or 110
    private val wall = (0xFF shl 24) or (51 shl 16) or (102 shl 8) or 204

    @Test fun zeroAmountKeepsTheAccent() {
        assertEquals(accent, TintMath.blend(accent, wall, 0f))
    }

    @Test fun fullAmountTakesTheWallpaperColour() {
        assertEquals(wall, TintMath.blend(accent, wall, 1f))
    }

    @Test fun defaultBlendLandsFortyPercentAcross() {
        val out = TintMath.blend(accent, wall)
        // R: 166 + (51 - 166) * 0.4 = 120
        // G: 227 + (102 - 227) * 0.4 = 177
        // B: 110 + (204 - 110) * 0.4 = 147.6 -> 148
        assertEquals(120, (out shr 16) and 0xFF)
        assertEquals(177, (out shr 8) and 0xFF)
        assertEquals(148, out and 0xFF)
    }

    @Test fun resultIsAlwaysOpaque() {
        val translucentAccent = (80 shl 24) or (166 shl 16) or (227 shl 8) or 110
        val out = TintMath.blend(translucentAccent, wall)
        assertEquals(0xFF, (out ushr 24) and 0xFF)
    }

    @Test fun amountClampsToTheUnitRange() {
        assertEquals(accent, TintMath.blend(accent, wall, -0.5f))
        assertEquals(wall, TintMath.blend(accent, wall, 1.5f))
    }
}
