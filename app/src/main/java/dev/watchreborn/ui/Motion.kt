package dev.watchreborn.ui

import kotlin.math.exp
import kotlin.math.pow

/**
 * Small pure motion helpers for the bubble animations: frame-rate independent
 * exponential damping (for position/scale springs) and an ease-out for the
 * drop glide. Kept separate so the feel is unit-testable without a View.
 */
object Motion {

    /** Fraction of the remaining distance to cover this frame: 1 - exp(-dt/tau). */
    fun dampFactor(dtSec: Float, tauSec: Float): Float {
        if (dtSec <= 0f || tauSec <= 0f) return 0f
        return (1.0 - exp(-dtSec / tauSec)).toFloat().coerceIn(0f, 1f)
    }

    /** Fast start, gentle arrival — for the 240ms drop glide. */
    fun easeOutCubic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return 1f - (1f - x).pow(3)
    }

    /** Overshoots past 1 before settling — the pop when quick-menu chips bloom out. */
    fun easeOutBack(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val u = x - 1f
        return 1f + c3 * u * u * u + c1 * u * u
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
