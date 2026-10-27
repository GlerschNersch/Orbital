package dev.watchreborn.ui

import kotlin.math.hypot

/**
 * Topology morph math (3.4.0).
 *
 * A topology switch used to teleport every bubble: the position
 * glide snaps any jump past ~4.5 pitches, and a layout switch moves
 * most bubbles much further than that. The morph flies each item
 * from its captured start to its new layout slot over one shared
 * envelope: items leave staggered by their distance from the centre
 * (the centre moves first, the rim trails) and travel on an
 * easeOutBack curve — a fast launch, a slight overshoot past the
 * slot, and a settle — so the whole field reads as one springy
 * gesture instead of a cut. Pure math so it can be unit-tested
 * off-device; WatchView owns the capture and the clock.
 */
object MorphMath {

    /** Total envelope: the last (outermost) item lands by here. */
    const val TOTAL_MS = 650f

    /** One item's flight time once its stagger delay has elapsed. */
    const val ITEM_MS = 430f

    /** Stagger spread across the field: centre items at 0, rim here. */
    const val STAGGER_MS = TOTAL_MS - ITEM_MS

    /** Start and target closer than this (pitch units) don't fly. */
    const val SKIP_DIST = 0.02f

    /**
     * Delay before an item at normalised centre-distance [distNorm]
     * (0 = centre, 1 = rim) starts moving.
     */
    fun delayFor(distNorm: Float): Float =
        STAGGER_MS * distNorm.coerceIn(0f, 1f)

    /**
     * An item's local flight progress at [elapsedMs] into the morph:
     * 0 until its [delayMs] passes, 1 after one full item flight,
     * clamped at both ends.
     */
    fun progress(elapsedMs: Float, delayMs: Float): Float =
        ((elapsedMs - delayMs) / ITEM_MS).coerceIn(0f, 1f)

    /**
     * easeOutBack with a gentle overshoot constant: fast off the
     * start, ~5% past the target at about two-thirds through, then
     * settled exactly on 1. Endpoints are exact (0 -> 0, 1 -> 1).
     */
    fun ease(t: Float): Float {
        val c = 1.15f
        val u = t.coerceIn(0f, 1f) - 1f
        return 1f + (c + 1f) * u * u * u + c * u * u
    }

    /** True when a start->target hop is too small to bother flying. */
    fun shouldSkip(dist: Float): Boolean = dist < SKIP_DIST

    /** Straight-line distance between two world points. */
    fun distance(x0: Float, y0: Float, x1: Float, y1: Float): Float =
        hypot(x1 - x0, y1 - y0)
}
