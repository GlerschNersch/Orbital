package dev.watchreborn.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
import dev.watchreborn.listedProfiles
import dev.watchreborn.ui.CustomFolders
import dev.watchreborn.ui.Frecency
import java.text.Collator
import kotlin.math.cos
import kotlin.math.sin

/** Anything that can sit on a cell of the honeycomb grid or in a list row. */
sealed interface GridItem {
    val key: String
    val label: String
    val icon: Bitmap
    val blurred: Bitmap
    val blurredScale: Float
}

/** One launchable app, its round icon, its blurred copy, and its classified category group. */
class App(
    override val key: String,
    override val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val system: Boolean,
    override val icon: Bitmap,
    override val blurred: Bitmap,
    override val blurredScale: Float,
    val defaultGroup: AppGroup,
) : GridItem

enum class WidgetKind(val key: String, val label: String, val tint: Int) {
    CORE_CLOCK("widget:core_clock", "Clock & Status", 0xFFA6E36E.toInt()),
    NOW_PLAYING("widget:now_playing", "Now Playing", 0xFFFF375F.toInt()),
    CONTROLS("widget:controls", "Control Cluster", 0xFF64D2FF.toInt()),
    VITALS("widget:vitals", "Vitals & Rings", 0xFF30D158.toInt()),
    TIMER("widget:timer", "Quick Timer", 0xFFFF9F0A.toInt()),
    CALENDAR("widget:calendar", "Calendar & Up Next", 0xFFFF453A.toInt()),
}

/**
 * A live canvas-rendered honeycomb bubble (#1 Center Core Clock Hub or #5 Live Media / Controls / Vitals).
 */
class WidgetBubble(
    val kind: WidgetKind,
    override val icon: Bitmap,
    override val blurred: Bitmap,
    override val blurredScale: Float,
) : GridItem {
    override val key: String = kind.key
    override val label: String = kind.label
}

/**
 * A honeycomb cluster folder containing [apps] in [group]. Its [icon] is a circular platter with a
 * miniature 7-icon watchOS focus cluster drawn inside.
 */
class Folder(
    val group: AppGroup,
    val apps: List<App>,
    override val icon: Bitmap,
    override val blurred: Bitmap,
    override val blurredScale: Float,
    /** Set for user-created folders (1.5.0); null for category folders. */
    val customId: String? = null,
    val customName: String? = null,
) : GridItem {
    override val key: String = if (customId != null) "cfolder:$customId" else "folder:${group.name}"
    override val label: String = customName ?: group.title
    val isCustom: Boolean get() = customId != null

    /** Header/platter tint: the category tint; a custom folder borrows its
     *  first member's category tint (its [group] is only a placeholder). */
    val tint: Int
        get() = if (isCustom) (apps.firstOrNull()?.defaultGroup?.tint ?: group.tint) else group.tint
}

/**
 * Loads apps from every listed profile (Private Space stays out), classifies them into [AppGroup]
 * folders, manages fixed spatial neighborhoods & slot pins, and tracks app usage.
 */
