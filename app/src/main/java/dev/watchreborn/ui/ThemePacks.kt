package dev.watchreborn.ui

import kotlin.math.roundToInt

/**
 * Theme packs: named bundles of the colours Orbital's chrome is
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
 * - [ThemePack.surfaceAlt]: the Settings page background, the dock.
 * - [ThemePack.latticeStroke]: hairline dividers in the sheets.
 * - [ThemePack.displayFont]: [DisplayFont.RAJDHANI] restyles the
 *   chrome typeface (search pill, sheet and picker headers,
 *   constellation header and chips); app labels stay system.
 *
 * 3.9.0 (theming phase A) foundations for the later phases:
 * - [ThemePack.backgroundStyle]: the procedural backdrop a pack
 *   wants (drawn from 3.10.0, phase B).
 * - [ThemePack.motionDialect]: the MotionTokens variant a pack
 *   moves with (variants land in 3.10.0, phase B).
 * - [ThemePack.suggestedTopology]: the layout a personality pack
 *   suggests on a manual pick (applied once in 3.10.0, phase B;
 *   never re-imposed afterwards).
 * - [ThemePack.accentByTopology]: per-topology accent overrides,
 *   keyed by ClusterTopology name, day values (see
 *   [ThemePacks.accentAt] for the night derivation).
 * - [ThemePack.materialStyle]: how island tiles are surfaced
 *   (3.10.0, phase B): MATTE is the stock tile, GLASS thins the
 *   fill and catches a light on the top edge, NEON blooms the rim.
 */

/** Display typeface for Orbital's chrome. */
enum class DisplayFont { SYSTEM, RAJDHANI }

/** Procedural backdrop a pack asks for (drawn from Phase B on). */
enum class BackgroundStyle { NONE, WAVE, GRID }

/** Motion dialect a pack moves with: STOCK is the tuned 3.8.3
 *  motion, CRISP retimes it shorter and snappier, DRIFT longer
 *  and softer (see MotionTokens.params). */
enum class MotionDialect { STOCK, CRISP, DRIFT }

/** How a pack surfaces its island tiles (3.10.0). */
enum class MaterialStyle { MATTE, GLASS, NEON }

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
    val backgroundStyle: BackgroundStyle = BackgroundStyle.NONE,
    val motionDialect: MotionDialect = MotionDialect.STOCK,
    val suggestedTopology: String? = null,
    val accentByTopology: Map<String, Int> = emptyMap(),
    val materialStyle: MaterialStyle = MaterialStyle.MATTE,
)

object ThemePacks {

    const val DEFAULT_ID = "orbital"
    const val AUTO_ID = "xmb_auto"

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

    // The twelve XMB month packs. Derivation, identical for every
    // row of XMBReborn's MonthPalette (and matching the values
    // 3.8.2 shipped for August and June): the day accent is the
    // row's centre or its most characteristic bright corner, the
    // day glow its brightest corner, the night accent its brightest
    // night corner, and the night glow always the night centre.
    // Surfaces are the month's hue taken dark, in the same ladder
    // the shipped packs use (panel / page / hairline).

    /** January (row 0): day centre BABABA, corners E8E8E8 / D1D1D1 /
     *  DDDDDD / B5B7B8; night corners B7B7B7 / B3B3B3 over 161616,
     *  centre 525252. The month pack (brighter surfaces); the
     *  separate Silver Mono pack below is the monochrome variant. */
    val XMB_SILVER = ThemePack(
        id = "xmb_silver",
        name = "XMB Silver",
        subtitle = "From XMBReborn: the PS3's January silver",
        accentDay = 0xFFBABABA.toInt(),
        accentNight = 0xFFB7B7B7.toInt(),
        glowDay = 0xFFD1D1D1.toInt(),
        glowNight = 0xFF525252.toInt(),
        surface = 0xFF2A2A2E.toInt(),
        surfaceAlt = 0xFF1B1B1F.toInt(),
        latticeStroke = 0xFF5A5B62.toInt(),
        displayFont = DisplayFont.SYSTEM,
        backgroundStyle = BackgroundStyle.WAVE,
        motionDialect = MotionDialect.DRIFT,
        suggestedTopology = "ORBITALS",
        materialStyle = MaterialStyle.GLASS,
    )

