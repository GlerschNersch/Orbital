package dev.watchreborn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 3.6.0: built-in native widget kinds (pure metadata). */
class NativeIslandsTest {

    @Test
    fun fromNameRoundTripsEveryKind() {
        for (kind in NativeKind.entries) {
            assertEquals(kind, NativeKind.fromName(kind.name))
        }
        assertNull(NativeKind.fromName("CONTROLS")) // cut in 3.6.0
        assertNull(NativeKind.fromName(null))
        assertNull(NativeKind.fromName(""))
        assertNull(NativeKind.fromName("clock")) // names are exact
    }

    @Test
    fun pickerListsEveryKindOnce() {
        assertEquals(NativeKind.entries.toSet(), NativeIslands.pickerKinds.toSet())
        assertEquals(NativeKind.entries.size, NativeIslands.pickerKinds.size)
    }

    @Test
    fun defaultTilesFitTheIslandClamps() {
        for (kind in NativeKind.entries) {
            assert(kind.defaultWDp in 180..420) { kind.name }
            assert(kind.defaultHDp in 110..300) { kind.name }
        }
    }
}
