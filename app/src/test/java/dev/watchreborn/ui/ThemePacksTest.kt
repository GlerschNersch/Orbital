package dev.watchreborn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePacksTest {

    @Test fun registryHasFivePacksWithUniqueIds() {
        assertEquals(5, ThemePacks.ALL.size)
        assertEquals(5, ThemePacks.ALL.map { it.id }.toSet().size)
        assertEquals(ThemePacks.ORBITAL, ThemePacks.ALL.first())
    }

    @Test fun byIdFallsBackToDefault() {
        assertEquals(ThemePacks.XMB_BLUE, ThemePacks.byId("xmb_blue"))
        assertEquals(ThemePacks.ORBITAL, ThemePacks.byId(null))
        assertEquals(ThemePacks.ORBITAL, ThemePacks.byId(""))
        assertEquals(ThemePacks.ORBITAL, ThemePacks.byId("no_such_pack"))
        assertEquals(ThemePacks.DEFAULT_ID, ThemePacks.ORBITAL.id)
    }

    /** The Orbital pack must reproduce the shipped 3.8.1 chrome
     *  exactly: the picked accent green, the sheet panel and page
     *  colours, and the hairline grey. */
    @Test fun orbitalPackMatchesShippedDefaults() {
        val p = ThemePacks.ORBITAL
        assertEquals(0xFFA6E36E.toInt(), p.accentDay)
        assertEquals(0xFFA6E36E.toInt(), p.accentNight)
        assertEquals(0xFF232529.toInt(), p.surface) // (35, 37, 43)
        assertEquals(0xFF181A1E.toInt(), p.surfaceAlt) // (24, 26, 30)
        assertEquals(0xFF60626A.toInt(), p.latticeStroke) // (96, 98, 106)
        assertEquals(DisplayFont.SYSTEM, p.displayFont)
    }

    @Test fun xmbPacksCarryMonthPaletteValues() {
        // August row: day bl corner 3D9DD3, night tl 008B9E,
        // day br corner 06B1DE, night centre 003361.
        assertEquals(0xFF3D9DD3.toInt(), ThemePacks.XMB_BLUE.accentDay)
        assertEquals(0xFF008B9E.toInt(), ThemePacks.XMB_BLUE.accentNight)
        assertEquals(0xFF06B1DE.toInt(), ThemePacks.XMB_BLUE.glowDay)
        assertEquals(0xFF003361.toInt(), ThemePacks.XMB_BLUE.glowNight)
        // June row: day centre 9B65D2, night centre 594966.
        assertEquals(0xFF9B65D2.toInt(), ThemePacks.XMB_PURPLE.accentDay)
        assertEquals(0xFF594966.toInt(), ThemePacks.XMB_PURPLE.glowNight)
        // January row: day centre BABABA, night centre 525252.
        assertEquals(0xFFBABABA.toInt(), ThemePacks.SILVER_MONO.accentDay)
        assertEquals(0xFF525252.toInt(), ThemePacks.SILVER_MONO.glowNight)
    }

    @Test fun nightnessSchedule() {
        assertEquals(0f, ThemePacks.nightness(12f), 0.001f) // noon: full day
        assertEquals(1f, ThemePacks.nightness(23f), 0.001f) // late evening: full night
        assertEquals(1f, ThemePacks.nightness(0f), 0.001f) // midnight: full night
        assertEquals(0f, ThemePacks.nightness(6f), 0.001f) // after the dawn fade
        // Mid-fade values sit strictly inside (0, 1) in both windows.
        val dusk = ThemePacks.nightness(19.4f)
        assertTrue("dusk $dusk", dusk > 0.2f && dusk < 0.8f)
        val dawn = ThemePacks.nightness(2.5f)
        assertTrue("dawn $dawn", dawn > 0.2f && dawn < 0.8f)
        // The evening fade is monotonic.
        assertTrue(ThemePacks.nightness(19.9f) > ThemePacks.nightness(18.6f))
    }

    @Test fun nightnessWrapsAndClamps() {
        assertEquals(ThemePacks.nightness(0f), ThemePacks.nightness(24f), 0.001f)
        assertEquals(ThemePacks.nightness(1f), ThemePacks.nightness(25f), 0.001f)
        assertEquals(ThemePacks.nightness(23f), ThemePacks.nightness(-1f), 0.001f)
    }

    @Test fun blendEndpointsAreExact() {
        val day = 0xFF102030.toInt()
        val night = 0xFFE8D8C8.toInt()
        assertEquals(day, ThemePacks.blend(day, night, 0f))
        assertEquals(night, ThemePacks.blend(day, night, 1f))
        // Out-of-range factors clamp to the endpoints.
        assertEquals(day, ThemePacks.blend(day, night, -0.5f))
        assertEquals(night, ThemePacks.blend(day, night, 1.5f))
    }

    @Test fun blendMidpointIsATrueLerp() {
        val mid = ThemePacks.blend(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f)
        assertEquals(0xFF808080.toInt(), mid)
    }

    @Test fun accentAtBlendsByHour() {
        assertEquals(ThemePacks.XMB_BLUE.accentDay, ThemePacks.accentAt(ThemePacks.XMB_BLUE, 12f))
        assertEquals(ThemePacks.XMB_BLUE.accentNight, ThemePacks.accentAt(ThemePacks.XMB_BLUE, 23f))
        // Static packs never move.
        assertEquals(ThemePacks.NIGHT_CITY.accentDay, ThemePacks.accentAt(ThemePacks.NIGHT_CITY, 3f))
        assertEquals(ThemePacks.ORBITAL.accentDay, ThemePacks.accentAt(ThemePacks.ORBITAL, 21f))
    }

    @Test fun onlyNightCityUsesRajdhani() {
        assertEquals(DisplayFont.RAJDHANI, ThemePacks.NIGHT_CITY.displayFont)
        for (pack in ThemePacks.ALL) {
            if (pack != ThemePacks.NIGHT_CITY) assertEquals(DisplayFont.SYSTEM, pack.displayFont)
        }
    }
}
