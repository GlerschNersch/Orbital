package dev.watchreborn.ui

import kotlin.math.roundToInt

/**
 * Wallpaper tint math (3.2.5).
 *
 * The cluster's accent used to be exactly the colour picked from the
 * ACCENTS palette. With Wallpaper Tint on, MainActivity leans that
 * pick toward the system wallpaper's primary colour (WallpaperManager
 * colours) so the launcher feels cut from the same cloth as the home
 * screen behind it — without surrendering the pick: the blend keeps
 * most of the accent's identity and only borrows the wallpaper's hue.
 * Pure channel math so it can be unit-tested off-device.
 */
object TintMath {

    /** How far the accent leans toward the wallpaper colour. */
    const val BLEND_AMOUNT = 0.40f

    /**
     * Per-channel linear blend of [accent] toward [wallpaper] by
     * [amount] (0 = the accent untouched, 1 = the wallpaper colour).
     * The result is always fully opaque: the accent drives rims and
     * text, and a translucent wallpaper colour would wash them out.
     */
    fun blend(accent: Int, wallpaper: Int, amount: Float = BLEND_AMOUNT): Int {
        val t = amount.coerceIn(0f, 1f)
        fun ch(a: Int, b: Int) = (a + (b - a) * t).roundToInt().coerceIn(0, 255)
        val r = ch((accent shr 16) and 0xFF, (wallpaper shr 16) and 0xFF)
        val g = ch((accent shr 8) and 0xFF, (wallpaper shr 8) and 0xFF)
        val b = ch(accent and 0xFF, wallpaper and 0xFF)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
