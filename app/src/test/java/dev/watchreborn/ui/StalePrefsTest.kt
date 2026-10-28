package dev.watchreborn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StalePrefsTest {

    private val live = "com.live/.Main#0"
    private val gone = "com.gone/com.gone.Main#0"
    private val goneWork = "com.live/.Main#10"

    @Test fun appKeysParse() {
        assertTrue(StalePrefs.isAppKey(live))
        assertTrue(StalePrefs.isAppKey(gone))
        assertFalse(StalePrefs.isAppKey("widget:vitals"))
        assertFalse(StalePrefs.isAppKey("folder:SOCIAL"))
        assertFalse(StalePrefs.isAppKey("cfolder:f12ab"))
        assertFalse(StalePrefs.isAppKey("com.x/.A#"))
        assertFalse(StalePrefs.isAppKey("com.x/.A#ab"))
        assertEquals("com.gone", StalePrefs.packageOf(gone))
        assertEquals(10L, StalePrefs.serialOf(goneWork))
    }

    @Test fun prefKeysMapToAppKeys() {
        assertEquals(live, StalePrefs.appKeyOf("pin:$live"))
        assertEquals(live, StalePrefs.appKeyOf("pin@HONEYCOMB:$live"))
        assertEquals(live, StalePrefs.appKeyOf("n:$live"))
        assertEquals(live, StalePrefs.appKeyOf("s:$live"))
        assertEquals(live, StalePrefs.appKeyOf("t:$live"))
        assertEquals(live, StalePrefs.appKeyOf("group:$live"))
        assertNull(StalePrefs.appKeyOf("pin:widget:vitals"))
        assertNull(StalePrefs.appKeyOf("pin@GRAVITY:folder:SOCIAL"))
        assertNull(StalePrefs.appKeyOf("hidden_groups"))
        assertNull(StalePrefs.appKeyOf("cf:f1:members"))
    }

    @Test fun planRemovesOnlyGoneAppsAndFiltersFolderOrders() {
        val prefs = mapOf(
            "pin:$live" to 3, "pin:$gone" to 4, "pin@RIVER:$gone" to 1,
            "n:$gone" to 9, "s:$gone" to 2f, "t:$gone" to 5L, "group:$gone" to "GAMES",
            "n:$live" to 2, "pin:widget:vitals" to 0, "pin:$goneWork" to 7,
            "forder:SOCIAL" to "$live,$gone",
            "forder:GAMES" to gone,
            "forder:TOOLS" to live,
            "cf:f1:members" to "$live,$gone",
        )
        val asked = mutableListOf<String>()
        val plan = StalePrefs.plan(prefs) { key -> asked += key; key == gone || key == goneWork }
        assertEquals(
            setOf("pin:$gone", "pin@RIVER:$gone", "n:$gone", "s:$gone", "t:$gone", "group:$gone",
                "pin:$goneWork", "forder:GAMES"),
            plan.remove,
        )
        assertEquals(mapOf("forder:SOCIAL" to live), plan.rewrite)
        // One verdict per app key, however many prefs mention it.
        assertEquals(asked.size, asked.toSet().size)
    }

    @Test fun nothingGoneIsAnEmptyPlan() {
        val plan = StalePrefs.plan(mapOf("pin:$live" to 1, "forder:X" to live)) { false }
        assertTrue(plan.isEmpty)
    }
}
