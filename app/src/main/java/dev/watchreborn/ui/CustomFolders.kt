package dev.watchreborn.ui

/**
 * Pure storage codec + membership rules for user-created folders (1.5.0),
 * kept free of Android types so the rules are unit-testable. AppRepo owns
 * the SharedPreferences side; everything here is plain data.
 *
 * A custom folder is a named, ordered list of app keys. Rules:
 * - An app lives in at most one custom folder (extraction on move).
 * - A folder with fewer than 2 members dissolves — its last app goes loose.
 * - Stored folders resolve against the installed apps; uninstalled members
 *   drop out, and a folder that falls below 2 installed members hides.
 */
object CustomFolders {

    data class Def(val id: String, val name: String, val memberKeys: List<String>)

    /** App keys contain no commas (pkg/.Activity#serial), so comma-join is safe. */
    fun encode(keys: List<String>): String = keys.joinToString(",")

    fun decode(raw: String?): List<String> =
        raw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    fun dissolves(memberCount: Int): Boolean = memberCount < 2

    /**
     * Remove [key] from every def (an app lives in at most one folder) and
     * drop defs that dissolve as a result.
     */
    fun extract(defs: List<Def>, key: String): List<Def> =
        defs.map { d -> if (key in d.memberKeys) d.copy(memberKeys = d.memberKeys.filter { it != key }) else d }
            .filter { !dissolves(it.memberKeys.size) }

    /** Keep only installed members, preserving stored order; skip dissolved defs. */
    fun resolve(defs: List<Def>, installedKeys: Set<String>): List<Def> =
        defs.map { d -> d.copy(memberKeys = d.memberKeys.filter { it in installedKeys }) }
            .filter { !dissolves(it.memberKeys.size) }
}
