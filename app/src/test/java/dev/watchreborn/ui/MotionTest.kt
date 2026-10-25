package dev.watchreborn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {

    @Test fun dampFactorIsZeroWithoutTime() {
        assertEquals(0f, Motion.dampFactor(0f, 0.1f), 0.0001f)
    }

    @Test fun dampFactorApproachesOneOverTime() {
        val f = Motion.dampFactor(1f, 0.1f)
        assertTrue("long frame should nearly complete the spring, was $f", f > 0.99f)
        val half = Motion.dampFactor(0.0693f, 0.1f)
        assertEquals(0.5f, half, 0.02f)
    }

    @Test fun dampFactorIsFrameRateIndependent() {
        // Two half steps land (nearly) where one full step lands.
        val one = Motion.dampFactor(0.032f, 0.1f)
        val step = Motion.dampFactor(0.016f, 0.1f)
        val two = step + (1f - step) * step
        assertEquals(one, two, 0.001f)
    }

    @Test fun easeOutCubicEndpointsAndShape() {
        assertEquals(0f, Motion.easeOutCubic(0f), 0.0001f)
        assertEquals(1f, Motion.easeOutCubic(1f), 0.0001f)
        assertTrue(Motion.easeOutCubic(0.5f) > 0.5f) // fast start
        assertEquals(1f, Motion.easeOutCubic(2f), 0.0001f) // clamps
    }

    @Test fun easeOutBackOvershootsThenSettles() {
        assertEquals(0f, Motion.easeOutBack(0f), 0.0001f)
        assertEquals(1f, Motion.easeOutBack(1f), 0.0001f)
        val mid = Motion.easeOutBack(0.7f)
        assertTrue("expected an overshoot past 1 mid-flight, was $mid", mid > 1f)
        assertEquals(1f, Motion.easeOutBack(3f), 0.0001f) // clamps
    }

    @Test fun lerpEndpoints() {
        assertEquals(10f, Motion.lerp(10f, 20f, 0f), 0.0001f)
        assertEquals(20f, Motion.lerp(10f, 20f, 1f), 0.0001f)
        assertEquals(15f, Motion.lerp(10f, 20f, 0.5f), 0.0001f)
    }
}
