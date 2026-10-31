package dev.watchreborn.ui

/**
 * Custom theme packs (3.11.0, theming phase C): packs the user
 * builds in the Settings editor, packs imported from
 * `.orbitalpack.json` files, and packs generated on the PC by the
 * Aloy tool (tools/make_pack.py) all live here.
 *
 * The file format is modeled on TSFReborn's theme schema
 * (TsfThemeFormat: a name plus a token-to-colour map, hex parsed
 * TSF-style as #RRGGBB or #AARRGGBB) and serialized as JSON with the
 * hand-rolled codec below, in the same idiom as LayoutBackup: no
 * org.json in app code, and every validation failure is a
 * [PackException] carrying a user-presentable message. A bad file
 * can never produce a partial pack: [parse] either returns a
 * complete pack or throws.
 *
 * Persistence route (builder's call, stated per the phase spec):
 * custom packs are stored as a JSON array (each element a full pack
 * document including its id) in the "watch" prefs under
 * `custom_packs_json`, and registered into
 * [ThemePacks.customPacks] by the activity. They join every
 * registry lookup, so crossfade, schedule slots and Face slots all
 * treat them as first-class packs.
 */
class PackException(message: String) : Exception(message)

object CustomPacks {

    const val FORMAT = "orbital-pack"
    const val VERSION = 1
    const val MAX_NAME = 40
    const val ID_PREFIX = "custom_"

    /** Surface used when a pack file omits one: a deep graphite. */
    private val DEFAULT_SURFACE = 0xFF14161C.toInt()

    fun newId(): String = ID_PREFIX + System.currentTimeMillis()

    fun isCustomId(id: String?): Boolean = id?.startsWith(ID_PREFIX) == true

    // --- Hex (TSF parseColor shape: #RRGGBB or #AARRGGBB) -----------

    private const val HEXD = "0123456789ABCDEF"

    private fun hexByte(v: Int): String = "" + HEXD[(v shr 4) and 0xF] + HEXD[v and 0xF]

    /** "#RRGGBB" for opaque colours, "#AARRGGBB" otherwise. */
    fun hex(color: Int): String {
        val a = (color ushr 24) and 0xFF
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        return if (a == 0xFF) "#" + hexByte(r) + hexByte(g) + hexByte(b)
        else "#" + hexByte(a) + hexByte(r) + hexByte(g) + hexByte(b)
    }

    /** Parses a TSF-style hex colour or throws a [PackException]
     *  naming the field, so a bad token in a file is a clear error,
     *  never a crash or a silently wrong colour. */
    fun parseHex(raw: String, field: String): Int {
        val s = raw.trim()
        if ((s.length != 7 && s.length != 9) || s[0] != '#') {
            throw PackException("The $field colour '$raw' is not a hex colour (use #RRGGBB)")
        }
        val digits = s.substring(1)
        val v = digits.toLongOrNull(16)
            ?: throw PackException("The $field colour '$raw' is not valid hex")
        return if (digits.length == 6) (0xFF000000L or v).toInt() else v.toInt()
    }

    // --- The pack document ------------------------------------------

    /** The `.orbitalpack.json` document for [pack] (no id: a file
     *  import always lands under a fresh custom id). */
    fun toJson(pack: ThemePack): String = document(pack, includeId = false)

    /** The prefs persistence form: a JSON array of pack documents,
     *  each carrying its id. */
    fun serializeList(packs: List<ThemePack>): String =
        packs.joinToString(separator = ",", prefix = "[", postfix = "]") { document(it, includeId = true) }

    private fun document(pack: ThemePack, includeId: Boolean): String = buildString {
        append("{\n  \"format\": \"").append(FORMAT).append("\",\n  \"version\": ").append(VERSION).append(",\n")
        if (includeId) append("  \"id\": \"").append(esc(pack.id)).append("\",\n")
        append("  \"name\": \"").append(esc(pack.name)).append("\",\n")
        append("  \"subtitle\": \"").append(esc(pack.subtitle)).append("\",\n")
        append("  \"tokens\": {\n")
        append("    \"accent\": \"").append(hex(pack.accentDay)).append("\",\n")
        append("    \"accentNight\": \"").append(hex(pack.accentNight)).append("\",\n")
        append("    \"glow\": \"").append(hex(pack.glowDay)).append("\",\n")
        append("    \"glowNight\": \"").append(hex(pack.glowNight)).append("\",\n")
        append("    \"surface\": \"").append(hex(pack.surface)).append("\",\n")
        append("    \"surfaceAlt\": \"").append(hex(pack.surfaceAlt)).append("\",\n")
        append("    \"lattice\": \"").append(hex(pack.latticeStroke)).append("\"\n")
        append("  },\n")
        append("  \"font\": \"").append(if (pack.displayFont == DisplayFont.RAJDHANI) "rajdhani" else "system").append("\",\n")
        append("  \"background\": \"").append(pack.backgroundStyle.name.lowercase()).append("\",\n")
        append("  \"material\": \"").append(pack.materialStyle.name.lowercase()).append("\"\n}")
    }