    /** February (row 1): day centre CDA30E, corners D0C123 /
     *  D5C62D / D5E12F / D6DD35; night corners B7B072 / CDC899
     *  over 16160E, centre 555032. */
    val XMB_GOLD = ThemePack(
        id = "xmb_gold",
        name = "XMB Gold",
        subtitle = "From XMBReborn: the PS3's February gold",
        accentDay = 0xFFCDA30E.toInt(),
        accentNight = 0xFFB7B072.toInt(),
        glowDay = 0xFFD6DD35.toInt(),
        glowNight = 0xFF555032.toInt(),
        surface = 0xFF2B2616.toInt(),
        surfaceAlt = 0xFF191708.toInt(),
        latticeStroke = 0xFF574D24.toInt(),
        displayFont = DisplayFont.SYSTEM,
        backgroundStyle = BackgroundStyle.WAVE,
        motionDialect = MotionDialect.DRIFT,
        suggestedTopology = "ORBITALS",
        materialStyle = MaterialStyle.GLASS,
    )

    /** March (row 2): day centre 6CA919, corners 81BE29 / A6B924 /
     *  80AA1F / 599F18; night corners 92A56B / 91A56A over 11130D,
     *  centre 464D33. */
    val XMB_LIME = ThemePack(
        id = "xmb_lime",
        name = "XMB Lime",
        subtitle = "From XMBReborn: the PS3's March lime",
        accentDay = 0xFF81BE29.toInt(),
        accentNight = 0xFF92A56B.toInt(),
        glowDay = 0xFFA6B924.toInt(),
        glowNight = 0xFF464D33.toInt(),
        surface = 0xFF222E16.toInt(),
        surfaceAlt = 0xFF141C0E.toInt(),
        latticeStroke = 0xFF47632C.toInt(),
        displayFont = DisplayFont.SYSTEM,
        backgroundStyle = BackgroundStyle.WAVE,
        motionDialect = MotionDialect.DRIFT,
        suggestedTopology = "ORBITALS",
        materialStyle = MaterialStyle.GLASS,
    )

    /** April (row 3): day centre DF7E98, corners E07992 / DB9EAB /
     *  E7497A / DC7585; night corners CAA5AC / C9A7AE over 100F0F,
     *  centre 6C575B. */
    val XMB_PINK = ThemePack(
        id = "xmb_pink",
        name = "XMB Pink",
        subtitle = "From XMBReborn: the PS3's April pink",
        accentDay = 0xFFE7497A.toInt(),
        accentNight = 0xFFCAA5AC.toInt(),
        glowDay = 0xFFDB9EAB.toInt(),
        glowNight = 0xFF6C575B.toInt(),
        surface = 0xFF33202A.toInt(),
        surfaceAlt = 0xFF211419.toInt(),
        latticeStroke = 0xFF6B4256.toInt(),
        displayFont = DisplayFont.SYSTEM,
        backgroundStyle = BackgroundStyle.WAVE,
        motionDialect = MotionDialect.DRIFT,
        suggestedTopology = "ORBITALS",
        materialStyle = MaterialStyle.GLASS,
    )

    /** May (row 4): day centre 168516, corners 136C13 / 189A18 /
     *  137213 / 179617; night corners 234D23 / 338633 over 111C11,
     *  centre 1A2F1A. */
    val XMB_GREEN = ThemePack(
        id = "xmb_green",
        name = "XMB Green",
        subtitle = "From XMBReborn: the PS3's May green",
        accentDay = 0xFF189A18.toInt(),
        accentNight = 0xFF338633.toInt(),
        glowDay = 0xFF179617.toInt(),
        glowNight = 0xFF1A2F1A.toInt(),
        surface = 0xFF16281A.toInt(),
        surfaceAlt = 0xFF0E1A10.toInt(),
        latticeStroke = 0xFF2E5C36.toInt(),
        displayFont = DisplayFont.SYSTEM,
        backgroundStyle = BackgroundStyle.WAVE,
        motionDialect = MotionDialect.DRIFT,
        suggestedTopology = "ORBITALS",
        materialStyle = MaterialStyle.GLASS,
    )

