package dev.watchreborn.ui

import kotlin.math.roundToInt

/**
 * 3.8.2 theme packs: named bundles of the colours Orbital's chrome is
 * drawn with, so the whole field can change identity in one pick.
 * Modelled loosely on TSFReborn's theme format (a named pack carrying
 * a token set), kept as a plain Kotlin data model: no file parsing,
 * no Android types, JVM-testable.
 *
 * Token meanings:
 * - [ThemePack.accentDay] / [ThemePack.accentNight]: the accent in
 *   daylight and after dark. XMB packs carry the PlayStation 3's
 *   month colours as extracted in XMBReborn's MonthPalette: the
 *   stored texture values are dark, so the working accent and glow
 *   are the month rows' bright corner values, while the centres
 *   anchor the night side. [ThemePacks.nightness] is the PS3's own
 *   day/night crossfade schedule, ported from MonthPalette.
 * - [ThemePack.glowDay] / [ThemePack.glowNight]: the constellation
 *   line and shockwave colour when searching.
 * - [ThemePack.surface]: sheet and picker panels, the search pill.
 * - [ThemePack.surfaceAlt]: the Settings page background.
 * - [ThemePack.latticeStroke]: hairline dividers in the sheets.
 * - [ThemePack.displayFont]: [DisplayFont.RAJDHANI] restyles the
 *   chrome typeface (search pill, sheet and picker headers,
 *   constellation header and chips); app labels stay system.
 */

/** Display typeface for Orbital's chrome. */
enum class DisplayFont { SYSTEM, RAJDHANI }

/** One named theme pack: a token map with an identity. */
data class ThemePack(
    val id: String,
    val name: String,
    val subtitle: String,
    val accentDay: Int,
    val accentNight: Int,
    val glowDay: Int,
    val glowNight: Int,
    val surface: Int,
    val surfaceAlt: Int,
    val latticeStroke: Int,
    val displayFont: DisplayFont,
)

object ThemePacks {

    const val DEFAULT_ID = "orbital"

    /**
     * The stock 3.8.1 look, token for token: the accent green every
     * install already has, the sheet panel (35,37,43), the page
     * background (24,26,30) and the hairline grey (96,98,106).
     * Selecting this pack is a visual no-op.
     */
    val ORBITAL = ThemePack(
        id = DEFAULT_ID,
        name = "Orbital",
        subtitle = "The stock look, green on graphite",
        accentDay = 0xFFA6E36E.toInt(),
        accentNight = 0xFFA6E36E.toInt(),
        glowDay = 0xFFA6E36E.toInt(),
        glowNight = 0xFFA6E36E.toInt(),
        surface = 0xFF232529.toInt(),
        surfaceAlt = 0xFF181A1E.toInt(),
        latticeStroke = 0xFF60626A.toInt(),
        displayFont = DisplayFont.SYSTEM,
    )

    /**
     * XMB August (MonthPalette row 7). Day corners run 00054E /
     * 0450AF / 3D9DD3 / 06B1DE around centre 0A68BB; night corners
     * 008B9E / 457C95 / 00255B / 000021 around centre 003361.
     */
    val XMB_BLUE = ThemePack(
        id = "xmb_blue",
        name = "XMB Blue",
        subtitle = "From XMBReborn: the PS3's August blue",
        accentDay = 0xFF3D9DD3.toInt(),
        accentNight = 0xFF008B9E.toInt(),
        glowDay = 0xFF06B1DE.toInt(),
        glowNight = 0xFF003361.toInt(),
        surface = 0xFF1C2836.toInt(),
        surfaceAlt = 0xFF111820.toInt(),
        latticeStroke = 0xFF2E4A66.toInt(),
        displayFont = DisplayFont.SYSTEM,
    )

    /**
     * XMB June (MonthPalette row 5). Day corners run 8D539C /
     * C680DA / 6955A2 / 845DD9 around centre 9B65D2; night corners
     * CA9BD6 / D2A3DE over black around centre 594966.
     */
    val XMB_PURPLE = ThemePack(
        id = "xmb_purple",
        name = "XMB Purple",
        subtitle = "From XMBReborn: the PS3's June purple",
        accentDay = 0xFF9B65D2.toInt(),
        accentNight = 0xFFCA9BD6.toInt(),
        glowDay = 0xFFC680DA.toInt(),
        glowNight = 0xFF594966.toInt(),
        surface = 0xFF2A2138.toInt(),
        surfaceAlt = 0xFF1A1424.toInt(),
        latticeStroke = 0xFF4A3A63.toInt(),
        displayFont = DisplayFont.SYSTEM,
    )

