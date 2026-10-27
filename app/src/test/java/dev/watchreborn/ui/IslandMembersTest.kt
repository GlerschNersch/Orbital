package dev.watchreborn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.6.0: island stack membership tokens (pure). */
class IslandMembersTest {

    @Test
    fun tokenShapes() {
        assertEquals("s:42", IslandMembers.sysToken(42))
        assertEquals("n:CLOCK", IslandMembers.nativeToken(NativeKind.CLOCK))
        assertTrue(IslandMembers.isNative("n:TIMER"))
        assertEquals(42, IslandMembers.sysId("s:42"))
        assertNull(IslandMembers.sysId("n:CLOCK"))
        assertEquals(NativeKind.CLOCK, IslandMembers.nativeKind("n:CLOCK"))
        assertNull(IslandMembers.nativeKind("s:42"))
        assertNull(IslandMembers.nativeKind("n:NOPE"))
    }

    @Test
    fun parseRoundTrips() {
        val members = listOf("s:8", "n:CLOCK", "s:11")
        assertEquals(members, IslandMembers.parse(IslandMembers.serialize(members)))
    }

    @Test
    fun parseDropsMalformedAndDuplicates() {
        assertEquals(
            listOf("s:8", "n:TIMER"),
            IslandMembers.parse(" s:8 ,,junk,n:TIMER,s:8,s:abc,n:NOPE,"),
        )
        assertEquals(emptyList<String>(), IslandMembers.parse(null))
        assertEquals(emptyList<String>(), IslandMembers.parse(""))
        assertEquals(emptyList<String>(), IslandMembers.parse("   "))
    }

    @Test
    fun topIndexClampsAndCycles() {
        val m = listOf("s:1", "n:CLOCK", "s:2")
        assertEquals(0, IslandMembers.clampTopIndex(m, -3))
        assertEquals(2, IslandMembers.clampTopIndex(m, 99))
        assertEquals(1, IslandMembers.nextTopIndex(m, 0))
        assertEquals(2, IslandMembers.nextTopIndex(m, 1))
        assertEquals(0, IslandMembers.nextTopIndex(m, 2)) // wraps
        assertEquals(0, IslandMembers.nextTopIndex(listOf("s:1"), 0))
        assertEquals(0, IslandMembers.nextTopIndex(emptyList(), 0))
        assertEquals(0, IslandMembers.clampTopIndex(emptyList(), 5))
    }

    @Test
    fun systemIdsExtractsOnlySystemMembers() {
        assertEquals(
            listOf(8, 11),
            IslandMembers.systemIds(listOf("s:8", "n:CLOCK", "s:11", "n:TIMER")),
        )
        assertEquals(emptyList<Int>(), IslandMembers.systemIds(listOf("n:CLOCK")))
        assertEquals(listOf(8, 11), IslandMembers.systemIdsOfRaw("s:8,n:VITALS,s:11"))
        assertEquals(emptyList<Int>(), IslandMembers.systemIdsOfRaw(null))
    }
}
