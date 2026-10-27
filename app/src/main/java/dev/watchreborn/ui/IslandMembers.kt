package dev.watchreborn.ui

/**
 * Island stack membership (3.6.0, Part B) — the pure bookkeeping
 * behind island stacks. An island slot holds an ordered list of
 * member tokens:
 *
 *   "s:<appWidgetId>" — a hosted system widget
 *   "n:<KIND>"        — a built-in native widget (NativeKind name)
 *
 * The list is persisted per slot as `island_<slotId>_members`
 * (comma-joined) with the visible member's index in
 * `island_<slotId>_top`. Slots written before 3.6.0 have no
 * members key: the activity synthesises a single system member
 * from the slot id itself, which is exactly the pre-stack
 * behaviour, so existing islands load unchanged.
 */
object IslandMembers {

    const val SYS_PREFIX = "s:"
    const val NATIVE_PREFIX = "n:"

    fun sysToken(appWidgetId: Int): String = SYS_PREFIX + appWidgetId

    fun nativeToken(kind: NativeKind): String = NATIVE_PREFIX + kind.name

    fun isNative(token: String): Boolean = token.startsWith(NATIVE_PREFIX)

    /** The appWidgetId of a system token, or null for natives /
     *  malformed tokens. */
    fun sysId(token: String): Int? =
        if (token.startsWith(SYS_PREFIX)) token.substring(SYS_PREFIX.length).toIntOrNull() else null

    /** The native kind of a native token, or null for system /
     *  unknown tokens. */
    fun nativeKind(token: String): NativeKind? =
        if (isNative(token)) NativeKind.fromName(token.substring(NATIVE_PREFIX.length)) else null

    /** True when the token names a real member (a parseable system
     *  id or a known native kind). */
    fun isValid(token: String): Boolean =
        sysId(token) != null || nativeKind(token) != null

    /** Parse a persisted members value: whitespace-tolerant,
     *  drops malformed tokens, de-duplicates preserving order
     *  (the same widget twice in one stack would fight over its
     *  single hosted view and snapshot). */
    fun parse(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<String>()
        for (part in raw.split(',')) {
            val t = part.trim()
            if (t.isNotEmpty() && isValid(t) && t !in out) out += t
        }
        return out
    }

    fun serialize(members: List<String>): String = members.joinToString(",")

    /** Clamp a persisted top index into the member list. */
    fun clampTopIndex(members: List<String>, top: Int): Int =
        if (members.isEmpty()) 0 else top.coerceIn(0, members.size - 1)

    /** The top index after a cycle tap: next member, wrapping.
     *  A stack of 0/1 never moves. */
    fun nextTopIndex(members: List<String>, top: Int): Int =
        if (members.size <= 1) 0 else (clampTopIndex(members, top) + 1) % members.size

    /** appWidgetIds referenced by a member list, in order. Used
     *  by the faces orphan logic: a widget id is only released
     *  when no live island and no surviving face references it. */
    fun systemIds(members: List<String>): List<Int> = members.mapNotNull(::sysId)

    /** All system widget ids referenced by a members map value,
     *  for snapshot introspection (faces / backups). */
    fun systemIdsOfRaw(raw: String?): List<Int> = systemIds(parse(raw))
}
