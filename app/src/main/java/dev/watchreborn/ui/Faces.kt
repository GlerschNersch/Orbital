package dev.watchreborn.ui

/**
 * Multiple faces (3.5.0) — saved cluster layouts.
 *
 * A face is a named, self-remembering layout slot: its content is a
 * [LayoutBackup] JSON snapshot of the two live preference stores
 * ("watch" + "apps"), captured with the same serializer 3.3.0's
 * backup & restore uses, so a face switch and a file restore share
 * one exact apply path. Faces live in a third SharedPreferences
 * store ("faces") that is never itself swapped: an ordered id list,
 * one name and one snapshot per face, and the active face id — kept
 * outside the swapped stores so it survives the switch it drives.
 *
 * This object is the pure half (no Android types): the key scheme,
 * the cap, name suggestions, snapshot introspection (topology,
 * island widget ids) and the widget-deallocation decision. The
 * SharedPreferences plumbing lives in MainActivity.
 */
object Faces {

    /** The store faces live in. Never part of a snapshot. */
    const val STORE = "faces"

    const val KEY_IDS = "face_ids"
    const val KEY_ACTIVE = "active_face_id"

    const val MAX_FACES = 6
    const val MAIN_NAME = "Main"

    fun keyName(id: String): String = "face_${id}_name"
    fun keyData(id: String): String = "face_${id}_data"

    data class Face(val id: String, val name: String, val data: String)

    /** Migration: the one face a fresh faces store starts with —
     *  named "Main", holding the current live layout, active. */
    fun migrationFace(id: String, liveSnapshot: String): Face =
        Face(id, MAIN_NAME, liveSnapshot)

    /** The cap is a product rule, not a storage limit: past six,
     *  the switcher stops being a switcher. */
    fun canAdd(currentCount: Int): Boolean = currentCount < MAX_FACES

    /** First free "Face N" (N ≥ 2) for the New Face dialog's
     *  prefill, so accepting the suggestion never collides. */
    fun nextFaceName(existingNames: Collection<String>): String {
        var n = 2
        while (true) {
            val candidate = "Face $n"
            if (candidate !in existingNames) return candidate
            n++
        }
    }

    /** The cluster_topology value inside a snapshot, or null when
     *  the snapshot doesn't parse or doesn't carry one. */
    fun topologyNameOf(data: String): String? = runCatching {
        LayoutBackup.parse(data).stores[LayoutBackup.STORE_WATCH]
            ?.get("cluster_topology") as? String
    }.getOrNull()

    /** The appWidgetIds a snapshot's island set references.
     *  (3.6.0: membership-aware — a snapshot's slots are named by
     *  the watch store's comma-joined island_ids entry; a slot
     *  WITH an island_<id>_members entry (3.6.0 stacks / native
     *  islands) references the SYSTEM members of that list, not
     *  the slot id itself — a native slot's id is a negative
     *  sequence number, never an appWidgetId. Pre-3.6.0 slots have
     *  no members entry and reference their own (positive) id, so
     *  old snapshots read exactly as before.) */
    fun islandIdsOf(data: String): Set<Int> = runCatching {
        val watch = LayoutBackup.parse(data).stores[LayoutBackup.STORE_WATCH]
            ?: return emptySet()
        val raw = watch["island_ids"] as? String ?: return emptySet()
        val out = HashSet<Int>()
        for (part in raw.split(',')) {
            val slotId = part.trim().toIntOrNull() ?: continue
            val members = watch["island_${slotId}_members"] as? String
            if (members != null) {
                out += IslandMembers.systemIdsOfRaw(members)
            } else if (slotId > 0) {
                out += slotId
            }
        }
        out
    }.getOrDefault(emptySet())

    /**
     * Widget deallocation decision for deleting a face: the ids the
     * removed face referenced that NO surviving face references and
     * that aren't in the live set. Shared ids stay allocated (the
     * survivor still names them); live ids stay allocated (the
     * cluster is using them right now) — deleting a face must never
     * blank an island another face, or the live layout, still shows.
     */
    fun orphanedWidgetIds(
        removed: Set<Int>,
        surviving: List<Set<Int>>,
        live: Set<Int>,
    ): Set<Int> {
        val kept = HashSet<Int>(live)
        for (ids in surviving) kept += ids
        return removed - kept
    }
}
