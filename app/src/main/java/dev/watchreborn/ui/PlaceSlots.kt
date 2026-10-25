package dev.watchreborn.ui

import kotlin.math.hypot

/**
 * Pure geometry and decision logic for drag-to-place: long-press a grid bubble,
 * drag it over the slot that should hold it, release to pin it there.
 * Kept side-effect free so the rules are unit-testable without a Canvas.
 */
object PlaceSlots {

    enum class DropAction {
        /** Released without dragging: fall back to the classic long-press menu. */
        MENU,

        /** Pin the item into the hovered slot. */
        PIN,

        /** Dropped on its own slot or off-grid: leave placement untouched. */
        NO_CHANGE,
    }

    fun resolveDrop(moved: Boolean, fromSlot: Int, hoverSlot: Int): DropAction = when {
        !moved -> DropAction.MENU
        hoverSlot < 0 || hoverSlot == fromSlot -> DropAction.NO_CHANGE
        else -> DropAction.PIN
    }

    /**
     * The slot whose center is nearest to (x, y) among [centers] (x, y, slot),
     * or -1 when the nearest center is farther than [maxDist].
     */
    fun nearestSlot(x: Float, y: Float, centers: List<Triple<Float, Float, Int>>, maxDist: Float): Int {
        var best = -1
        var bestDist = Float.MAX_VALUE
        for ((cx, cy, slot) in centers) {
            val d = hypot(x - cx, y - cy)
            if (d < bestDist) {
                bestDist = d
                best = slot
            }
        }
        return if (bestDist <= maxDist) best else -1
    }
}
