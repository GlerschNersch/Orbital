package dev.watchreborn.ui

import dev.watchreborn.apps.AppGroup

/**
 * Prototype seed of the "intent, not apps" layer for Orbital.
 *
 * This is intentionally explicit and dumb, on purpose:
 * - Intents are surfaced as tappable chips in Constellation Search, or by
 *   typing an exact intent keyword / title / chip label as the whole search
 *   query (see [matchExact]).
 * - There is no LLM, no substring guessing, and no context guessing yet.
 *   Normal app search must never be hijacked by a partial or approximate
 *   match, so [matchExact] only fires on a whole-query exact match.
 * - [resolve] only reorders and filters apps that already exist. It groups
 *   them by the intent's category priority, keeping frecency order inside
 *   each group, so the most used relevant apps surface first.
 *
 * Future versions may learn context (time, place, recent activity), but
 * that is deliberately out of scope for this prototype.
 */
object IntentLayer {

    /** An app considered for an intent, identified by its stable [key]. */
    data class IntentCandidate(val key: String, val group: AppGroup)

    /** One intent definition. [groups] is in priority order, first is best. */
    data class IntentDef(
        val id: String,
        val chipLabel: String,
        val title: String,
        val keywords: Set<String>,
        val groups: List<AppGroup>,
    )

    val WORK = IntentDef(
        id = "WORK",
        chipLabel = "Work",
        title = "Work",
        keywords = setOf("work", "focus", "docs", "write", "meeting", "email"),
        groups = listOf(AppGroup.PRODUCTIVITY, AppGroup.AI, AppGroup.INTERNET, AppGroup.NEWS),
    )

    val WORKOUT = IntentDef(
        id = "WORKOUT",
        chipLabel = "Gym",
        title = "Workout",
        keywords = setOf("workout", "gym", "legs", "push day", "pull day", "train", "exercise", "fitness"),
        groups = listOf(AppGroup.HEALTH, AppGroup.MUSIC, AppGroup.VIDEO),
    )

    val DRIVE = IntentDef(
        id = "DRIVE",
        chipLabel = "Drive",
        title = "Drive",
        keywords = setOf("drive", "commute", "go", "trip", "navigate"),
        groups = listOf(AppGroup.TRAVEL, AppGroup.MUSIC, AppGroup.SOCIAL),
    )

    val CONNECT = IntentDef(
        id = "CONNECT",
        chipLabel = "Talk",
        title = "Connect",
        keywords = setOf("talk", "message", "call", "chat", "people"),
        groups = listOf(AppGroup.SOCIAL, AppGroup.INTERNET, AppGroup.PHOTOS),
    )

    val UNWIND = IntentDef(
        id = "UNWIND",
        chipLabel = "Relax",
        title = "Unwind",
        keywords = setOf("relax", "unwind", "watch", "listen", "read", "play"),
        groups = listOf(AppGroup.VIDEO, AppGroup.MUSIC, AppGroup.GAMES, AppGroup.NEWS, AppGroup.PHOTOS),
    )

    val HOME = IntentDef(
        id = "HOME",
        chipLabel = "Home",
        title = "Home",
        keywords = setOf("home", "lights", "money", "shop", "food"),
        groups = listOf(AppGroup.SMART_HOME, AppGroup.FINANCE, AppGroup.SHOPPING, AppGroup.FOOD, AppGroup.TOOLS),
    )

    /** All intents, in chip display order. */
    val ALL: List<IntentDef> = listOf(WORK, WORKOUT, DRIVE, CONNECT, UNWIND, HOME)

    /**
     * Returns the intent whose keyword, title, or chip label exactly equals
     * the whole normalized [query] (trimmed, lowercased), or null.
     *
     * Exact match only: "worko", "chrome", and "" all return null.
     */
    fun matchExact(query: String): IntentDef? {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return null
        return ALL.firstOrNull { intent ->
            normalized in intent.keywords ||
                normalized == intent.title.lowercase() ||
                normalized == intent.chipLabel.lowercase()
        }
    }

    /**
     * Resolves [intent] against [candidatesInFrecencyOrder].
     *
     * Keeps only candidates whose group is in the intent's groups, orders
     * them by group priority index first and then by their original order
     * in [candidatesInFrecencyOrder] (frecency), dedupes by key (first
     * occurrence wins), and caps the result at [cap].
     *
     * Returns the ordered [IntentCandidate]s, not just keys, so callers
     * keep the group information alongside each key.
     */
    fun resolve(
        intent: IntentDef,
        candidatesInFrecencyOrder: List<IntentCandidate>,
        cap: Int = 14,
    ): List<IntentCandidate> {
        if (cap <= 0) return emptyList()
        val priority = HashMap<AppGroup, Int>(intent.groups.size)
        intent.groups.forEachIndexed { index, group -> priority[group] = index }
        val seen = HashSet<String>()
        return candidatesInFrecencyOrder
            .withIndex()
            .filter { (_, candidate) -> candidate.group in priority }
            .sortedWith(
                compareBy<IndexedValue<IntentCandidate>> { priority.getValue(it.value.group) }
                    .thenBy { it.index }
            )
            .map { it.value }
            .filter { seen.add(it.key) }
            .take(cap)
    }
}
