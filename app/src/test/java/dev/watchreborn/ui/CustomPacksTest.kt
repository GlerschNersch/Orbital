package dev.watchreborn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CustomPacksTest {

    private fun samplePack(): ThemePack = ThemePack(
        id = "custom_123",
        name = "Ember Test",
        subtitle = "Custom",
        accentDay = 0xFFE1A308.toInt(),
        accentNight = 0xFFB97E06.toInt(),
        glowDay = 0xFFFFD742.toInt(),
        glowNight = 0xFF8A6D1F.toInt(),
        surface = 0xFF121221.toInt(),
        surfaceAlt = 0xFF0E0E17.toInt(),
        latticeStroke = 0xFF7A5B14.toInt(),
        displayFont = DisplayFont.RAJDHANI,
        backgroundStyle = BackgroundStyle.GRID,
        materialStyle = MaterialStyle.NEON,
    )

    @Test fun fileRoundTripPreservesEveryField() {
        val pack = samplePack()
        val back = CustomPacks.parse(CustomPacks.toJson(pack), "custom_999")
        assertEquals("custom_999", back.id) // a file import always lands under a fresh id
        assertEquals(pack.name, back.name)
        assertEquals(pack.subtitle, back.subtitle)
        assertEquals(pack.accentDay, back.accentDay)
        assertEquals(pack.accentNight, back.accentNight)
        assertEquals(pack.glowDay, back.glowDay)
        assertEquals(pack.glowNight, back.glowNight)
        assertEquals(pack.surface, back.surface)
        assertEquals(pack.surfaceAlt, back.surfaceAlt)
        assertEquals(pack.latticeStroke, back.latticeStroke)
        assertEquals(pack.displayFont, back.displayFont)
        assertEquals(pack.backgroundStyle, back.backgroundStyle)
        assertEquals(pack.materialStyle, back.materialStyle)
        // Customs never carry a built-in's personality.
        assertEquals(null, back.suggestedTopology)
        assertTrue(back.accentByTopology.isEmpty())
        assertEquals(MotionDialect.STOCK, back.motionDialect)
    }

    @Test fun builtInExportRoundTripsAsACustom() {
        // Export writes a built-in's tokens; import lands as a custom
        // carrying the same name and colours (the Night City proof).
        val back = CustomPacks.parse(CustomPacks.toJson(ThemePacks.NIGHT_CITY), "custom_1")
        assertEquals("Night City", back.name)
        assertEquals(ThemePacks.NIGHT_CITY.accentDay, back.accentDay)
        assertEquals(ThemePacks.NIGHT_CITY.glowDay, back.glowDay)
        assertEquals(ThemePacks.NIGHT_CITY.surface, back.surface)
        assertEquals(DisplayFont.RAJDHANI, back.displayFont)
        assertEquals(BackgroundStyle.GRID, back.backgroundStyle)
        assertEquals(MaterialStyle.NEON, back.materialStyle)
        assertTrue(CustomPacks.isCustomId(back.id))
    }

    @Test fun hexRoundTripsIncludingAlpha() {
        assertEquals("#A6E36E", CustomPacks.hex(0xFFA6E36E.toInt()))
        assertEquals(0xFFA6E36E.toInt(), CustomPacks.parseHex("#A6E36E", "accent"))
        assertEquals(0xFFA6E36E.toInt(), CustomPacks.parseHex("#ffa6e36e", "accent"))
        assertEquals("#80A6E36E", CustomPacks.hex(0x80A6E36E.toInt()))
        assertEquals(0x80A6E36E.toInt(), CustomPacks.parseHex("#80A6E36E", "accent"))
    }

    private fun expectPackError(json: String, needle: String) {
        try {
            CustomPacks.parse(json, "custom_x")
            fail("expected a PackException mentioning '$needle'")
        } catch (e: PackException) {
            assertTrue("message '${e.message}' should mention '$needle'", e.message!!.contains(needle))
        }
    }

    @Test fun guardsRejectBadFilesWithClearMessages() {
        expectPackError("", "empty")
        expectPackError("not json at all", "not a valid theme pack")
        expectPackError("""{"format":"other","name":"X","tokens":{"accent":"#112233"}}""", "not an Orbital theme pack")
        expectPackError("""{"tokens":{"accent":"#112233"}}""", "no name")
        expectPackError("""{"name":"${"A".repeat(41)}","tokens":{"accent":"#112233"}}""", "too long")
        expectPackError("""{"name":"X"}""", "missing its colour tokens")
        expectPackError("""{"name":"X","tokens":{}}""", "missing its accent")
        expectPackError("""{"name":"X","tokens":{"accent":"orange"}}""", "not a hex colour")
        expectPackError("""{"name":"X","tokens":{"accent":"#12G456"}}""", "not valid hex")
        expectPackError("""{"name":"X","tokens":{"accent":"#112233"},"font":"comic"}""", "Unknown font")
        expectPackError("""{"name":"X","tokens":{"accent":"#112233"},"background":"plasma"}""", "Unknown background")
        expectPackError("""{"name":"X","tokens":{"accent":"#112233"},"material":"chrome"}""", "Unknown material")
    }

    @Test fun minimalFileFillsDefaultsInFamily() {
        val back = CustomPacks.parse("""{"name":"Bare","tokens":{"accent":"#E1A308"}}""", "custom_7")
        assertEquals(0xFFE1A308.toInt(), back.accentDay)
        assertEquals(back.accentDay, back.accentNight) // night falls back to day
        assertEquals(back.accentDay, back.glowDay) // glow falls back to accent
        assertEquals(back.glowDay, back.glowNight)
        assertEquals(DisplayFont.SYSTEM, back.displayFont)
        assertEquals(BackgroundStyle.NONE, back.backgroundStyle)
        assertEquals(MaterialStyle.MATTE, back.materialStyle)
        assertEquals("Custom", back.subtitle)
    }

    @Test fun listRoundTripAndTolerance() {
        val a = samplePack()
        val b = CustomPacks.buildPack("Second", 0xFF3D9DD3.toInt(), 0xFF06B1DE.toInt(), 1, BackgroundStyle.WAVE, false, "custom_456")
        val back = CustomPacks.parseList(CustomPacks.serializeList(listOf(a, b)))
        assertEquals(listOf("custom_123", "custom_456"), back.map { it.id })
        assertEquals(a.accentDay, back[0].accentDay)
        assertEquals(b.surface, back[1].surface)
        // Corruption tolerance: garbage and non-custom entries are dropped.
        assertEquals(emptyList<ThemePack>(), CustomPacks.parseList("garbage"))
        assertEquals(emptyList<ThemePack>(), CustomPacks.parseList(null))
        assertEquals(emptyList<ThemePack>(), CustomPacks.parseList("""[{"id":"night_city","name":"Fake","tokens":{"accent":"#112233"}}]"""))
        // Duplicate ids keep the first.
        val dup = CustomPacks.parseList(CustomPacks.serializeList(listOf(a, a.copy(name = "Clone"))))
        assertEquals(1, dup.size)
        assertEquals("Ember Test", dup[0].name)
    }

    @Test fun builderMapsStepsAndDefaults() {
        val pack = CustomPacks.buildPack("  ", 0xFFE1A308.toInt(), 0xFFFFD742.toInt(), 0, BackgroundStyle.NONE, true, "custom_1")
        assertEquals("Custom Pack", pack.name) // blank names fall back
        assertEquals("Custom", pack.subtitle)
        assertEquals(CustomPacks.SURFACE_STEPS[0].surface, pack.surface)
        assertEquals(CustomPacks.SURFACE_STEPS[0].surfaceAlt, pack.surfaceAlt)
        assertEquals(pack.accentDay, pack.accentNight)
        assertEquals(DisplayFont.RAJDHANI, pack.displayFont)
        // The lattice is the accent pulled toward the surface.
        assertEquals(ThemePacks.blend(pack.accentDay, pack.surface, 0.45f), pack.latticeStroke)
        // Out-of-range steps clamp to the ladder.
        val clamped = CustomPacks.buildPack("X", 0xFF000000.toInt(), 0xFF000000.toInt(), 99, BackgroundStyle.GRID, false, "custom_2")
        assertEquals(CustomPacks.SURFACE_STEPS.last().surface, clamped.surface)
        // The swatch grid: 12 month accents + Night City pair + white + grey.
        assertEquals(16, CustomPacks.SWATCHES.size)
        assertEquals(ThemePacks.XMB_MONTHS.map { it.accentDay }, CustomPacks.SWATCHES.subList(0, 12))
        assertFalse(CustomPacks.isCustomId("night_city"))
        assertTrue(CustomPacks.isCustomId("custom_1"))
    }
}