    /**
     * Parses and validates a pack document under [id]. Guards:
     * required `name` (trimmed, capped at [MAX_NAME]), required
     * `tokens` object with a required `accent`, TSF hex parsing on
     * every token, enum whitelists for font / background / material,
     * and a format check when the document names one. Optional
     * tokens fall back in-family (night = day, glow = accent,
     * lattice blended from accent and surface), so a minimal
     * hand-written file still yields a complete pack.
     */
    fun parse(json: String, id: String): ThemePack {
        if (json.isBlank()) throw PackException("The file is empty")
        val root = try {
            JsonParser(json).parse()
        } catch (e: IllegalStateException) {
            throw PackException("That file is not a valid theme pack (${e.message})")
        }
        val obj = root as? Map<*, *> ?: throw PackException("That file is not a theme pack (no theme object)")
        val format = obj["format"] as? String
        if (format != null && format != FORMAT) throw PackException("That file is not an Orbital theme pack")
        val name = (obj["name"] as? String)?.trim().orEmpty()
        if (name.isEmpty()) throw PackException("The pack has no name")
        if (name.length > MAX_NAME) throw PackException("The pack name is too long (max $MAX_NAME characters)")
        val tokens = obj["tokens"] as? Map<*, *> ?: throw PackException("The pack is missing its colour tokens")
        fun token(key: String): Int? = (tokens[key] as? String)?.let { parseHex(it, key) }
        val accent = token("accent") ?: throw PackException("The pack is missing its accent colour")
        val accentNight = token("accentNight") ?: accent
        val glow = token("glow") ?: accent
        val glowNight = token("glowNight") ?: glow
        val surface = token("surface") ?: DEFAULT_SURFACE
        val surfaceAlt = token("surfaceAlt") ?: ThemePacks.blend(surface, 0xFF000000.toInt(), 0.35f)
        val lattice = token("lattice") ?: ThemePacks.blend(accent, surface, 0.45f)
        val font = when (val f = (obj["font"] as? String)?.trim()?.lowercase()) {
            null, "system" -> DisplayFont.SYSTEM
            "rajdhani" -> DisplayFont.RAJDHANI
            else -> throw PackException("Unknown font '$f' (use system or rajdhani)")
        }
        val background = when (val b = (obj["background"] as? String)?.trim()?.lowercase()) {
            null, "none" -> BackgroundStyle.NONE
            "wave" -> BackgroundStyle.WAVE
            "grid" -> BackgroundStyle.GRID
            else -> throw PackException("Unknown background '$b' (use none, wave or grid)")
        }
        val material = when (val m = (obj["material"] as? String)?.trim()?.lowercase()) {
            null, "matte" -> MaterialStyle.MATTE
            "glass" -> MaterialStyle.GLASS
            "neon" -> MaterialStyle.NEON
            else -> throw PackException("Unknown material '$m' (use matte, glass or neon)")
        }
        val subtitle = (obj["subtitle"] as? String)?.trim()?.take(60)?.ifEmpty { null } ?: "Custom"
        return ThemePack(
            id = id,
            name = name,
            subtitle = subtitle,
            accentDay = accent,
            accentNight = accentNight,
            glowDay = glow,
            glowNight = glowNight,
            surface = surface,
            surfaceAlt = surfaceAlt,
            latticeStroke = lattice,
            displayFont = font,
            backgroundStyle = background,
            materialStyle = material,
        )
    }

    /** The persisted customs, tolerantly: a corrupt store yields
     *  the packs that do parse (each re-validated through [parse]),
     *  never a crash. Duplicate ids keep the first. */
    fun parseList(json: String?): List<ThemePack> {
        if (json.isNullOrBlank()) return emptyList()
        val root = runCatching { JsonParser(json).parse() }.getOrNull() ?: return emptyList()
        val arr = root as? List<*> ?: return emptyList()
        val out = ArrayList<ThemePack>()
        val seen = HashSet<String>()
        for (el in arr) {
            val obj = el as? Map<*, *> ?: continue
            val id = obj["id"] as? String ?: continue
            if (!isCustomId(id) || !seen.add(id)) continue
            runCatching { parse(writeValue(obj), id) }.getOrNull()?.let { out += it }
        }
        return out
    }

    // --- The builder -------------------------------------------------

    /** One surface step of the editor: a surface/surfaceAlt ladder
     *  taken from the shipped packs (Graphite = Orbital's, Deep =
     *  Night City's, Midnight one rung darker). */
    data class SurfaceStep(val label: String, val surface: Int, val surfaceAlt: Int)

