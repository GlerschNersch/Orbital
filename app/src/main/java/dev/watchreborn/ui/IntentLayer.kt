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
 * - 3.8.1: [resolve] also sub-caps each secondary group (see
 *   [SECONDARY_GROUP_CAP]) and caps the total at [TOTAL_CAP]. Matt's
 *   real-phone Gym test filled the old cap of 14 with broad Music-group
 *   backfill (audio recorders, tuners, generators) once the handful of
 *   real Fitness apps ran out; the primary group stays uncapped, the
 *   helpers only get a few seats each.
 * - 3.8.3 (batch 2 item 1): the Gym intent gets a purpose filter, because
 *   sub-caps alone still seated the wrong Music apps (frecency only
 *   counts launches through Orbital, so Spotify — usually launched over
 *   Bluetooth/car — ranked below recently-tested utilities). For
 *   WORKOUT only: Music seats require a playback/streaming signal and
 *   deny audio creation/utility apps outright, and the primary Fitness
 *   group is tiered — training / gym-use apps first, general health
 *   (portals, wellness) only fills leftover seats. Candidates carrying
 *   no label (unit fixtures) are unfiltered and tier-neutral: the
 *   purpose rules only judge apps they can actually read.
 *
 * Future versions may learn context (time, place, recent activity), but
 * that is deliberately out of scope for this prototype.
 */
object IntentLayer {

    /** An app considered for an intent, identified by its stable [key].
     *  [label] and [packageName] feed the WORKOUT purpose filter; they
     *  default blank so callers without them stay unfiltered. */
    data class IntentCandidate(
        val key: String,
        val group: AppGroup,
        val label: String = "",
        val packageName: String = "",
    )

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

    /** Total result cap for [resolve] (3.8.1: was 14 in the prototype). */
    const val TOTAL_CAP = 10

    /**
     * Max apps any one secondary group (every group after the intent's
     * primary group) may contribute to a [resolve] result.
     */
    const val SECONDARY_GROUP_CAP = 3

    // --- 3.8.3 WORKOUT purpose filter -------------------------------------

    /** Tier-1 (core) signals for the Gym primary group: training and
     *  gym-use apps. Device health trackers (Samsung Health, Health
     *  Connect) count as gym-use via "health"; patient portals and
     *  wellness apps carry none of these and fall to tier 2. */
    private val GYM_CORE_KEYWORDS = listOf(
        "workout", "gym", "train", "run", "cycle", "lift", "physique",
        "fitbit", "zepp", "strava", "health", "fitness",
    )

    /** Audio creation / utility signals: denied a Gym Music seat even
     *  when a playback keyword also matches ("Music Maker"). Checked
     *  against the label first, before the playback requirement. */
    private val GYM_MUSIC_DENY_KEYWORDS = listOf(
        "record", "tuner", "voice", "generat", "assistant", "guitar",
        "studio", "maker", "editor", "suno", "voicemod",
    )

    /** Playback / streaming signals a Gym Music seat requires. */
    private val GYM_MUSIC_PLAYBACK_KEYWORDS = listOf(
        "spotify", "music", "podcast", "radio", "audible", "tidal",
        "deezer", "pandora", "soundcloud", "youtube",
    )

    private fun searchText(c: IntentCandidate): String =
        (c.label + " " + c.packageName).lowercase()

    /** 0 = training / gym-use core, 1 = general health. Label-less
     *  candidates are tier-neutral (0) so plain frecency decides. */
    private fun gymTier(c: IntentCandidate): Int {
        val text = searchText(c)
        if (text.isBlank()) return 0
        return if (GYM_CORE_KEYWORDS.any { it in text }) 0 else 1
    }

    /** Whether a Music-group app may take a Gym seat: playback only.
     *  Label-less candidates pass — there is nothing to judge. */
    private fun gymMusicAllowed(c: IntentCandidate): Boolean {
        if (c.label.isBlank()) return true
        val label = c.label.lowercase()
        if (GYM_MUSIC_DENY_KEYWORDS.any { it in label }) return false
        val text = searchText(c)
        return GYM_MUSIC_PLAYBACK_KEYWORDS.any { it in text }
    }

    /**
     * Resolves [intent] against [candidatesInFrecencyOrder].
     *
     * Keeps only candidates whose group is in the intent's groups, orders
     * them by group priority index first and then by their original order
     * in [candidatesInFrecencyOrder] (frecency), dedupes by key (first
     * occurrence wins), then applies the caps: the primary group (index
     * 0) is uncapped, each secondary group contributes at most
     * [SECONDARY_GROUP_CAP] apps, and the total is capped at [cap].
     *
     * For [WORKOUT], the 3.8.3 purpose filter runs first (Music seats
     * are playback-only) and the primary Fitness group sorts by purpose
     * tier before frecency (see the class doc).
     *
     * Returns the ordered [IntentCandidate]s, not just keys, so callers
     * keep the group information alongside each key.
     */
    fun resolve(
        intent: IntentDef,
        candidatesInFrecencyOrder: List<IntentCandidate>,
        cap: Int = TOTAL_CAP,
    ): List<IntentCandidate> {
        if (cap <= 0) return emptyList()
        val priority = HashMap<AppGroup, Int>(intent.groups.size)
        intent.groups.forEachIndexed { index, group -> priority[group] = index }
        val seen = HashSet<String>()
        val groupCounts = HashMap<AppGroup, Int>(intent.groups.size)
        val pool = if (intent == WORKOUT) {
            candidatesInFrecencyOrder.filter { candidate ->
                candidate.group != AppGroup.MUSIC || gymMusicAllowed(candidate)
            }
        } else {
            candidatesInFrecencyOrder
        }
        fun tierOf(candidate: IntentCandidate): Int =
            if (intent == WORKOUT && priority[candidate.group] == 0) gymTier(candidate) else 0
        return pool
            .withIndex()
            .filter { (_, candidate) -> candidate.group in priority }
            .sortedWith(
                compareBy<IndexedValue<IntentCandidate>> { priority.getValue(it.value.group) }
                    .thenBy { tierOf(it.value) }
                    .thenBy { it.index }
            )
            .map { it.value }
            .filter { seen.add(it.key) }
            .filter { candidate ->
                if (priority.getValue(candidate.group) == 0) {
                    true
                } else {
                    val used = groupCounts[candidate.group] ?: 0
                    if (used >= SECONDARY_GROUP_CAP) {
                        false
                    } else {
                        groupCounts[candidate.group] = used + 1
                        true
                    }
                }
            }
            .take(cap)
    }
}
