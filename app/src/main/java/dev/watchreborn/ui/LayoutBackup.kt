package dev.watchreborn.ui

/**
 * Layout backup & restore (3.3.0).
 *
 * The whole Orbital setup lives in two SharedPreferences stores —
 * "watch" (topology, toggles, islands, pins, accent…) and "apps"
 * (AppRepo's folders, pins and app state). A backup is one JSON
 * document holding every entry of both stores with its type kept
 * alongside the value, so a restore can rewrite the stores exactly —
 * an Int stays an Int, a Float stays a Float, a string-set stays a
 * string-set. The header names the format, a schema version, and the
 * app version that wrote the file.
 *
 * This object is pure Kotlin (no Android types) so the round-trip
 * and the validation are unit-testable off-device: MainActivity
 * hands it plain maps and writes back plain maps. The JSON codec is
 * hand-rolled for the small value grammar backups need — objects,
 * arrays, strings, numbers, booleans — and rejects anything else.
 */
object LayoutBackup {

    const val FORMAT = "orbital-layout"
    const val SCHEMA = 1
    const val STORE_WATCH = "watch"
    const val STORE_APPS = "apps"

    /** The only stores a backup may read or a restore may write. */
    val STORES = listOf(STORE_WATCH, STORE_APPS)

    /** A user-presentable reason a file is not a usable backup. */
    class BackupException(message: String) : Exception(message)

    data class Header(
        val versionName: String,
        val versionCode: Int,
        val exportedAtMillis: Long,
    )

    data class Backup(
        val header: Header,
        /** Store name → key → typed value (String/Boolean/Int/Long/Float/Set<String>). */
        val stores: Map<String, Map<String, Any>>,
    ) {
        val entryCount: Int get() = stores.values.sumOf { it.size }
    }

    // ── Serialization ───────────────────────────────────────────────