class AppRepo(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val users = context.getSystemService(UserManager::class.java)
    private val prefs = context.getSharedPreferences("apps", Context.MODE_PRIVATE)

    fun load(iconPx: Int): List<App> {
        val me = Process.myUserHandle()
        val rolesByPkg = detectRoles()
        val out = ArrayList<App>()
        for (user in launcherApps.listedProfiles) {
            for (info in launcherApps.getActivityList(null, user)) {
                val pkg = info.componentName.packageName
                if (pkg == context.packageName) continue
                val serial = users.getSerialNumberForUser(user)
                val key = "${info.componentName.flattenToShortString()}#$serial"
                val label = info.label?.toString()?.trim().orEmpty().ifEmpty { pkg }
                val icon = roundIcon(info, user.takeIf { it != me }, iconPx)
                val (soft, softScale) = blur(icon)
                val appInfo = info.applicationInfo
                @Suppress("DEPRECATION")
                val isGame = (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                val group = AppClassifier.classify(
                    label = label,
                    packageName = pkg,
                    category = appInfo.category,
                    isGame = isGame,
                    roles = rolesByPkg[pkg].orEmpty(),
                )
                out += App(
                    key = key,
                    label = label,
                    component = info.componentName,
                    user = user,
                    system = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    icon = icon,
                    blurred = soft,
                    blurredScale = softScale,
                    defaultGroup = group,
                )
            }
        }
        val collator = Collator.getInstance()
        return out.sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    /** Effective group for [app], respecting any user override. */
    /** Category folders the user hid from the folder header (1.7.0). */
    fun hiddenGroups(): Set<String> =
        prefs.getString("hidden_groups", null)?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    fun setGroupHidden(group: AppGroup, hidden: Boolean) {
        val cur = hiddenGroups().toMutableSet()
        if (hidden) cur += group.name else cur -= group.name
        prefs.edit().putString("hidden_groups", cur.joinToString(",")).apply()
    }

    fun clearHiddenGroups() {
        prefs.edit().remove("hidden_groups").apply()
    }

    fun groupOf(app: App): AppGroup {
        val over = prefs.getString("group:${app.key}", null) ?: return app.defaultGroup
        return runCatching { AppGroup.valueOf(over) }.getOrDefault(app.defaultGroup)
    }

    fun setGroupOverride(app: App, group: AppGroup?) {
        val e = prefs.edit()
        if (group == null || group == app.defaultGroup) e.remove("group:${app.key}")
        else e.putString("group:${app.key}", group.name)
        e.apply()
    }

    /**
     * Pin storage. A null [scope] is the global pin set ("pin:<key>"); a non-null
     * scope (a topology name, when per-layout pins are enabled) stores a separate
     * set ("pin@<scope>:<key>") so each cluster layout can have its own arrangement.
     */
    private fun pinKey(itemKey: String, scope: String?): String =
        if (scope == null) "pin:$itemKey" else "pin@$scope:$itemKey"

    private fun pinPrefix(scope: String?): String =
        if (scope == null) "pin:" else "pin@$scope:"

    /** Returns the pinned hex slot index for [itemKey], or null if unpinned. */
    fun pinnedSlotOf(itemKey: String, scope: String? = null): Int? {
        val v = prefs.getInt(pinKey(itemKey, scope), -1)
        return if (v >= 0) v else null
    }

    fun isPinned(itemKey: String, scope: String? = null): Boolean = pinnedSlotOf(itemKey, scope) != null

    /** The item key currently pinned at [slot] within [scope], or null. */
    fun keyPinnedAt(slot: Int, scope: String? = null): String? {
        val prefix = pinPrefix(scope)
        for ((k, v) in prefs.all) {
            if (k.startsWith(prefix) && v == slot) return k.removePrefix(prefix)
        }
        return null
    }

    fun setPinnedSlot(itemKey: String, slotIndex: Int?, scope: String? = null) {
        val e = prefs.edit()
        val prefix = pinPrefix(scope)
        if (slotIndex == null || slotIndex < 0) {
            e.remove(pinKey(itemKey, scope))
        } else {
            // Clear any previous occupant of this slot (within the same scope)
            prefs.all.keys.filter { it.startsWith(prefix) && prefs.getInt(it, -1) == slotIndex }
                .forEach { e.remove(it) }
            e.putInt(pinKey(itemKey, scope), slotIndex)
        }
        e.apply()
    }

    fun clearAllPins() {
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith("pin:") || it.startsWith("pin@") }.forEach { e.remove(it) }
        e.apply()
    }

    /** Snapshot of every pin in every scope (2.7.0): raw pref key -> slot,
     *  so Clear Pins can be undone exactly, scopes included. */
    fun allPins(): Map<String, Int> =
        prefs.all.filterKeys { it.startsWith("pin:") || it.startsWith("pin@") }
            .mapValues { (it.value as? Int) ?: -1 }
            .filterValues { it >= 0 }

    /** Restores a snapshot taken by [allPins], replacing all current pins. */
    fun restorePins(snapshot: Map<String, Int>) {
        clearAllPins()
        val e = prefs.edit()
        for ((k, v) in snapshot) e.putInt(k, v)
        e.apply()
    }

    /** Custom member order for a folder (app keys), set by drag-arranging in Folder view. */
    fun folderOrder(group: AppGroup): List<String> =
        prefs.getString("forder:${group.name}", null)?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()

    fun setFolderOrder(group: AppGroup, keys: List<String>) {
        prefs.edit().putString("forder:${group.name}", keys.joinToString(",")).apply()
    }

    // ---- Custom folders (1.5.0): user-created, named, free-form. ----
    // Storage: "cf:ids" lists the folder ids; each folder keeps
    // "cf:<id>:name" and "cf:<id>:members" (comma-joined app keys, ordered).
    // Membership rules (one folder per app, <2 members dissolves) live in
    // the pure CustomFolders helper.

    fun customFolderDefs(): List<CustomFolders.Def> {
        val ids = CustomFolders.decode(prefs.getString("cf:ids", null))
        return ids.mapNotNull { id ->
            val name = prefs.getString("cf:$id:name", null) ?: return@mapNotNull null
            CustomFolders.Def(id, name, CustomFolders.decode(prefs.getString("cf:$id:members", null)))
        }
    }

    private fun saveCustomFolderDefs(defs: List<CustomFolders.Def>) {
        val e = prefs.edit()
        for (old in CustomFolders.decode(prefs.getString("cf:ids", null))) {
            e.remove("cf:$old:name")
            e.remove("cf:$old:members")
        }
        e.putString("cf:ids", CustomFolders.encode(defs.map { it.id }))
        for (d in defs) {
            e.putString("cf:${d.id}:name", d.name)
            e.putString("cf:${d.id}:members", CustomFolders.encode(d.memberKeys))
        }
        e.apply()
    }

    fun customFolderOf(appKey: String): CustomFolders.Def? =
        customFolderDefs().firstOrNull { appKey in it.memberKeys }

    /** Creates a folder from [memberKeys]; each key is first extracted from
     *  any folder it already lives in. Returns the new folder's id. */
    fun createCustomFolder(name: String, memberKeys: List<String>): String {
        var defs = customFolderDefs()
        for (k in memberKeys) defs = CustomFolders.extract(defs, k)
        val id = "f" + java.util.UUID.randomUUID().toString().replace("-", "").take(10)
        defs = defs + CustomFolders.Def(id, name, memberKeys.distinct())
        saveCustomFolderDefs(defs)
        return id
    }

    /** Moves [appKey] into folder [id], extracting it from its old folder. */
    fun addToCustomFolder(id: String, appKey: String) {
        var defs = CustomFolders.extract(customFolderDefs(), appKey)
        defs = defs.map { if (it.id == id) it.copy(memberKeys = it.memberKeys + appKey) else it }
        saveCustomFolderDefs(defs)
    }

    /** Replaces a folder's member list (reorder); dissolves it below 2. */
    fun setCustomFolderMembers(id: String, keys: List<String>) {
        val defs = customFolderDefs()
            .map { if (it.id == id) it.copy(memberKeys = keys.distinct()) else it }
            .filter { !CustomFolders.dissolves(it.memberKeys.size) }
        saveCustomFolderDefs(defs)
    }

    fun removeFromCustomFolder(id: String, appKey: String) {
        val def = customFolderDefs().firstOrNull { it.id == id } ?: return
        setCustomFolderMembers(id, def.memberKeys - appKey)
    }

    fun renameCustomFolder(id: String, name: String) {
        saveCustomFolderDefs(customFolderDefs().map { if (it.id == id) it.copy(name = name) else it })
    }

    fun deleteCustomFolder(id: String) {
        saveCustomFolderDefs(customFolderDefs().filter { it.id != id })
    }

    /** Resolves stored custom folders against the installed apps and builds
     *  their grid items; also returns the set of app keys they claim. */
    private fun buildCustomFolders(allApps: List<App>, iconPx: Int): Pair<List<Folder>, Set<String>> {
        val byKey = allApps.associateBy { it.key }
        val resolved = CustomFolders.resolve(customFolderDefs(), byKey.keys)
        val folders = ArrayList<Folder>()
        val claimed = HashSet<String>()
        for (def in resolved) {
            val members = def.memberKeys.mapNotNull { byKey[it] }
            if (members.size < 2) continue
            claimed += members.map { it.key }
            // The platter tint follows the first member's category colour.
            val bmp = renderFolderIcon(members.first().defaultGroup, members, iconPx)
            val (soft, softScale) = blur(bmp)
            folders += Folder(AppGroup.OTHER, members, bmp, soft, softScale, customId = def.id, customName = def.name)
        }
        return folders to claimed
    }

    /** Members with a saved custom order come first (in that order); the rest keep their incoming order. */
    fun applyFolderOrder(group: AppGroup, members: List<App>): List<App> {
        val order = folderOrder(group)
        if (order.isEmpty()) return members
        val rank = order.withIndex().associate { it.value to it.index }
        return members.sortedBy { rank[it.key] ?: Int.MAX_VALUE }
    }

    /**
     * Builds the Honeycomb items when Auto Folders is enabled:
     * - When [fixedNeighborhoods] is true, the 7 core slots stay anchored to essentials/pinned items
     *   in a fixed order, and category [Folder] bubbles are arranged in fixed [AppGroup.compassOrder]
     *   around the honeycomb so spatial muscle memory is preserved.
     * - When [fixedNeighborhoods] is false, items and folders sort dynamically by recent usage.
     * - Any item with a saved pin (`pin:<key>`) is locked to its exact hex index.
     */
    fun buildFolderGrid(
        alphabetical: List<App>,
        byUse: List<App>,
        iconPx: Int,
        fixedNeighborhoods: Boolean,
        coreClockHub: Boolean = false,
        liveWidgets: Boolean = false,
        pinScope: String? = null,
    ): List<GridItem> {
        val widgets = if (coreClockHub || liveWidgets) buildWidgetBubbles(iconPx) else emptyMap()
        val coreTake = if (coreClockHub) 6 else 7
        if (byUse.size <= coreTake) {
            val small = buildList {
                if (coreClockHub) widgets[WidgetKind.CORE_CLOCK]?.let(::add)
                addAll(byUse)
                if (liveWidgets) {
                    widgets[WidgetKind.NOW_PLAYING]?.let(::add)
                    widgets[WidgetKind.CONTROLS]?.let(::add)
                    widgets[WidgetKind.VITALS]?.let(::add)
                    widgets[WidgetKind.TIMER]?.let(::add)
                    widgets[WidgetKind.CALENDAR]?.let(::add)
                }
            }
            return applyPins(small, pinScope)
        }
        val essentials = essentialPackages()
        fun essentialRank(a: App) = essentials.indexOf(a.component.packageName).let { if (it < 0) Int.MAX_VALUE else it }

        // Custom folders (1.5.0) claim their members before anything else:
        // a member lives in its folder, not in the core, a category or loose.
        val (customFolders, claimedKeys) = buildCustomFolders(alphabetical, iconPx)
        val alphaFree = if (claimedKeys.isEmpty()) alphabetical else alphabetical.filter { it.key !in claimedKeys }
        val byUseFree = if (claimedKeys.isEmpty()) byUse else byUse.filter { it.key !in claimedKeys }
        val primary = if (fixedNeighborhoods) {
            // Fixed core: essentials first in fixed role order, then alphabetical fallback
            alphaFree.sortedWith(compareBy<App> { essentialRank(it) }.thenBy { it.label }).take(coreTake)
        } else {
            byUseFree.take(coreTake)
        }
        val primaryKeys = primary.mapTo(HashSet()) { it.key }
        val sourceApps = if (fixedNeighborhoods) alphaFree else byUseFree
        val byGroup = sourceApps.groupBy(::groupOf)
        val folders = ArrayList<Folder>()
        val loose = ArrayList<App>()
        val now = System.currentTimeMillis()

        val hidden = hiddenGroups()
        for (g in AppGroup.entries) {
            val members = byGroup[g].orEmpty()
            if (g == AppGroup.OTHER || members.size < 2 || g.name in hidden) {
                for (m in members) if (m.key !in primaryKeys) loose += m
            } else {
                // Sort members inside each folder: a saved drag-arranged order wins;
                // otherwise essentials/used first, or stable when fixed.
                val sortedMembers = if (fixedNeighborhoods) {
                    members.sortedWith(compareByDescending<App> { isPinned(it.key, pinScope) }.thenBy { essentialRank(it) }.thenBy { it.label })
                } else {
                    members
                }
                val orderedMembers = applyFolderOrder(g, sortedMembers)
                val bmp = renderFolderIcon(g, orderedMembers, iconPx)
                val (soft, softScale) = blur(bmp)
                folders += Folder(g, orderedMembers, bmp, soft, softScale)
            }
        }

        if (fixedNeighborhoods) {
            folders.sortBy { it.group.compassOrder }
            // Custom folders follow the categories, alphabetical by name.
            folders += customFolders.sortedBy { it.label.lowercase() }
        } else {
            folders += customFolders
            folders.sortByDescending { f -> f.apps.sumOf { score(it.key, now).toDouble() } }
        }

        val base = buildList {
            if (coreClockHub) widgets[WidgetKind.CORE_CLOCK]?.let(::add)
            addAll(primary)
            if (liveWidgets) {
                widgets[WidgetKind.NOW_PLAYING]?.let(::add)
                widgets[WidgetKind.CONTROLS]?.let(::add)
                widgets[WidgetKind.VITALS]?.let(::add)
                widgets[WidgetKind.TIMER]?.let(::add)
                widgets[WidgetKind.CALENDAR]?.let(::add)
            }
            addAll(folders)
            addAll(loose)
        }
        return applyPins(base, pinScope)
    }

    fun buildFlatGridWithWidgets(
        byUse: List<App>,
        iconPx: Int,
        coreClockHub: Boolean,
        liveWidgets: Boolean,
        pinScope: String? = null,
    ): List<GridItem> {
        // Custom folders (1.5.0) show in the flat grid too: members are
        // claimed out of the loose list and each folder takes the position
        // of its earliest member in usage order.
        val (customFolders, claimedKeys) = buildCustomFolders(byUse, iconPx)
        val free = if (claimedKeys.isEmpty()) byUse else byUse.filter { it.key !in claimedKeys }
        val skeleton: List<GridItem> = if (!coreClockHub && !liveWidgets) {
            free
        } else {
            val widgets = buildWidgetBubbles(iconPx)
            val coreTake = if (coreClockHub) 6 else 7
            buildList {
                if (coreClockHub) widgets[WidgetKind.CORE_CLOCK]?.let(::add)
                addAll(free.take(coreTake))
                if (liveWidgets) {
                    widgets[WidgetKind.NOW_PLAYING]?.let(::add)
                    widgets[WidgetKind.CONTROLS]?.let(::add)
                    widgets[WidgetKind.VITALS]?.let(::add)
                    widgets[WidgetKind.TIMER]?.let(::add)
                    widgets[WidgetKind.CALENDAR]?.let(::add)
                }
                addAll(free.drop(coreTake))
            }
        }
        if (customFolders.isEmpty()) return applyPins(skeleton, pinScope)
        val rank = HashMap<String, Int>()
        byUse.forEachIndexed { i, a -> rank[a.key] = i }
        val pending = customFolders
            .map { f -> f to (f.apps.minOfOrNull { rank[it.key] ?: Int.MAX_VALUE } ?: Int.MAX_VALUE) }
            .sortedBy { it.second }
            .toMutableList()
        val out = ArrayList<GridItem>(skeleton.size + pending.size)
        for (item in skeleton) {
            val r = if (item is App) rank[item.key] ?: Int.MAX_VALUE else -1
            while (pending.isNotEmpty() && pending.first().second <= r) out += pending.removeAt(0).first
            out += item
        }
        for (rest in pending) out += rest.first
        return applyPins(out, pinScope)
    }

    private fun buildWidgetBubbles(px: Int): Map<WidgetKind, WidgetBubble> {
        val out = HashMap<WidgetKind, WidgetBubble>()
        for (kind in WidgetKind.entries) {
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val cx = px / 2f
            val cy = px / 2f
            p.style = Paint.Style.FILL
            p.color = Color.rgb(
                (24 + Color.red(kind.tint) * 0.16f).toInt(),
                (26 + Color.green(kind.tint) * 0.16f).toInt(),
                (30 + Color.blue(kind.tint) * 0.16f).toInt(),
            )
            c.drawCircle(cx, cy, px * 0.48f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = px * 0.04f
            p.color = kind.tint
            c.drawCircle(cx, cy, px * 0.44f, p)
            val (soft, softScale) = blur(bmp)
            out[kind] = WidgetBubble(kind, bmp, soft, softScale)
        }
        return out
    }

    /** Locks any pinned [GridItem] into its saved slot index while filling remaining slots in order. */
    fun <T : GridItem> applyPins(items: List<T>, scope: String? = null): List<T> {
        if (items.isEmpty()) return items
        val n = items.size
        val pinnedBySlot = HashMap<Int, T>()
        val unpinned = ArrayList<T>(n)
        for (item in items) {
            val slot = pinnedSlotOf(item.key, scope)?.coerceIn(0, n - 1)
            if (slot != null && slot !in pinnedBySlot) {
                pinnedBySlot[slot] = item
            } else {
                unpinned += item
            }
        }
        if (pinnedBySlot.isEmpty()) return items
        val out = ArrayList<T>(n)
        val iter = unpinned.iterator()
        for (i in 0 until n) {
            val p = pinnedBySlot[i]
            if (p != null) out += p
            else if (iter.hasNext()) out += iter.next()
        }
        while (iter.hasNext()) out += iter.next()
        return out
    }

    /** Renders a circular watchOS platter containing a miniature 7-icon cluster + tinted ring. */
    private fun renderFolderIcon(group: AppGroup, members: List<App>, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val cx = px / 2f
        val cy = px / 2f

        // Dark tinted platter
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(
            (28 + Color.red(group.tint) * 0.12f).toInt(),
            (30 + Color.green(group.tint) * 0.12f).toInt(),
            (34 + Color.blue(group.tint) * 0.12f).toInt(),
        )
        c.drawCircle(cx, cy, px * 0.48f, paint)

        // Outer category accent ring
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = px * 0.032f
        paint.color = Color.argb(185, Color.red(group.tint), Color.green(group.tint), Color.blue(group.tint))
        c.drawCircle(cx, cy, px * 0.465f, paint)

        // Subtle center hub halo to distinguish the center open target from the 6 outer Quick-Snipe icons
        paint.strokeWidth = px * 0.014f
        paint.color = Color.argb(110, Color.red(group.tint), Color.green(group.tint), Color.blue(group.tint))
        c.drawCircle(cx, cy, px * 0.152f, paint)

        // Mini 7-icon cluster inside
        val rect = RectF()
        val centerR = px * 0.135f
        val ringR = px * 0.115f
        val orbit = px * 0.265f
        members.take(7).forEachIndexed { i, app ->
            val (ix, iy, ir) = if (i == 0) {
                Triple(cx, cy, centerR)
            } else {
                val ang = Math.toRadians((i - 1) * 60.0 - 90.0)
                Triple(
                    cx + (cos(ang) * orbit).toFloat(),
                    cy + (sin(ang) * orbit).toFloat(),
                    ringR,
                )
            }
            rect.set(ix - ir, iy - ir, ix + ir, iy + ir)
            c.drawBitmap(app.icon, null, rect, paint)
        }
        return bmp
    }

    fun byUse(apps: List<App>): List<App> {
        val now = System.currentTimeMillis()
        val essentials = essentialPackages()
        fun essentialRank(a: App) = essentials.indexOf(a.component.packageName).let { if (it < 0) Int.MAX_VALUE else it }
        return apps.withIndex().sortedWith(
            compareByDescending<IndexedValue<App>> { score(it.value.key, now) }
                .thenBy { essentialRank(it.value) }
                .thenBy { it.index },
        ).map { it.value }
    }

    private fun detectRoles(): Map<String, Set<AppRole>> {
        val pm = context.packageManager
        val pairs = listOf(
            AppRole.DIALER to Intent(Intent.ACTION_DIAL),
            AppRole.SMS to Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            AppRole.CAMERA to Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            AppRole.BROWSER to Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")),
            AppRole.EMAIL to Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")),
        )
        val out = HashMap<String, MutableSet<AppRole>>()
        for ((role, intent) in pairs) {
            val pkg = runCatching { pm.resolveActivity(intent, 0)?.activityInfo?.packageName }.getOrNull()
                ?.takeIf { it != "android" } ?: continue
            out.getOrPut(pkg) { HashSet() }.add(role)
        }
        return out
    }

    private fun essentialPackages(): List<String> {
        val pm = context.packageManager
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")),
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")),
        )
        return intents.mapNotNull { intent ->
            runCatching { pm.resolveActivity(intent, 0)?.activityInfo?.packageName }.getOrNull()
                ?.takeIf { it != "android" }
        }.distinct()
    }

    fun markOpened(key: String) {
        val now = System.currentTimeMillis()
        val count = prefs.getInt("n:$key", 0)
        // recordLaunch owns the aging: it decays the stored score (or seeds
        // from the legacy count when no s: key exists yet) and adds 1 for
        // this launch, so score() only ever has to age the result forward.
        val last = prefs.getLong("t:$key", 0L)
        val stored = if (prefs.contains("s:$key")) prefs.getFloat("s:$key", 0f) else null
        val next = Frecency.recordLaunch(stored, count, last, now)
        prefs.edit().putInt("n:$key", count + 1).putLong("t:$key", now).putFloat("s:$key", next).apply()
    }

    /** Frecency launch score for [key] right now (0 for never opened); see Frecency. */
    fun usageScore(key: String): Float = score(key, System.currentTimeMillis())

    private fun score(key: String, now: Long): Float {
        val last = prefs.getLong("t:$key", 0L)
        if (prefs.contains("s:$key")) return Frecency.decay(prefs.getFloat("s:$key", 0f), last, now)
        // Pre-1.18.0 data holds only an all-time count: seed the frecency
        // score from it (computed on the fly until the next launch stores s:).
        return Frecency.seedFromLegacy(prefs.getInt("n:$key", 0), last, now)
    }

    private fun roundIcon(info: LauncherActivityInfo, otherUser: UserHandle?, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.clipPath(Path().apply { addCircle(px / 2f, px / 2f, px / 2f, Path.Direction.CW) })
        val d = info.getIcon(0)
        if (d is AdaptiveIconDrawable) {
            val bleed = px / 4
            for (layer in listOfNotNull(d.background, d.foreground)) {
                layer.setBounds(-bleed, -bleed, px + bleed, px + bleed)
                layer.draw(c)
            }
        } else {
            c.drawColor(Color.rgb(44, 44, 46))
            val inset = (px * 0.14f).toInt()
            d.setBounds(inset, inset, px - inset, px - inset)
            d.draw(c)
        }
        if (otherUser == null) return bmp
        val badged = context.packageManager.getUserBadgedIcon(BitmapDrawable(context.resources, bmp), otherUser)
        return toBitmap(badged, px).also { if (it !== bmp) bmp.recycle() }
    }

    private fun toBitmap(d: Drawable, px: Int): Bitmap {
        if (d is BitmapDrawable && d.bitmap.width == px) return d.bitmap
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, px, px)
        d.draw(Canvas(out))
        return out
    }

    private fun blur(src: Bitmap): Pair<Bitmap, Float> {
        val sw = (src.width / 6).coerceAtLeast(8)
        val sh = (src.height / 6).coerceAtLeast(8)
        val pad = 4
        val small = Bitmap.createBitmap(sw + 2 * pad, sh + 2 * pad, Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(src, null, android.graphics.Rect(pad, pad, pad + sw, pad + sh), Paint(Paint.FILTER_BITMAP_FLAG))
        val w = small.width
        val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) { boxBlur(px, w, h, 2) }
        small.setPixels(px, 0, w, 0, 0, w, h)
        return small to (sw + 2f * pad) / sw
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        val n = 2 * r + 1
        for (pass in 0 until 2) {
            val (src, dst) = if (pass == 0) px to tmp else tmp to px
            val (len, lines) = if (pass == 0) w to h else h to w
            for (line in 0 until lines) {
                for (i in 0 until len) {
                    var a = 0; var rr = 0; var g = 0; var b = 0
                    for (k in -r..r) {
                        val j = (i + k).coerceIn(0, len - 1)
                        val c = if (pass == 0) src[line * w + j] else src[j * w + line]
                        a += c ushr 24; rr += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255
                    }
                    val v = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
                    if (pass == 0) dst[line * w + i] = v else dst[i * w + line] = v
                }
            }
        }
    }
}
