package dev.watchreborn.ui

import dev.watchreborn.apps.AppGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentLayerTest {

    private fun candidate(key: String, group: AppGroup) =
        IntentLayer.IntentCandidate(key, group)

    // matchExact

    @Test fun workoutKeywordMatchesWorkout() {
        assertEquals(IntentLayer.WORKOUT, IntentLayer.matchExact("workout"))
    }

    @Test fun paddedWorkMatchesWork() {
        assertEquals(IntentLayer.WORK, IntentLayer.matchExact(" Work "))
    }

    @Test fun gymChipLabelMatchesWorkout() {
        assertEquals(IntentLayer.WORKOUT, IntentLayer.matchExact("gym"))
    }

    @Test fun appNameDoesNotMatch() {
        assertNull(IntentLayer.matchExact("chrome"))
    }

    @Test fun emptyDoesNotMatch() {
        assertNull(IntentLayer.matchExact(""))
    }

    @Test fun partialDoesNotMatch() {
        assertNull(IntentLayer.matchExact("worko"))
    }

    // resolve

    @Test fun resolveOrdersByGroupPriorityThenFrecency() {
        // Frecency order: music first, then two health, then video.
        val candidates = listOf(
            candidate("music1", AppGroup.MUSIC),
            candidate("health1", AppGroup.HEALTH),
            candidate("video1", AppGroup.VIDEO),
            candidate("health2", AppGroup.HEALTH),
            candidate("music2", AppGroup.MUSIC),
        )
        val resolved = IntentLayer.resolve(IntentLayer.WORKOUT, candidates)
        assertEquals(
            listOf("health1", "health2", "music1", "music2", "video1"),
            resolved.map { it.key },
        )
    }

    @Test fun resolveExcludesGroupsNotInIntent() {
        val candidates = listOf(
            candidate("game1", AppGroup.GAMES),
            candidate("health1", AppGroup.HEALTH),
            candidate("finance1", AppGroup.FINANCE),
        )
        val resolved = IntentLayer.resolve(IntentLayer.WORKOUT, candidates)
        assertEquals(listOf("health1"), resolved.map { it.key })
    }

    @Test fun resolveDedupesByKey() {
        val candidates = listOf(
            candidate("health1", AppGroup.HEALTH),
            candidate("health1", AppGroup.HEALTH),
            candidate("music1", AppGroup.MUSIC),
        )
        val resolved = IntentLayer.resolve(IntentLayer.WORKOUT, candidates)
        assertEquals(listOf("health1", "music1"), resolved.map { it.key })
    }

    @Test fun resolveCapsAt14() {
        val candidates = (1..20).map { candidate("health$it", AppGroup.HEALTH) }
        val resolved = IntentLayer.resolve(IntentLayer.WORKOUT, candidates)
        assertEquals(14, resolved.size)
        assertEquals("health1", resolved.first().key)
        assertEquals("health14", resolved.last().key)
    }

    @Test fun resolveEmptyWhenNoCandidatesMatch() {
        val candidates = listOf(
            candidate("game1", AppGroup.GAMES),
            candidate("finance1", AppGroup.FINANCE),
        )
        assertTrue(IntentLayer.resolve(IntentLayer.WORKOUT, candidates).isEmpty())
        assertTrue(IntentLayer.resolve(IntentLayer.WORKOUT, emptyList()).isEmpty())
    }
}