    /**
     * XMB June (MonthPalette row 5). Day corners run 8D539C /
     * C680DA / 6955A2 / 845DD9 around centre 9B65D2; night corners
     * CA9BD6 / D2A3DE over black around centre 594966.
     * Per-topology: Orbitals rings take the glow lavender C680DA.
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
        backgroundStyle = BackgroundStyle.WAVE,
        motionDialect = MotionDialect.DRIFT,
        suggestedTopology = "ORBITALS",
        materialStyle = MaterialStyle.GLASS,
        accentByTopology = mapOf("ORBITALS" to 0xFFC680DA.toInt()),
    )

    /** July (row 6): day centre 02CDC3, corners 00AA8F / 02DDCE /
     *  00BABD / 10E7E2; night corners 0C4C48 / 148D8A over 111313,
     *  centre 123635. */
    val XMB_TEAL = ThemePack(
        id = "xmb_teal",
        name = "XMB Teal",
        subtitle = "From XMBReborn: the PS3's July teal",
        accentDay = 0xFF02CDC3.toInt(),
        accentNight = 0xFF148D8A.toInt(),
        glowDay = 0xFF10E7E2.toInt(),
        glowNight = 0xFF123635.toInt(),
        surface = 0xFF14302E.toInt(),
        surfaceAlt = 0xFF0C1E1C.toInt(),
        latticeStroke = 0xFF256B66.toInt(),
        displayFont = DisplayFont.SYSTEM,
        backgroundStyle = BackgroundStyle.WAVE,
        motionDialect = MotionDialect.DRIFT,
        suggestedTopology = "ORBITALS",
        materialStyle = MaterialStyle.GLASS,
    )

    /**
     * XMB August (MonthPalette row 7). Day corners run 00054E /
     * 0450AF / 3D9DD3 / 06B1DE around centre 0A68BB; night corners
     * 008B9E / 457C95 / 00255B / 000021 around centre 003361.
     * Per-topology: the DNA helix strands take the bright cyan
     * 06B1DE against the deeper sky-blue accent.
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
        backgroundStyle = BackgroundStyle.WAVE,
        motionDialect = MotionDialect.DRIFT,
        suggestedTopology = "ORBITALS",
        materialStyle = MaterialStyle.GLASS,
        accentByTopology = mapOf("DNA_HELIX" to 0xFF06B1DE.toInt()),
    )

    /** September (row 8): day centre B242BD, corners CA5CDD /
     *  91369C / CF5FE3 / AE41C4; night corners 6B0090 / 3F005B /
     *  35004C / 190020, centre 3D004A. */
    val XMB_MAGENTA = ThemePack(
        id = "xmb_magenta",
        name = "XMB Magenta",
        subtitle = "From XMBReborn: the PS3's September magenta",
        accentDay = 0xFFB242BD.toInt(),
        accentNight = 0xFF6B0090.toInt(),
        glowDay = 0xFFCF5FE3.toInt(),
        glowNight = 0xFF3D004A.toInt(),
        surface = 0xFF2E1A33.toInt(),
        surfaceAlt = 0xFF1C1021.toInt(),
        latticeStroke = 0xFF5C3468.toInt(),
        displayFont = DisplayFont.SYSTEM,
        backgroundStyle = BackgroundStyle.WAVE,
        motionDialect = MotionDialect.DRIFT,
        suggestedTopology = "ORBITALS",
        materialStyle = MaterialStyle.GLASS,
    )

    /** October (row 9): day centre E1A308, corners E2B10F /
     *  E59805 / D8C500 / E1B311; night corners D89200 / D98B00
     *  over 050000, centre 573500. */
    val XMB_ORANGE = ThemePack(
        id = "xmb_orange",
        name = "XMB Orange",
        subtitle = "From XMBReborn: the PS3's October orange",
        accentDay = 0xFFE1A308.toInt(),
        accentNight = 0xFFD89200.toInt(),
        glowDay = 0xFFE59805.toInt(),
        glowNight = 0xFF573500.toInt(),
        surface = 0xFF33250F.toInt(),
        surfaceAlt = 0xFF211708.toInt(),
        latticeStroke = 0xFF6E4E1C.toInt(),
        displayFont = DisplayFont.SYSTEM,
        backgroundStyle = BackgroundStyle.WAVE,
        motionDialect = MotionDialect.DRIFT,
        suggestedTopology = "ORBITALS",
        materialStyle = MaterialStyle.GLASS,
    )

