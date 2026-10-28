package dev.watchreborn.ui

/**
 * Pure key rules for pruning the apps store after uninstalls (3.7.5).
 *
 * Pins ("pin:<key>", "pin@<scope>:<key>"), usage ("n:", "s:", "t:")
 * and group overrides ("group:") are stored per app key and were never
 * removed, so every uninstalled app left its entries behind forever
 * (and pin lookups scan the whole store). Folder orders ("forder:<g>")
 * hold comma-joined keys and are filtered rather than removed.
 *
 * Only app keys (`pkg/Activity#serial`) are ever candidates — folder
 * and widget keys ("folder:", "cfolder:", "widget:") never parse.
 * Custom folder membership is deliberately left alone: display
 * already resolves it against installed apps, and pruning could
 * silently dissolve a two-app folder the user made.
 */
object StalePrefs {

    private val SIMPLE_PREFIXES = listOf("pin:", "n:", "s:", "t:", "group:")
    const val FOLDER_ORDER_PREFIX = "forder:"

    /** The app key a per-app pref key belongs to, or null when [prefKey]
     *  is not a per-app key or names a folder/widget. */
    fun appKeyOf(prefKey: String): String? {
        val rest = SIMPLE_PREFIXES.firstOrNull { prefKey.startsWith(it) }
            ?.let { prefKey.removePrefix(it) }
            ?: if (prefKey.startsWith("pin@")) {
                prefKey.substringAfter(':', "").ifEmpty { null }
            } else {
                null
            }
        return rest?.takeIf(::isAppKey)
    }

    /** `pkg/Activity#serial`: the shape AppRepo.load gives app keys. */
    fun isAppKey(key: String): Boolean {
        val slash = key.indexOf('/')
        val hash = key.lastIndexOf('#')
        return slash > 0 && hash > slash + 1 && hash < key.length - 1 &&
            key.substring(hash + 1).all(Char::isDigit)
    }

    /** Package name of an app key (call only when [isAppKey]). */
    fun packageOf(appKey: String): String = appKey.substringBefore('/')

    /** User serial of an app key (call only when [isAppKey]). */
    fun serialOf(appKey: String): Long = appKey.substringAfterLast('#').toLong()

    /**
     * The pruning plan for [prefs] (key -> value): per-app keys whose app
     * [isGone] are removed; folder orders lose their gone members and are
     * rewritten (or removed when empty). [isGone] is asked once per app key.
     */
    fun plan(prefs: Map<String, *>, isGone: (String) -> Boolean): Plan {
        val verdicts = HashMap<String, Boolean>()
        fun gone(appKey: String) = verdicts.getOrPut(appKey) { isGone(appKey) }
        val remove = LinkedHashSet<String>()
        val rewrite = LinkedHashMap<String, String>()
        for ((k, v) in prefs) {
            if (k.startsWith(FOLDER_ORDER_PREFIX) && v is String) {
                val keys = CustomFolders.decode(v)
                val kept = keys.filter { !(isAppKey(it) && gone(it)) }
                if (kept.size == keys.size) continue
                if (kept.isEmpty()) remove += k else rewrite[k] = CustomFolders.encode(kept)
                continue
            }
            val app = appKeyOf(k) ?: continue
            if (gone(app)) remove += k
        }
        return Plan(remove, rewrite)
    }

    data class Plan(val remove: Set<String>, val rewrite: Map<String, String>) {
        val isEmpty: Boolean get() = remove.isEmpty() && rewrite.isEmpty()
    }
}
