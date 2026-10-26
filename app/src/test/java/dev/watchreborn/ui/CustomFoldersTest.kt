package dev.watchreborn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomFoldersTest {

    private fun def(id: String, vararg keys: String) =
        CustomFolders.Def(id, id, keys.toList())

    @Test
    fun codecRoundTrips() {
        val keys = listOf("com.a/.Main#0", "com.b/.Home#0", "com.c/.X#10")
        assertEquals(keys, CustomFolders.decode(CustomFolders.encode(keys)))
        assertEquals(emptyList<String>(), CustomFolders.decode(null))
        assertEquals(emptyList<String>(), CustomFolders.decode(""))
        assertEquals(emptyList<String>(), CustomFolders.decode(" , ,"))
    }

    @Test
    fun dissolveRule() {
        assertTrue(CustomFolders.dissolves(0))
        assertTrue(CustomFolders.dissolves(1))
        assertFalse(CustomFolders.dissolves(2))
        assertFalse(CustomFolders.dissolves(9))
    }

    @Test
    fun extractRemovesFromOneFolderOnly() {
        val defs = listOf(def("a", "k1", "k2", "k3"), def("b", "k4", "k5"))
        val out = CustomFolders.extract(defs, "k2")
        assertEquals(listOf("k1", "k3"), out.first { it.id == "a" }.memberKeys)
        assertEquals(listOf("k4", "k5"), out.first { it.id == "b" }.memberKeys)
    }

    @Test
    fun extractDissolvesFolderLeftWithOneMember() {
        val defs = listOf(def("a", "k1", "k2"), def("b", "k3", "k4"))
        val out = CustomFolders.extract(defs, "k1")
        assertEquals(listOf("b"), out.map { it.id })
    }

    @Test
    fun extractUnknownKeyIsNoOp() {
        val defs = listOf(def("a", "k1", "k2"))
        assertEquals(defs, CustomFolders.extract(defs, "nope"))
    }

    @Test
    fun resolveDropsUninstalledAndDissolved() {
        val defs = listOf(
            def("a", "k1", "gone", "k2"),
            def("b", "gone1", "gone2"),
            def("c", "k3", "gone"),
        )
        val out = CustomFolders.resolve(defs, setOf("k1", "k2", "k3"))
        // a keeps its two installed members in stored order; b is empty and
        // c falls to one member — both hide.
        assertEquals(listOf("a"), out.map { it.id })
        assertEquals(listOf("k1", "k2"), out[0].memberKeys)
    }
}
