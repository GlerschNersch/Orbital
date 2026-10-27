package dev.watchreborn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LayoutBackupTest {

    private fun sampleStores(): Map<String, Map<String, Any?>> = mapOf(
        LayoutBackup.STORE_WATCH to mapOf(
            "cluster_topology" to "FORCE_FIELD",
            "auto_folders" to false,
            "wallpaper_tint" to true,
            "accent" to 4,
            "some_long_counter" to 1234567890123L,
            "island_ids" to "8,10,11",
            "island_8_ax" to 0.0f,
            "island_8_ay" to 2.74f,
            "island_10_ay" to -1.51f,
            "island_11_scale" to 1.3f,
            "hints_seen" to setOf("pinch", "islands"),
            "tricky_text" to "quotes \"and\" \\ slashes\nnewline\t tab 🎉",
        ),
        LayoutBackup.STORE_APPS to mapOf(
            "pin:com.example.app/.Main" to 3,
            "hidden_groups" to setOf("games"),
            "folder_label" to "Utilities",
        ),
    )

    @Test fun roundTripPreservesEveryTypeAndValue() {
        val json = LayoutBackup.serialize(sampleStores(), "3.3.0", 65, 1730000000000L)
        val back = LayoutBackup.parse(json)
        assertEquals("3.3.0", back.header.versionName)
        assertEquals(65, back.header.versionCode)
        assertEquals(1730000000000L, back.header.exportedAtMillis)
        val watch = back.stores.getValue(LayoutBackup.STORE_WATCH)
        assertEquals("FORCE_FIELD", watch["cluster_topology"])
        assertEquals(false, watch["auto_folders"])
        assertEquals(true, watch["wallpaper_tint"])
        assertEquals(4, watch["accent"])
        assertTrue(watch["accent"] is Int)
        assertEquals(1234567890123L, watch["some_long_counter"])
        assertTrue(watch["some_long_counter"] is Long)
        assertEquals(0.0f, watch["island_8_ax"])
        assertEquals(2.74f, watch["island_8_ay"])
        assertEquals(-1.51f, watch["island_10_ay"])
        assertEquals(1.3f, watch["island_11_scale"])
        assertTrue(watch["island_8_ay"] is Float)
        assertEquals(setOf("pinch", "islands"), watch["hints_seen"])
        assertEquals("quotes \"and\" \\ slashes\nnewline\t tab 🎉", watch["tricky_text"])
        val apps = back.stores.getValue(LayoutBackup.STORE_APPS)
        assertEquals(3, apps["pin:com.example.app/.Main"])
        assertEquals(setOf("games"), apps["hidden_groups"])
        assertEquals("Utilities", apps["folder_label"])
        assertEquals(15, back.entryCount)
    }

    @Test fun serializationIsDeterministic() {
        val a = LayoutBackup.serialize(sampleStores(), "3.3.0", 65, 42L)
        val b = LayoutBackup.serialize(sampleStores(), "3.3.0", 65, 42L)
        assertEquals(a, b)
        assertTrue(a.contains("\"format\": \"orbital-layout\""))
        assertTrue(a.contains("\"schema\": 1"))
    }

    @Test fun emptyStoresRoundTrip() {
        val json = LayoutBackup.serialize(
            mapOf(LayoutBackup.STORE_WATCH to emptyMap(), LayoutBackup.STORE_APPS to emptyMap()),
            "3.3.0", 65, 1L,
        )
        val back = LayoutBackup.parse(json)
        assertEquals(0, back.entryCount)
        assertTrue(back.stores.getValue(LayoutBackup.STORE_WATCH).isEmpty())
    }

    @Test fun nullValuesAreSkipped() {
        val json = LayoutBackup.serialize(
            mapOf(LayoutBackup.STORE_WATCH to mapOf("gone" to null, "kept" to "yes")),
            "3.3.0", 65, 1L,
        )
        val back = LayoutBackup.parse(json)
        assertEquals(mapOf("kept" to "yes"), back.stores[LayoutBackup.STORE_WATCH])
    }

    private fun expectReject(json: String): LayoutBackup.BackupException {
        try {
            LayoutBackup.parse(json)
        } catch (e: LayoutBackup.BackupException) {
            return e
        }
        fail("expected BackupException for: ${json.take(60)}")
        throw AssertionError("unreachable")
    }

    @Test fun rejectsPlainText() {
        expectReject("this is not json at all")
    }

    @Test fun rejectsForeignJson() {
        val e = expectReject("""{"format": "nova-launcher", "schema": 1, "stores": {}}""")
        assertTrue(e.message!!.contains("Not an Orbital layout backup"))
    }

    @Test fun rejectsWrongSchema() {
        val e = expectReject(
            """{"format": "orbital-layout", "schema": 2, "stores": {"watch": {}}}"""
        )
        assertTrue(e.message!!.contains("schema 2"))
    }

    @Test fun rejectsMissingStores() {
        expectReject("""{"format": "orbital-layout", "schema": 1}""")
    }

    @Test fun rejectsTruncatedJson() {
        val json = LayoutBackup.serialize(sampleStores(), "3.3.0", 65, 1L)
        expectReject(json.substring(0, json.length / 2))
    }

    @Test fun rejectsUnknownEntryType() {
        expectReject(
            """{"format": "orbital-layout", "schema": 1,
               "stores": {"watch": {"k": {"t": "blob", "v": 1}}}}"""
        )
    }

    @Test fun rejectsMistypedEntryValue() {
        expectReject(
            """{"format": "orbital-layout", "schema": 1,
               "stores": {"watch": {"k": {"t": "int", "v": "seven"}}}}"""
        )
    }

    @Test fun unknownStoresAreIgnoredNotApplied() {
        val back = LayoutBackup.parse(
            """{"format": "orbital-layout", "schema": 1,
               "stores": {"mystery": {"k": {"t": "int", "v": 1}},
                          "watch": {"a": {"t": "boolean", "v": true}}}}"""
        )
        assertEquals(setOf(LayoutBackup.STORE_WATCH), back.stores.keys)
        assertEquals(true, back.stores.getValue(LayoutBackup.STORE_WATCH)["a"])
    }
}