    fun serialize(
        stores: Map<String, Map<String, Any?>>,
        versionName: String,
        versionCode: Int,
        exportedAtMillis: Long,
    ): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"format\": ").append(quote(FORMAT)).append(",\n")
        sb.append("  \"schema\": ").append(SCHEMA).append(",\n")
        sb.append("  \"app\": \"dev.orbital\",\n")
        sb.append("  \"versionName\": ").append(quote(versionName)).append(",\n")
        sb.append("  \"versionCode\": ").append(versionCode).append(",\n")
        sb.append("  \"exportedAt\": ").append(exportedAtMillis).append(",\n")
        sb.append("  \"stores\": {")
        val storeNames = stores.keys.sorted()
        storeNames.forEachIndexed { si, name ->
            if (si > 0) sb.append(",")
            sb.append("\n    ").append(quote(name)).append(": {")
            val entries = stores[name].orEmpty()
            val keys = entries.keys.filter { entries[it] != null }.sorted()
            keys.forEachIndexed { ki, key ->
                if (ki > 0) sb.append(",")
                sb.append("\n      ").append(quote(key)).append(": ")
                appendEntry(sb, entries[key]!!)
            }
            if (keys.isNotEmpty()) sb.append("\n    ")
            sb.append("}")
        }
        if (storeNames.isNotEmpty()) sb.append("\n  ")
        sb.append("}\n}\n")
        return sb.toString()
    }

    private fun appendEntry(sb: StringBuilder, value: Any) {
        when (value) {
            is String -> sb.append("{\"t\": \"string\", \"v\": ").append(quote(value)).append("}")
            is Boolean -> sb.append("{\"t\": \"boolean\", \"v\": ").append(value).append("}")
            is Int -> sb.append("{\"t\": \"int\", \"v\": ").append(value).append("}")
            is Long -> sb.append("{\"t\": \"long\", \"v\": ").append(value).append("}")
            is Float -> sb.append("{\"t\": \"float\", \"v\": ").append(value.toString()).append("}")
            is Set<*> -> {
                sb.append("{\"t\": \"stringset\", \"v\": [")
                val items = value.filterIsInstance<String>().sorted()
                items.forEachIndexed { i, s ->
                    if (i > 0) sb.append(", ")
                    sb.append(quote(s))
                }
                sb.append("]}")
            }
            else -> throw IllegalArgumentException("Unsupported prefs value type: ${value.javaClass}")
        }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') {
                    sb.append("\\u")
                    sb.append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    // ── Parsing & validation ──────────────────────────────────────

    /**
     * Parse [text] as a layout backup. Throws [BackupException] with
     * a user-presentable message for anything that is not a valid
     * schema-1 Orbital backup — foreign files, corrupt JSON, wrong
     * schema, mistyped entries. Nothing is applied by this call.
     */
    fun parse(text: String): Backup {
        val root = try {
            JsonParser(text).parseDocument()
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("That file isn't a valid Orbital layout backup.")
        }
        val obj = root as? Json.Obj
            ?: throw BackupException("That file isn't a valid Orbital layout backup.")
        val format = (obj.fields["format"] as? Json.Str)?.value
        if (format != FORMAT) throw BackupException("Not an Orbital layout backup.")
        val schemaRaw = (obj.fields["schema"] as? Json.Num)?.raw
            ?: throw BackupException("That backup is missing its schema version.")
        val schema = schemaRaw.toIntOrNull()
            ?: throw BackupException("That backup has a corrupt schema version.")
        if (schema != SCHEMA) {
            throw BackupException("Unsupported backup version (schema $schema) — update Orbital to restore it.")
        }
        val header = Header(
            versionName = (obj.fields["versionName"] as? Json.Str)?.value ?: "",
            versionCode = (obj.fields["versionCode"] as? Json.Num)?.raw?.toIntOrNull() ?: 0,
            exportedAtMillis = (obj.fields["exportedAt"] as? Json.Num)?.raw?.toLongOrNull() ?: 0L,
        )
        val storesObj = obj.fields["stores"] as? Json.Obj
            ?: throw BackupException("That backup is missing its layout data.")
        val stores = LinkedHashMap<String, Map<String, Any>>()
        for ((name, storeJson) in storesObj.fields) {
            if (name !in STORES) continue // whitelist: unknown stores are never applied
            val storeObj = storeJson as? Json.Obj
                ?: throw BackupException("That backup's \"$name\" data is corrupt.")
            val entries = LinkedHashMap<String, Any>()
            for ((key, entryJson) in storeObj.fields) {
                entries[key] = decodeEntry(key, entryJson)
            }
            stores[name] = entries
        }
        if (stores.isEmpty()) throw BackupException("That backup holds no Orbital layout data.")
        return Backup(header, stores)
    }

    private fun decodeEntry(key: String, json: Json): Any {
        val obj = json as? Json.Obj
            ?: throw BackupException("That backup has a corrupt entry for \"$key\".")
        val type = (obj.fields["t"] as? Json.Str)?.value
            ?: throw BackupException("That backup has a corrupt entry for \"$key\".")
        val v = obj.fields["v"]
            ?: throw BackupException("That backup has a corrupt entry for \"$key\".")
        return when (type) {
            "string" -> (v as? Json.Str)?.value
                ?: throw BackupException("That backup's \"$key\" should be text.")
            "boolean" -> (v as? Json.Bool)?.value
                ?: throw BackupException("That backup's \"$key\" should be true or false.")
            "int" -> (v as? Json.Num)?.raw?.toIntOrNull()
                ?: throw BackupException("That backup's \"$key\" should be a whole number.")
            "long" -> (v as? Json.Num)?.raw?.toLongOrNull()
                ?: throw BackupException("That backup's \"$key\" should be a whole number.")
            "float" -> (v as? Json.Num)?.raw?.toFloatOrNull()
                ?: throw BackupException("That backup's \"$key\" should be a number.")
            "stringset" -> {
                val arr = v as? Json.Arr
                    ?: throw BackupException("That backup's \"$key\" should be a list of text.")
                arr.items.map { item ->
                    (item as? Json.Str)?.value
                        ?: throw BackupException("That backup's \"$key\" should be a list of text.")
                }.toSet()
            }
            else -> throw BackupException("That backup has an entry type Orbital doesn't know (\"$type\").")
        }
    }

    // ── Minimal JSON model + parser ───────────────────────────────

    private sealed interface Json {
        data class Obj(val fields: Map<String, Json>) : Json
        data class Arr(val items: List<Json>) : Json
        data class Str(val value: String) : Json
        data class Num(val raw: String) : Json
        data class Bool(val value: Boolean) : Json
        object Null : Json
    }

    private class JsonParser(private val text: String) {
        private var pos = 0

        fun parseDocument(): Json {
            val value = parseValue()
            skipWs()
            if (pos != text.length) fail("trailing content")
            return value
        }

        private fun fail(why: String): Nothing =
            throw BackupException("That file isn't a valid Orbital layout backup ($why).")

        private fun skipWs() {
            while (pos < text.length && text[pos] in " \t\n\r") pos++
        }

        private fun peek(): Char {
            skipWs()
            if (pos >= text.length) fail("unexpected end of file")
            return text[pos]
        }

        private fun parseValue(): Json =
            when (peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> Json.Str(parseString())
                't' -> { expect("true"); Json.Bool(true) }
                'f' -> { expect("false"); Json.Bool(false) }
                'n' -> { expect("null"); Json.Null }
                else -> parseNumber()
            }

        private fun expect(literal: String) {
            if (!text.regionMatches(pos, literal, 0, literal.length)) fail("bad literal")
            pos += literal.length
        }

        private fun parseObject(): Json.Obj {
            pos++ // '{'
            val fields = LinkedHashMap<String, Json>()
            if (peek() == '}') { pos++; return Json.Obj(fields) }
            while (true) {
                if (peek() != '"') fail("expected an object key")
                val key = parseString()
                if (peek() != ':') fail("expected ':'")
                pos++
                fields[key] = parseValue()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return Json.Obj(fields) }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun parseArray(): Json.Arr {
            pos++ // '['
            val items = mutableListOf<Json>()
            if (peek() == ']') { pos++; return Json.Arr(items) }
            while (true) {
                items += parseValue()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return Json.Arr(items) }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) fail("unterminated string")
                val ch = text[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= text.length) fail("unterminated escape")
                        when (val esc = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) fail("bad unicode escape")
                                val code = text.substring(pos, pos + 4).toIntOrNull(16)
                                    ?: fail("bad unicode escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("bad escape '\\$esc'")
                        }
                    }
                    else -> sb.append(ch)
                }
            }
        }

        private fun parseNumber(): Json.Num {
            val start = pos
            if (pos < text.length && text[pos] == '-') pos++
            var sawDigit = false
            while (pos < text.length && text[pos].isDigit()) { pos++; sawDigit = true }
            if (pos < text.length && text[pos] == '.') {
                pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            if (!sawDigit) fail("bad number")
            return Json.Num(text.substring(start, pos))
        }
    }
}
