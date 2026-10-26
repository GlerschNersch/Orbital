package dev.watchreborn.ui

import kotlin.math.PI
import kotlin.math.max

/**
 * Pure placement math for the ring/arc cluster layouts (Orbitals, Chrono Bezel,
 * Thumb Arc Dock).
 *
 * The original generators gave each topology a fixed set of rings and dumped
 * every remaining app onto the last one, so at ~180 installed apps the outer
 * ring collapsed into an unreadable overlapping "caterpillar". These helpers
 * generalise the rings: capacities grow ring by ring without bound, and the
 * Bezel's outer ring works as a spin-through carousel window over the catalog.
 */
object RingMath {

    /** Where one item sits among concentric rings. Item 0 is the center. */
    data class Loc(val ring: Int, val slot: Int, val ringSize: Int, val startIndex: Int)

    /**
     * Locates [index] (item 0 = center) in rings whose capacities start at
     * [firstCapacity] and grow by [capacityGrowth] per ring:
     * capacities are firstCapacity, firstCapacity+growth, firstCapacity+2*growth, …
     * Ring r starts at index 1 + sum of the capacities of rings 0..r-1.
     */
    fun locate(index: Int, firstCapacity: Int, capacityGrowth: Int): Loc {
        if (index <= 0) return Loc(-1, 0, 1, 0)
        var ring = 0
        var start = 1
        var cap = firstCapacity
        while (index >= start + cap) {
            start += cap
            ring++
            cap += capacityGrowth
        }
        return Loc(ring, index - start, cap, start)
    }

    /** How many rings are needed to hold [total] items (item 0 is the center). */
    fun ringCount(total: Int, firstCapacity: Int, capacityGrowth: Int): Int {
        if (total <= 1) return 0
        var rings = 0
        var start = 1
        var cap = firstCapacity
        while (start < total) {
            start += cap
            cap += capacityGrowth
            rings++
        }
        return rings
    }

    /** Total items held by the center plus the first [rings] rings. */
    fun capacityOf(rings: Int, firstCapacity: Int, capacityGrowth: Int): Int {
        var total = 1
        var cap = firstCapacity
        repeat(rings) {
            total += cap
            cap += capacityGrowth
        }
        return total
    }

    /**
     * Carousel window for the Chrono Bezel's outer ring: of [total] items in the
     * outer list, only [capacity] sit on the ring at once. [rotation] (radians,
     * already speed-scaled by the caller) scrolls the window through the list;
     * it is clamped so the window never runs past either end.
     *
     * Returns the item's fractional slot in [0, capacity) — its angle is
     * -PI/2 + slot * (2PI / capacity) — or null when the item is scrolled out
     * of the window and should not be drawn.
     */
    fun carouselSlot(itemPos: Int, total: Int, capacity: Int, rotation: Float): Float? {
        if (itemPos < 0 || itemPos >= total) return null
        if (total <= capacity) return itemPos.toFloat()
        val step = (2.0 * PI / capacity).toFloat()
        val maxScroll = (total - capacity).toFloat()
        val scroll = (rotation / step).coerceIn(0f, maxScroll)
        val slot = itemPos - scroll
        return if (slot >= 0f && slot < capacity.toFloat()) slot else null
    }

    /** The clamped scroll offset (in items) for a carousel — for HUD readouts. */
    fun carouselScroll(total: Int, capacity: Int, rotation: Float): Float {
        if (total <= capacity) return 0f
        val step = (2.0 * PI / capacity).toFloat()
        return (rotation / step).coerceIn(0f, max(0, total - capacity).toFloat())
    }
}