    val SURFACE_STEPS = listOf(
        SurfaceStep("Graphite", 0xFF232529.toInt(), 0xFF181A1E.toInt()),
        SurfaceStep("Deep", 0xFF121221.toInt(), 0xFF0E0E17.toInt()),
        SurfaceStep("Midnight", 0xFF0B0D12.toInt(), 0xFF06070A.toInt()),
    )

    /** The editor's swatch grid: the 12 XMB month accents, then the
     *  Night City pair (yellow, cyan), white and a neutral grey. */
    val SWATCHES: List<Int> = ThemePacks.XMB_MONTHS.map { it.accentDay } + listOf(
        0xFFFFD742.toInt(), // Night City accent
        0xFF5EF6FF.toInt(), // Night City glow
        0xFFFFFFFF.toInt(),
        0xFF8A8F98.toInt(),
    )

    /** Builds the pack the editor's Save produces: day = night (the
     *  editor works in one set of colours), the lattice derived
     *  in-family from accent and surface, stock motion, matte
     *  islands. Customs carry no per-topology overrides and no
     *  topology suggestion: those stay a built-in personality. */
    fun buildPack(
        name: String,
        accent: Int,
        glow: Int,
        surfaceStep: Int,
        background: BackgroundStyle,
        rajdhani: Boolean,
        id: String,
    ): ThemePack {
        val step = SURFACE_STEPS[surfaceStep.coerceIn(0, SURFACE_STEPS.lastIndex)]
        return ThemePack(
            id = id,
            name = name.trim().ifEmpty { "Custom Pack" }.take(MAX_NAME),
            subtitle = "Custom",
            accentDay = accent,
            accentNight = accent,
            glowDay = glow,
            glowNight = glow,
            surface = step.surface,
            surfaceAlt = step.surfaceAlt,
            latticeStroke = ThemePacks.blend(accent, step.surface, 0.45f),
            displayFont = if (rajdhani) DisplayFont.RAJDHANI else DisplayFont.SYSTEM,
            backgroundStyle = background,
            motionDialect = MotionDialect.STOCK,
            materialStyle = MaterialStyle.MATTE,
        )
    }

    // --- A small JSON codec (LayoutBackup's idiom, self-contained) --

    private fun esc(s: String): String = buildString {
        for (ch in s) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u00").append(hexByte(ch.code)) else append(ch)
            }
        }
    }

    private fun writeValue(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"" + esc(v) + "\""
        is Boolean -> v.toString()
        is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        is Map<*, *> -> v.entries.joinToString(separator = ",", prefix = "{", postfix = "}") {
            "\"" + esc(it.key.toString()) + "\":" + writeValue(it.value)
        }
        is List<*> -> v.joinToString(separator = ",", prefix = "[", postfix = "]") { writeValue(it) }
        else -> "\"" + esc(v.toString()) + "\""
    }

    /** Recursive-descent JSON reader producing Map / List / String /
     *  Double / Boolean / null, with position-tagged errors. */
    private class JsonParser(private val s: String) {
        private var i = 0

        fun parse(): Any? {
            val v = value()
            ws()
            if (i != s.length) throw IllegalStateException("trailing characters at $i")
            return v
        }

        private fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun value(): Any? {
            ws()
            if (i >= s.length) throw IllegalStateException("unexpected end of input")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { lit("true"); true }
                'f' -> { lit("false"); false }
                'n' -> { lit("null"); null }
                else -> num()
            }
        }

        private fun lit(word: String) {
            if (!s.startsWith(word, i)) throw IllegalStateException("invalid literal at $i")
            i += word.length
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++ // '{'
            ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw IllegalStateException("expected a key at $i")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw IllegalStateException("expected ':' at $i")
                i++
                out[k] = value()
                ws()
                if (i >= s.length) throw IllegalStateException("unexpected end of input")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw IllegalStateException("expected ',' or '}' at $i")
                }
            }
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++ // '['
            ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                out += value()
                ws()
                if (i >= s.length) throw IllegalStateException("unexpected end of input")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw IllegalStateException("expected ',' or ']' at $i")
                }
            }
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (i < s.length) {
                val ch = s[i++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw IllegalStateException("unterminated escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw IllegalStateException("bad unicode escape")
                                val code = s.substring(i, i + 4).toIntOrNull(16)
                                    ?: throw IllegalStateException("bad unicode escape")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw IllegalStateException("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            throw IllegalStateException("unterminated string")
        }

        private fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
            if (i == start) throw IllegalStateException("unexpected character '${s[i]}' at $i")
            return s.substring(start, i).toDoubleOrNull()
                ?: throw IllegalStateException("bad number at $start")
        }
    }
}
