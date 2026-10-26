package dev.watchreborn.ui

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure sizing math for the Zero-G Billiards pool (`LIQUID_POOL`).
 *
 * The original pool used one fixed bubble radius (pitch × 0.45) inside a
 * fixed tank (pitch × 2.55). That tank physically fits ~20 bubbles at that
 * size; with a real catalog (~187 items) the simulation was ~9×
 * over capacity — collisions constantly shoved bubbles through the wall
 * (the clamp ran before the collision passes, never after), the seeds
 * started on hex positions far outside the tank, and no wall was ever
 * drawn, so the layout read as a full-screen overlapping jumble.
 *
 * The fix: fit the bubble radius to the catalog so everything packs
 * inside the tank, seed inside the tank, clamp after collisions, and
 * draw the tank rim (the drawing lives in WatchView).
 */
object PoolMath {

    /** Tank radius in world px for a grid [pitch]. */
    fun tankRadius(pitch: Float): Float = pitch * 2.55f

    /**
     * Bubble radius for [count] bubbles: the natural radius (pitch × 0.45)
     * for small catalogs, shrunk by area packing (πr²·N ≈ 0.80·π·tankR²)
     * when the catalog would overflow the tank.
     */
    fun bubbleRadius(count: Int, pitch: Float): Float {
        if (count <= 0) return pitch * 0.45f
        val fit = tankRadius(pitch) * sqrt(0.80f / count.toFloat())
        return min(pitch * 0.45f, fit)
    }

    /**
     * Scale factor that pulls seed positions inside the tank: hex seeds
     * reach ~3× the tank radius at real catalog sizes, so the outermost
     * seed is mapped to 95% of the usable centre radius and everything
     * else scales proportionally. Returns 1f when the seeds already fit.
     */
    fun seedScale(maxSeedRadius: Float, count: Int, pitch: Float): Float {
        if (maxSeedRadius <= 0f) return 1f
        val limit = (tankRadius(pitch) - bubbleRadius(count, pitch)) * 0.95f
        return if (maxSeedRadius > limit) limit / maxSeedRadius else 1f
    }
}