    /**
     * Cyberpunk 2077's MainColors as extracted in
     * Cyberpunk2077Reborn (the inkstyle JSON's values are unresolved
     * variant references; these are the game's HDR floats from
     * gen_theme.py's BASE table, clamped to SDR the same way):
     * Yellow (1.119, .844, .257) = FFD742, Blue (.369, .965, 1.0) =
     * 5EF6FF, DarkRed (.263, .086, .094) = 431618, backgrounds
     * bg-dark (18,18,33) and Fullscreen darkest (14,14,23). Static:
     * day and night values are the same. Rajdhani (OFL) is bundled
     * in assets/fonts and styles the chrome.
     */
    val NIGHT_CITY = ThemePack(
        id = "night_city",
        name = "Night City",
        subtitle = "Cyberpunk yellow and cyan, set in Rajdhani",
        accentDay = 0xFFFFD742.toInt(),
        accentNight = 0xFFFFD742.toInt(),
        glowDay = 0xFF5EF6FF.toInt(),
        glowNight = 0xFF5EF6FF.toInt(),
        surface = 0xFF121221.toInt(),
        surfaceAlt = 0xFF0E0E17.toInt(),
        latticeStroke = 0xFF431618.toInt(),
        displayFont = DisplayFont.RAJDHANI,
    )

    /**
     * XMB January (MonthPalette row 0). Day centre BABABA under
     * corners E8E8E8 / D1D1D1 / DDDDDD / B5B7B8; night centre 525252
     * under B7B7B7 / B3B3B3 over 161616. Only the chrome goes
     * monochrome: app icons and category tints keep their colours.
     */
    val SILVER_MONO = ThemePack(
        id = "silver_mono",
        name = "Silver Mono",
        subtitle = "From XMBReborn: January silver, monochrome chrome",
        accentDay = 0xFFBABABA.toInt(),
        accentNight = 0xFFB3B3B3.toInt(),
        glowDay = 0xFFE8E8E8.toInt(),
        glowNight = 0xFF525252.toInt(),
        surface = 0xFF26262A.toInt(),
        surfaceAlt = 0xFF17171A.toInt(),
        latticeStroke = 0xFF55565C.toInt(),
        displayFont = DisplayFont.SYSTEM,
    )

    /** All packs, in picker order. */
    val ALL: List<ThemePack> = listOf(ORBITAL, XMB_BLUE, XMB_PURPLE, NIGHT_CITY, SILVER_MONO)

    /** The pack for a persisted [id], or the default when unknown. */
    fun byId(id: String?): ThemePack = ALL.firstOrNull { it.id == id } ?: ORBITAL

    // The PS3's own schedule (from XMBReborn's MonthPalette, fitted
    // to BACKGROUND.mnu): night fades into day from 00:00 to 05:10,
    // day fades into night from 18:30 to 20:20.
    private const val NIGHT2DAY_BEGIN = 0f
    private const val NIGHT2DAY_END = 5.16611f
    private const val DAY2NIGHT_BEGIN = 18.498f
    private const val DAY2NIGHT_END = 20.3312f

    /** 0 = full day, 1 = full night, for an hour of day (0..24, fractional). */
    fun nightness(hour: Float): Float {
        val h = ((hour % 24f) + 24f) % 24f
        return when {
            h < NIGHT2DAY_END -> 1f - smooth((h - NIGHT2DAY_BEGIN) / (NIGHT2DAY_END - NIGHT2DAY_BEGIN))
            h < DAY2NIGHT_BEGIN -> 0f
            h < DAY2NIGHT_END -> smooth((h - DAY2NIGHT_BEGIN) / (DAY2NIGHT_END - DAY2NIGHT_BEGIN))
            else -> 1f
        }
    }

    private fun smooth(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** Per-channel lerp (alpha included) from [day] to [night] by [n] in 0..1. */
    fun blend(day: Int, night: Int, n: Float): Int {
        val t = n.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val d = (day ushr shift) and 0xFF
            val k = (night ushr shift) and 0xFF
            return (d + (k - d) * t).roundToInt().coerceIn(0, 255)
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** The pack's effective accent at [hour], day/night blended. */
    fun accentAt(pack: ThemePack, hour: Float): Int =
        blend(pack.accentDay, pack.accentNight, nightness(hour))

    /** The pack's effective glow colour at [hour], day/night blended. */
    fun glowAt(pack: ThemePack, hour: Float): Int =
        blend(pack.glowDay, pack.glowNight, nightness(hour))
}
