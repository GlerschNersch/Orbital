package dev.watchreborn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FacesTest {

    private fun stores(
        topology: String,
        islandIds: String,
        scale: Float,
        growth: Boolean,
    ): Map<String, Map<String, Any?>> = mapOf(
        LayoutBackup.STORE_WATCH to mapOf(
            "cluster_topology" to topology,
            "island_ids" to islandIds,
            "island_11_scale" to scale,
            "usage_growth" to growth,
        ),
        LayoutBackup.STORE_APPS to mapOf(
            "pin:com.example.app/.Main" to 3,
        ),
    )

    private fun snapshot(stores: Map<String, Map<String, Any?>>): String =
        LayoutBackup.serialize(stores, "3.5.0", 67, 1730000000000L)

    @Test fun migrationCreatesMainHoldingTheLiveLayout() {
        val live = stores("FORCE_FIELD", "8,10,11", 1.0f, true)
        val face = Faces.migrationFace("f1", snapshot(live))
        assertEquals("f1", face.id)
        assertEquals(Faces.MAIN_NAME, face.name)
        assertEquals("Main", face.name)
        // The migration snapshot is the live layout, byte for byte.
        val parsed = LayoutBackup.parse(face.data)
        assertEquals("FORCE_FIELD", parsed.stores.getValue(LayoutBackup.STORE_WATCH)["cluster_topology"])
        assertEquals("8,10,11", parsed.stores.getValue(LayoutBackup.STORE_WATCH)["island_ids"])
    }

    @Test fun switchRoundTripRestoresEachFaceExactly() {
        // A simulated faces store + live stores, driven through the
        // same capture/apply steps MainActivity performs: switching
        // away snapshots the live state into the outgoing face's
        // slot, then applies the incoming face's snapshot.
        val slots = HashMap<String, String>()
        var live: Map<String, Map<String, Any?>> = stores("HONEYCOMB", "8,11", 1.0f, true)
        slots["main"] = snapshot(live)
        slots["test"] = slots.getValue("main") // New Face duplicates the live state
        var active = "main"

        fun switchTo(id: String) {
            slots[active] = snapshot(live) // outgoing face remembers itself
            live = LayoutBackup.parse(slots.getValue(id)).stores
            active = id
        }

        // Mutate on Main, switch to Test: Test still holds the original.
        live = stores("RIVER", "8,11", 1.3f, false)
        switchTo("test")
        var watch = live.getValue(LayoutBackup.STORE_WATCH)
        assertEquals("HONEYCOMB", watch["cluster_topology"])
        assertEquals(1.0f, watch["island_11_scale"])
        assertEquals(true, watch["usage_growth"])

        // Mutate differently on Test, switch back: Main's mutation is intact.
        live = stores("GRAVITY", "11", 1.6f, true)
        switchTo("main")
        watch = live.getValue(LayoutBackup.STORE_WATCH)
        assertEquals("RIVER", watch["cluster_topology"])
        assertEquals(1.3f, watch["island_11_scale"])
        assertEquals(false, watch["usage_growth"])
        assertEquals("8,11", watch["island_ids"])

        // And Test kept its own mutation too.
        switchTo("test")
        watch = live.getValue(LayoutBackup.STORE_WATCH)
        assertEquals("GRAVITY", watch["cluster_topology"])
        assertEquals(1.6f, watch["island_11_scale"])
        assertEquals("11", watch["island_ids"])
    }

    @Test fun topologyAndIslandIdsReadFromSnapshots() {
        val data = snapshot(stores("NEBULA", "8, 10,11", 1.0f, true))
        assertEquals("NEBULA", Faces.topologyNameOf(data))
        assertEquals(setOf(8, 10, 11), Faces.islandIdsOf(data))
        // Corrupt data introspects as absent, never throws.
        assertEquals(null, Faces.topologyNameOf("not json"))
        assertEquals(emptySet<Int>(), Faces.islandIdsOf("not json"))
        assertEquals(emptySet<Int>(), Faces.islandIdsOf(snapshot(stores("RIVER", "", 1.0f, true))))
    }

    @Test fun deleteSharedWidgetIdsAreRetained() {
        // 10 and 11 are shared with a surviving face: only 8 is orphaned.
        val orphans = Faces.orphanedWidgetIds(
            removed = setOf(8, 10, 11),
            surviving = listOf(setOf(10, 11)),
            live = emptySet(),
        )
        assertEquals(setOf(8), orphans)
    }

    @Test fun deleteUnsharedNonLiveWidgetIdsAreOrphaned() {
        val orphans = Faces.orphanedWidgetIds(
            removed = setOf(8, 10),
            surviving = listOf(setOf(99)),
            live = emptySet(),
        )
        assertEquals(setOf(8, 10), orphans)
    }

    @Test fun deleteLiveWidgetIdsAreRetainedEvenWhenUnshared() {
        // The live layout is showing 10 right now: deleting a face
        // that also references it must not deallocate it.
        val orphans = Faces.orphanedWidgetIds(
            removed = setOf(8, 10),
            surviving = emptyList(),
            live = setOf(10),
        )
        assertEquals(setOf(8), orphans)
    }

    @Test fun capIsEnforcedAtSix() {
        assertTrue(Faces.canAdd(0))
        assertTrue(Faces.canAdd(5))
        assertFalse(Faces.canAdd(6))
        assertFalse(Faces.canAdd(7))
        assertEquals(6, Faces.MAX_FACES)
    }

    @Test fun nextFaceNameSkipsUsedNames() {
        assertEquals("Face 2", Faces.nextFaceName(listOf("Main")))
        assertEquals("Face 3", Faces.nextFaceName(listOf("Main", "Face 2", "Weekend")))
        assertEquals("Face 2", Faces.nextFaceName(listOf("Main", "Face 3")))
    }
}
