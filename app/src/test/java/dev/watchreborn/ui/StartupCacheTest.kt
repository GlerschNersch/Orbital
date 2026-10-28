package dev.watchreborn.ui

import dev.watchreborn.apps.StartupCacheLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.7.2: the startup cache's fingerprint, entry encoding and
 * reconcile decision. The cache is only ever a paint-fast hint —
 * these pin the rules that keep it honest: any change to the
 * package set (install, remove, update, icon size, locale) must
 * fingerprint differently, and a fingerprint match is the only
 * thing that may skip the fresh load.
 */
class StartupCacheTest {
    private fun stamp(
        serial: Long = 0L,
        component: String = "com.example.app/.MainActivity",
        version: Long = 1L,
        updated: Long = 1000L,
    ) = StartupCacheLogic.PkgStamp(serial, component, version, updated)

    private fun entry(
        key: String = "com.example.app/.MainActivity#0",
        label: String = "Example",
    ) = StartupCacheLogic.Entry(
        key = key,
        label = label,
        component = "com.example.app/.MainActivity",
        userSerial = 0L,
        system = false,
        group = "OTHER",
        blurredScale = 0.02f,
    )

    @Test
    fun fingerprintIsStableAndOrderIndependent() {
        val a = listOf(stamp(), stamp(component = "org.other/.Home", version = 3L))
        val fp1 = StartupCacheLogic.fingerprint(a, 168, "en-US")
        val fp2 = StartupCacheLogic.fingerprint(a.reversed(), 168, "en-US")
        assertEquals(fp1, fp2)
        assertEquals(fp1, StartupCacheLogic.fingerprint(a, 168, "en-US"))
    }

    @Test
    fun fingerprintChangesWhenThePackageSetChanges() {
        val base = listOf(stamp())
        val fp = StartupCacheLogic.fingerprint(base, 168, "en-US")
        // An install adds a stamp.
        assertNotEquals(
            fp,
            StartupCacheLogic.fingerprint(base + stamp(component = "com.new/.A"), 168, "en-US"),
        )
        // An update bumps version or lastUpdateTime.
        assertNotEquals(fp, StartupCacheLogic.fingerprint(listOf(stamp(version = 2L)), 168, "en-US"))
        assertNotEquals(fp, StartupCacheLogic.fingerprint(listOf(stamp(updated = 2000L)), 168, "en-US"))
        // A different activity in the same package.
        assertNotEquals(
            fp,
            StartupCacheLogic.fingerprint(listOf(stamp(component = "com.example.app/.Other")), 168, "en-US"),
        )
        // A second user profile.
        assertNotEquals(fp, StartupCacheLogic.fingerprint(listOf(stamp(serial = 10L)), 168, "en-US"))
    }

    @Test
    fun fingerprintChangesWithIconSizeAndLocale() {
        val base = listOf(stamp())
        val fp = StartupCacheLogic.fingerprint(base, 168, "en-US")
        assertNotEquals(fp, StartupCacheLogic.fingerprint(base, 192, "en-US"))
        assertNotEquals(fp, StartupCacheLogic.fingerprint(base, 168, "es-ES"))
    }

    @Test
    fun entryRoundTripsThroughEncoding() {
        val tricky = entry(label = "A;B:C\nD — ünïcode ✓")
        assertEquals(tricky, StartupCacheLogic.decodeEntry(StartupCacheLogic.encodeEntry(tricky)))
        val empty = entry(label = "")
        assertEquals(empty, StartupCacheLogic.decodeEntry(StartupCacheLogic.encodeEntry(empty)))
        val sys = entry().copy(system = true, blurredScale = 0.1234f, userSerial = 999L)
        assertEquals(sys, StartupCacheLogic.decodeEntry(StartupCacheLogic.encodeEntry(sys)))
    }

    @Test
    fun entryListRoundTrips() {
        val list = listOf(entry(), entry(key = "org.other/.Home#10", label = "Other"))
        assertEquals(list, StartupCacheLogic.decodeEntries(StartupCacheLogic.encodeEntries(list)))
        assertEquals(emptyList<StartupCacheLogic.Entry>(), StartupCacheLogic.decodeEntries(""))
    }

    @Test
    fun malformedEncodingsDecodeToNull() {
        assertNull(StartupCacheLogic.decodeEntry(""))
        assertNull(StartupCacheLogic.decodeEntry("garbage"))
        assertNull(StartupCacheLogic.decodeEntry("3:ab")) // declared length overruns
        assertNull(StartupCacheLogic.decodeEntry(StartupCacheLogic.encodeEntry(entry()) + "x"))
        val good = StartupCacheLogic.encodeEntry(entry())
        assertNull(StartupCacheLogic.decodeEntries(good + "\n" + "junk"))
        assertNull(StartupCacheLogic.decodeEntries("junk"))
    }

    @Test
    fun reconcileSkipsOnlyOnFingerprintMatchWithAField() {
        assertTrue(StartupCacheLogic.canSkipFreshLoad("fp", "fp", hasField = true))
        assertFalse(StartupCacheLogic.canSkipFreshLoad("fp", "fp", hasField = false))
        assertFalse(StartupCacheLogic.canSkipFreshLoad("fp", "other", hasField = true))
        assertFalse(StartupCacheLogic.canSkipFreshLoad(null, "fp", hasField = true))
        assertFalse(StartupCacheLogic.canSkipFreshLoad("fp", null, hasField = true))
        assertFalse(StartupCacheLogic.canSkipFreshLoad(null, null, hasField = true))
    }
}
