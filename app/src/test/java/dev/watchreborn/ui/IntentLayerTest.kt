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

    // 3.8.1 sub-caps: the prototype's resolveCapsAt14 assumed the old
    // total cap of 14; it is now resolveCapsAt10PrimaryGroup (same shape,
    // new total), joined by the secondary-group cases below.

    @Test fun resolveCapsAt10PrimaryGroup() {
        val candidates = (1..20).map { candidate("health$it", AppGroup.HEALTH) }
        val resolved = IntentLayer.resolve(IntentLayer.WORKOUT, candidates)
        assertEquals(10, resolved.size)
        assertEquals("health1", resolved.first().key)
        assertEquals("health10", resolved.last().key)
    }

    @Test fun resolveCapsSecondaryGroupAt3() {
        // Frecency puts six Music apps first; WORKOUT may only take 3 of
        // them, behind the uncapped primary Fitness group.
        val candidates = (1..6).map { candidate("music$it", AppGroup.MUSIC) } +
            listOf(candidate("health1", AppGroup.HEALTH), candidate("health2", AppGroup.HEALTH))
        val resolved = IntentLayer.resolve(IntentLayer.WORKOUT, candidates)
        assertEquals(
            listOf("health1", "health2", "music1", "music2", "music3"),
            resolved.map { it.key },
        )
    }

    @Test fun resolvePrimaryGroupCanExceed3() {
        val candidates = (1..6).map { candidate("health$it", AppGroup.HEALTH) }
        val resolved = IntentLayer.resolve(IntentLayer.WORKOUT, candidates)
        assertEquals(6, resolved.size)
        assertEquals("health6", resolved.last().key)
    }

    @Test fun resolveTotalCapWithMixedGroups() {
        // 6 primary + 3 + 3 secondaries would be 12; the total cap of 10
        // trims the tail of the last group.
        val candidates = (1..6).map { candidate("health$it", AppGroup.HEALTH) } +
            (1..6).map { candidate("music$it", AppGroup.MUSIC) } +
            (1..6).map { candidate("video$it", AppGroup.VIDEO) }
        val resolved = IntentLayer.resolve(IntentLayer.WORKOUT, candidates)
        assertEquals(10, resolved.size)
        assertEquals(
            listOf(
                "health1", "health2", "health3", "health4", "health5", "health6",
                "music1", "music2", "music3", "video1",
            ),
            resolved.map { it.key },
        )
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