    /** November (row 10): day centre 865C20, corners 774A18 /
     *  98742E; night corners 7F5525 over 111110, centre 4B3618. */
    val XMB_BROWN = ThemePack(
        id = "xmb_brown",
        name = "XMB Brown",
        subtitle = "From XMBReborn: the PS3's November brown",
        accentDay = 0xFF865C20.toInt(),
        accentNight = 0xFF7F5525.toInt(),
        glowDay = 0xFF98742E.toInt(),
        glowNight = 0xFF4B3618.toInt(),
        surface = 0xFF2B2115.toInt(),
        surfaceAlt = 0xFF1A140C.toInt(),
        latticeStroke = 0xFF59452A.toInt(),
        displayFont = DisplayFont.SYSTEM,
        backgroundStyle = BackgroundStyle.WAVE,
        motionDialect = MotionDialect.DRIFT,
        suggestedTopology = "ORBITALS",
        materialStyle = MaterialStyle.GLASS,
    )

    /** December (row 11): day centre E3412C, corners D7412D /
     *  E6432D / D13D2A / E5432D; night corners 40211C / BD3F2C
     *  over 0E0D0D, centre 2D1815. */
    val XMB_RED = ThemePack(
        id = "xmb_red",
        name = "XMB Red",
        subtitle = "From XMBReborn: the PS3's December red",
        accentDay = 0xFFE3412C.toInt(),
        accentNight = 0xFFBD3F2C.toInt(),
        glowDay = 0xFFE6432D.toInt(),
        glowNight = 0xFF2D1815.toInt(),
        surface = 0xFF331B17.toInt(),
        surfaceAlt = 0xFF201110.toInt(),
        latticeStroke = 0xFF6B3830.toInt(),
        displayFont = DisplayFont.SYSTEM,
        backgroundStyle = BackgroundStyle.WAVE,
        motionDialect = MotionDialect.DRIFT,
        suggestedTopology = "ORBITALS",
        materialStyle = MaterialStyle.GLASS,
    )

    /** The twelve month packs in calendar order (index = month). */
    val XMB_MONTHS: List<ThemePack> = listOf(
        XMB_SILVER, XMB_GOLD, XMB_LIME, XMB_PINK, XMB_GREEN, XMB_PURPLE,
        XMB_TEAL, XMB_BLUE, XMB_MAGENTA, XMB_ORANGE, XMB_BROWN, XMB_RED,
    )

    /**
     * XMB Auto: the pack that follows the calendar, like the PS3
     * did. Its stored tokens mirror October's (a picker swatch and
     * a fallback); [resolvePack] substitutes the current month's
     * pack at draw time, so these values are never what renders.
     */
    val XMB_AUTO = ThemePack(
        id = AUTO_ID,
        name = "XMB Auto",
        subtitle = "From XMBReborn: the PS3 colour for this month",
        accentDay = 0xFFE1A308.toInt(),
        accentNight = 0xFFD89200.toInt(),
        glowDay = 0xFFE59805.toInt(),
        glowNight = 0xFF573500.toInt(),
        surface = 0xFF33250F.toInt(),
        surfaceAlt = 0xFF211708.toInt(),
        latticeStroke = 0xFF6E4E1C.toInt(),
        displayFont = DisplayFont.SYSTEM,
        backgroundStyle = BackgroundStyle.WAVE,
        motionDialect = MotionDialect.DRIFT,
        suggestedTopology = "ORBITALS",
        materialStyle = MaterialStyle.GLASS,
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
     * Per-topology: Orbitals goes cyan-forward (the glow 5EF6FF);
     * Honeycomb and the rest keep the signature yellow.
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
        backgroundStyle = BackgroundStyle.GRID,
        accentByTopology = mapOf("ORBITALS" to 0xFF5EF6FF.toInt()),
        motionDialect = MotionDialect.CRISP,
        suggestedTopology = "FORCE_FIELD",
        materialStyle = MaterialStyle.NEON,
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
        backgroundStyle = BackgroundStyle.WAVE,
        suggestedTopology = "HONEYCOMB",
    )

    /** All packs, in picker order: the stock look, the twelve XMB
     *  months in calendar order, XMB Auto, then the two specials. */
    val ALL: List<ThemePack> = listOf(ORBITAL) + XMB_MONTHS + listOf(XMB_AUTO, NIGHT_CITY, SILVER_MONO)

