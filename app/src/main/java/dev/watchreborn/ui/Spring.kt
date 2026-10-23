package dev.watchreborn.ui

import kotlin.math.abs
import kotlin.math.exp

/**
 * A critically damped spring toward [target], stepped by elapsed time. The face-to-apps zoom in the
 * footage settles in about 0.3 s with no overshoot, which is what critical damping gives.
 */
class Spring(var value: Float = 0f, private val settleSeconds: Float = Metrics.ZOOM_SECONDS) {
    var target = value
    var velocity = 0f
        private set

    /** Natural frequency for which the spring is within 1% of its target after [settleSeconds]. */
    private val omega get() = 6.64f / settleSeconds

    val settled get() = abs(target - value) < 1e-3f && abs(velocity) < 1e-2f

    fun snap(v: Float) { value = v; target = v; velocity = 0f }

    fun step(dt: Float) {
        if (settled) { value = target; velocity = 0f; return }
        // Exact critically damped solution over dt, so big frame gaps can't make it unstable.
        val w = omega
        val x0 = value - target
        val v0 = velocity
        val e = exp(-w * dt)
        val x = (x0 + (v0 + w * x0) * dt) * e
        velocity = (v0 - (v0 + w * x0) * w * dt) * e
        value = target + x
    }
}
