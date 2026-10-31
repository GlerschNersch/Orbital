package dev.watchreborn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePacksTest {

    @Test fun registryHasSixteenPacksWithUniqueIds() {
        // Precisely: Orbital + the 12 XMB months + XMB Auto +
        // Night City + Silver Mono.
        assertEquals(16, ThemePacks.ALL.size)
        assertEquals(16, ThemePacks.ALL.map { it.id }.toSet().size)
        assertEquals(ThemePacks.ORBITAL, ThemePacks.ALL.first())
        assertEquals(ThemePacks.XMB_MONTHS, ThemePacks.ALL.subList(1, 13))
        assertEquals(ThemePacks.XMB_AUTO, ThemePacks.ALL[13])
        assertEquals(ThemePacks.NIGHT_CITY, ThemePacks.ALL[14])
        assertEquals(ThemePacks.SILVER_MONO, ThemePacks.ALL[15])
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

    /** The ten month packs 3.9.0 added, spot-checked against
     *  MonthPalette rows with the shipped derivation: day accent =
     *  centre or brightest corner, night accent = brightest night
     *  corner, night glow = night centre. */
    @Test fun newMonthPacksCarryMonthPaletteValues() {
        // February: day centre CDA30E, night tl B7B072, night centre 555032.
        assertEquals(0xFFCDA30E.toInt(), ThemePacks.XMB_GOLD.accentDay)
        assertEquals(0xFFB7B072.toInt(), ThemePacks.XMB_GOLD.accentNight)
        assertEquals(0xFF555032.toInt(), ThemePacks.XMB_GOLD.glowNight)
        // March: day tl 81BE29, day tr glow A6B924.
        assertEquals(0xFF81BE29.toInt(), ThemePacks.XMB_LIME.accentDay)
        assertEquals(0xFFA6B924.toInt(), ThemePacks.XMB_LIME.glowDay)
        // April: day bl E7497A, night centre 6C575B.
        assertEquals(0xFFE7497A.toInt(), ThemePacks.XMB_PINK.accentDay)
        assertEquals(0xFF6C575B.toInt(), ThemePacks.XMB_PINK.glowNight)
        // May: day tr 189A18, night tr 338633, night centre 1A2F1A.
        assertEquals(0xFF189A18.toInt(), ThemePacks.XMB_GREEN.accentDay)
        assertEquals(0xFF338633.toInt(), ThemePacks.XMB_GREEN.accentNight)
        assertEquals(0xFF1A2F1A.toInt(), ThemePacks.XMB_GREEN.glowNight)
        // July: day centre 02CDC3, day br glow 10E7E2, night tr 148D8A.
        assertEquals(0xFF02CDC3.toInt(), ThemePacks.XMB_TEAL.accentDay)
        assertEquals(0xFF10E7E2.toInt(), ThemePacks.XMB_TEAL.glowDay)
        assertEquals(0xFF148D8A.toInt(), ThemePacks.XMB_TEAL.accentNight)
        // September: day centre B242BD, day bl glow CF5FE3, night tl 6B0090.
        assertEquals(0xFFB242BD.toInt(), ThemePacks.XMB_MAGENTA.accentDay)
        assertEquals(0xFFCF5FE3.toInt(), ThemePacks.XMB_MAGENTA.glowDay)
        assertEquals(0xFF6B0090.toInt(), ThemePacks.XMB_MAGENTA.accentNight)
        // October: day centre E1A308, night tl D89200, night centre 573500.
        assertEquals(0xFFE1A308.toInt(), ThemePacks.XMB_ORANGE.accentDay)
        assertEquals(0xFFD89200.toInt(), ThemePacks.XMB_ORANGE.accentNight)
        assertEquals(0xFF573500.toInt(), ThemePacks.XMB_ORANGE.glowNight)
        // November: day centre 865C20, night tl 7F5525.
        assertEquals(0xFF865C20.toInt(), ThemePacks.XMB_BROWN.accentDay)
        assertEquals(0xFF7F5525.toInt(), ThemePacks.XMB_BROWN.accentNight)
        // December: day centre E3412C, night tr BD3F2C, night centre 2D1815.
        assertEquals(0xFFE3412C.toInt(), ThemePacks.XMB_RED.accentDay)
        assertEquals(0xFFBD3F2C.toInt(), ThemePacks.XMB_RED.accentNight)
        assertEquals(0xFF2D1815.toInt(), ThemePacks.XMB_RED.glowNight)
        // The January month pack is its own pack, distinct from Silver Mono.
        assertEquals(0xFFBABABA.toInt(), ThemePacks.XMB_SILVER.accentDay)
        assertTrue(ThemePacks.XMB_SILVER != ThemePacks.SILVER_MONO)
    }

    @Test fun monthMappingCoversTheCalendar() {
        val ids = listOf(
            "xmb_silver", "xmb_gold", "xmb_lime", "xmb_pink", "xmb_green", "xmb_purple",
            "xmb_teal", "xmb_blue", "xmb_magenta", "xmb_orange", "xmb_brown", "xmb_red",
        )
        for (m in 0..11) assertEquals(ids[m], ThemePacks.forMonth(m).id)
        // Out-of-range months clamp to the ends.
        assertEquals(ThemePacks.XMB_SILVER, ThemePacks.forMonth(-3))
        assertEquals(ThemePacks.XMB_RED, ThemePacks.forMonth(42))
    }

    @Test fun xmbAutoResolvesToTheCurrentMonth() {
        for (m in 0..11) {
            assertEquals(ThemePacks.forMonth(m), ThemePacks.resolvePack(ThemePacks.AUTO_ID, m))
        }
        // Every other id renders as itself, whatever the month.
        assertEquals(ThemePacks.NIGHT_CITY, ThemePacks.resolvePack("night_city", 9))
        assertEquals(ThemePacks.ORBITAL, ThemePacks.resolvePack(null, 9))
        assertEquals("Currently: October Orange", ThemePacks.autoSubtitle(9))
        assertEquals("Currently: January Silver", ThemePacks.autoSubtitle(0))
    }

    @Test fun schedulePicksDayOrNightSlotByNightness() {
        // Noon: full day on the PS3 schedule.
        assertEquals("xmb_teal", ThemePacks.scheduledPackId("xmb_teal", "night_city", 12f))
        // Late evening and the small hours: full night.
        assertEquals("night_city", ThemePacks.scheduledPackId("xmb_teal", "night_city", 23f))
        assertEquals("night_city", ThemePacks.scheduledPackId("xmb_teal", "night_city", 0f))
        // Blank slots fall back to the default pack.
        assertEquals(ThemePacks.DEFAULT_ID, ThemePacks.scheduledPackId(null, null, 12f))
        assertEquals(ThemePacks.DEFAULT_ID, ThemePacks.scheduledPackId("", "  ", 23f))
    }

    @Test fun topologyAccentsResolveAndFallBack() {
        // Night City on Orbitals is cyan-forward, day and night
        // (a static pack's override never shifts).
        assertEquals(0xFF5EF6FF.toInt(), ThemePacks.accentAt(ThemePacks.NIGHT_CITY, 12f, "ORBITALS"))
        assertEquals(0xFF5EF6FF.toInt(), ThemePacks.accentAt(ThemePacks.NIGHT_CITY, 23f, "ORBITALS"))
        // Honeycomb keeps the signature yellow; unknown topologies too.
        assertEquals(0xFFFFD742.toInt(), ThemePacks.accentAt(ThemePacks.NIGHT_CITY, 12f, "HONEYCOMB"))
        assertEquals(0xFFFFD742.toInt(), ThemePacks.accentAt(ThemePacks.NIGHT_CITY, 12f, "NO_SUCH"))
        assertEquals(0xFFFFD742.toInt(), ThemePacks.accentAt(ThemePacks.NIGHT_CITY, 12f, null))
        // XMB Purple on Orbitals takes the glow lavender by day.
        assertEquals(0xFFC680DA.toInt(), ThemePacks.accentDayFor(ThemePacks.XMB_PURPLE, "ORBITALS"))
        // XMB Blue on the helix: day override 06B1DE, shifted by the
        // pack's own day-to-night delta (3D9DD3 -> 008B9E) after dark:
        // (06-3D, B1-12, DE-35) clamped = (00, 9F, A9).
        assertEquals(0xFF06B1DE.toInt(), ThemePacks.accentDayFor(ThemePacks.XMB_BLUE, "DNA_HELIX"))
        assertEquals(0xFF009FA9.toInt(), ThemePacks.accentNightFor(ThemePacks.XMB_BLUE, "DNA_HELIX"))
        assertEquals(0xFF009FA9.toInt(), ThemePacks.accentAt(ThemePacks.XMB_BLUE, 23f, "DNA_HELIX"))
        // A pack without overrides ignores the topology entirely.
        assertEquals(ThemePacks.XMB_TEAL.accentDay, ThemePacks.accentDayFor(ThemePacks.XMB_TEAL, "ORBITALS"))
    }

    @Test fun fontDimensionResolution() {
        assertEquals(DisplayFont.RAJDHANI, ThemePacks.displayFontFor("pack", ThemePacks.NIGHT_CITY))
        assertEquals(DisplayFont.SYSTEM, ThemePacks.displayFontFor("pack", ThemePacks.XMB_BLUE))
        assertEquals(DisplayFont.SYSTEM, ThemePacks.displayFontFor("system", ThemePacks.NIGHT_CITY))
        assertEquals(DisplayFont.RAJDHANI, ThemePacks.displayFontFor("rajdhani", ThemePacks.ORBITAL))
        assertEquals(DisplayFont.SYSTEM, ThemePacks.displayFontFor(null, ThemePacks.ORBITAL))
        assertEquals(DisplayFont.SYSTEM, ThemePacks.displayFontFor("bogus", ThemePacks.XMB_BLUE))
    }

    /** Phase B/C foundations: every pack carries the new tokens,
     *  behaviour-neutral in Phase A. */
    @Test fun phaseFoundationsAreCarried() {
        for (pack in ThemePacks.ALL) {
            assertEquals(MotionDialect.STOCK, pack.motionDialect)
            assertEquals(null, pack.suggestedTopology)
        }
        for (pack in ThemePacks.XMB_MONTHS) assertEquals(BackgroundStyle.WAVE, pack.backgroundStyle)
        assertEquals(BackgroundStyle.WAVE, ThemePacks.XMB_AUTO.backgroundStyle)
        assertEquals(BackgroundStyle.WAVE, ThemePacks.SILVER_MONO.backgroundStyle)
        assertEquals(BackgroundStyle.GRID, ThemePacks.NIGHT_CITY.backgroundStyle)
        assertEquals(BackgroundStyle.NONE, ThemePacks.ORBITAL.backgroundStyle)
        // Copies carry the new fields through.
        val copy = ThemePacks.NIGHT_CITY.copy(name = "Copy")
        assertEquals(BackgroundStyle.GRID, copy.backgroundStyle)
        assertEquals(ThemePacks.NIGHT_CITY.accentByTopology, copy.accentByTopology)
        assertEquals(ThemePacks.NIGHT_CITY, ThemePacks.byId(copy.id))
    }

    @Test fun onlyNightCityUsesRajdhani() {
        assertEquals(DisplayFont.RAJDHANI, ThemePacks.NIGHT_CITY.displayFont)
        for (pack in ThemePacks.ALL) {
            if (pack != ThemePacks.NIGHT_CITY) assertEquals(DisplayFont.SYSTEM, pack.displayFont)
        }
    }
}