    /**
     * The topology a manual pick of [pack] suggests from
     * [currentTopology] (a ClusterTopology name): the pack's
     * suggestion when it has one and the field is not already
     * there, else null. This is a one-shot decision made at pick
     * time only — nothing calls it on a schedule resolution or a
     * redraw, so a suggestion can never re-impose itself after
     * the user changes topology by hand.
     */
    fun suggestedTopologyFor(pack: ThemePack, currentTopology: String?): String? {
        val s = pack.suggestedTopology ?: return null
        return if (s == currentTopology) null else s
    }

    /** The pack for a persisted [id], or the default when unknown. */
    fun byId(id: String?): ThemePack = ALL.firstOrNull { it.id == id } ?: ORBITAL

    /** The XMB pack for a calendar [month] (0 = January). */
    fun forMonth(month: Int): ThemePack = XMB_MONTHS[month.coerceIn(0, 11)]

    /**
     * The pack that actually renders for a persisted [id] in a
     * given calendar [month]: XMB Auto resolves to the month's own
     * pack; every other id renders as itself.
     */
    fun resolvePack(id: String?, month: Int): ThemePack =
        if (id == AUTO_ID) forMonth(month) else byId(id)

    /**
     * Which scheduled slot is live at [hour]: the night pack once
     * the PS3 schedule is at least half into night, the day pack
     * otherwise. Unknown/blank slots fall back to the default id.
     */
    fun scheduledPackId(dayId: String?, nightId: String?, hour: Float): String =
        if (nightness(hour) >= 0.5f) nightId?.takeIf { it.isNotBlank() } ?: DEFAULT_ID
        else dayId?.takeIf { it.isNotBlank() } ?: DEFAULT_ID

    /** Month names for the XMB Auto picker's live subtitle. */
    private val MONTH_NAMES = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    /** The XMB colour names (MonthPalette's own), by month index. */
    private val MONTH_COLOR_NAMES = listOf(
        "Silver", "Gold", "Lime", "Pink", "Green", "Purple",
        "Teal", "Blue", "Magenta", "Orange", "Brown", "Red",
    )

    /** The XMB Auto row's live subtitle: "Currently: October Orange". */
    fun autoSubtitle(month: Int): String {
        val m = month.coerceIn(0, 11)
        return "Currently: ${MONTH_NAMES[m]} ${MONTH_COLOR_NAMES[m]}"
    }

    /**
     * The chrome typeface for a font-dimension pref value: "system"
     * and "rajdhani" pin a face; anything else (including the
     * default "pack") follows the pack's own [ThemePack.displayFont].
     */
    fun displayFontFor(pref: String?, pack: ThemePack): DisplayFont = when (pref) {
        "system" -> DisplayFont.SYSTEM
        "rajdhani" -> DisplayFont.RAJDHANI
        else -> pack.displayFont
    }

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

    /** The pack's day accent on [topology] (a ClusterTopology name),
     *  or the pack accent when the topology has no override. */
    fun accentDayFor(pack: ThemePack, topology: String?): Int =
        topology?.let { pack.accentByTopology[it] } ?: pack.accentDay

    /**
     * The pack's night accent on [topology]: the day override
     * shifted by the pack's own day-to-night channel delta, so an
     * override stays in the pack's family after dark. A static pack
     * (day == night) keeps its override unchanged.
     */
    fun accentNightFor(pack: ThemePack, topology: String?): Int {
        val override = topology?.let { pack.accentByTopology[it] } ?: return pack.accentNight
        fun ch(shift: Int): Int {
            val o = (override ushr shift) and 0xFF
            val d = (pack.accentDay ushr shift) and 0xFF
            val k = (pack.accentNight ushr shift) and 0xFF
            return (o + (k - d)).coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** The pack's effective accent at [hour], day/night blended. */
    fun accentAt(pack: ThemePack, hour: Float): Int = accentAt(pack, hour, null)

    /** The pack's effective accent at [hour] on [topology]. */
    fun accentAt(pack: ThemePack, hour: Float, topology: String?): Int =
        blend(accentDayFor(pack, topology), accentNightFor(pack, topology), nightness(hour))

    /** The pack's effective glow colour at [hour], day/night blended. */
    fun glowAt(pack: ThemePack, hour: Float): Int =
        blend(pack.glowDay, pack.glowNight, nightness(hour))
}
