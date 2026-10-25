package dev.watchreborn.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Drop-resolution and nearest-slot rules for drag-to-place. */
class PlaceSlotsTest {

    @Test
    fun releaseWithoutDragOpensMenu() {
        assertEquals(PlaceSlots.DropAction.MENU, PlaceSlots.resolveDrop(moved = false, fromSlot = 3, hoverSlot = 3))
        assertEquals(PlaceSlots.DropAction.MENU, PlaceSlots.resolveDrop(moved = false, fromSlot = 3, hoverSlot = 9))
    }

    @Test
    fun dragToAnotherSlotPins() {
        assertEquals(PlaceSlots.DropAction.PIN, PlaceSlots.resolveDrop(moved = true, fromSlot = 3, hoverSlot = 9))
        assertEquals(PlaceSlots.DropAction.PIN, PlaceSlots.resolveDrop(moved = true, fromSlot = 0, hoverSlot = 1))
    }

    @Test
    fun dragBackHomeOrOffGridChangesNothing() {
        assertEquals(PlaceSlots.DropAction.NO_CHANGE, PlaceSlots.resolveDrop(moved = true, fromSlot = 3, hoverSlot = 3))
        assertEquals(PlaceSlots.DropAction.NO_CHANGE, PlaceSlots.resolveDrop(moved = true, fromSlot = 3, hoverSlot = -1))
    }

    @Test
    fun nearestSlotPicksClosestCenter() {
        val centers = listOf(
            Triple(0f, 0f, 0),
            Triple(100f, 0f, 1),
            Triple(0f, 100f, 2),
        )
        assertEquals(1, PlaceSlots.nearestSlot(88f, 6f, centers, maxDist = 60f))
        assertEquals(2, PlaceSlots.nearestSlot(4f, 91f, centers, maxDist = 60f))
        assertEquals(0, PlaceSlots.nearestSlot(0f, 0f, centers, maxDist = 60f))
    }

    @Test
    fun nearestSlotRejectsFarAwayAndEmpty() {
        val centers = listOf(Triple(0f, 0f, 0))
        assertEquals(-1, PlaceSlots.nearestSlot(500f, 500f, centers, maxDist = 60f))
        assertEquals(-1, PlaceSlots.nearestSlot(0f, 0f, emptyList(), maxDist = 60f))
    }
}
