package dev.watchreborn

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.AlertDialog
import android.appwidget.AppWidgetHostView
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.RemoteViews
import android.app.Dialog
import android.app.role.RoleManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.Manifest
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.UserHandle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import dev.watchreborn.apps.App
import dev.watchreborn.apps.AppGroup
import dev.watchreborn.apps.AppRepo
import dev.watchreborn.apps.StartupCache
import dev.watchreborn.apps.StartupCacheLogic
import dev.watchreborn.apps.Folder
import dev.watchreborn.apps.GridItem
import dev.watchreborn.apps.WidgetBubble
import dev.watchreborn.apps.WidgetKind
import dev.watchreborn.ui.CardAction
import dev.watchreborn.ui.ClusterOption
import dev.watchreborn.ui.ClusterTopology
import dev.watchreborn.ui.IslandLayout
import dev.watchreborn.ui.IslandMembers
import dev.watchreborn.ui.NativeIslands
import dev.watchreborn.ui.NativeKind
import dev.watchreborn.ui.NativePodView
import dev.watchreborn.ui.Faces
import dev.watchreborn.ui.LayoutBackup
import dev.watchreborn.ui.FaceInfo
import dev.watchreborn.ui.Metrics
import dev.watchreborn.ui.StepsToday
import dev.watchreborn.ui.ThemePacks
import dev.watchreborn.ui.WatchView
import java.time.LocalDate
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity(), SensorEventListener {
    private lateinit var view: WatchView
    private lateinit var repo: AppRepo
    private lateinit var startupCache: StartupCache
    // -- 3.7.2 cold-start fast paths --------------------------------
    // Process-start clock for the OrbitalStart markers (0 when the
    // platform can't report it; the markers then read large but
    // stay ordered).
    private val procStartUptime: Long =
        runCatching { Process.getStartUptimeMillis() }.getOrDefault(0L)
    // Fingerprint of the package set the painted field reflects
    // (from the cache file, or from the probe that accompanied the
    // last fresh load). Null until one of those lands.
    // Volatile: written on the loader thread, read on main (and the
    // reverse for [apps]) — see reload().
    @Volatile private var cacheFingerprint: String? = null
    // Bumped whenever a material input changes outside a push
    // (today: a launch, which changes frecency). onResume compares
    // it against [pushedVersion] to decide the fast path.
    private var stateVersion = 0
    private var pushedVersion = -1
    private var pushedAppsRef: List<App>? = null
    private var pushInFlight = false
    private var pushSeq = 0
    // The most recent grids, and the list they were built from —
    // lets a deferred grid swap reuse them when the same list is
    // re-pushed (e.g. an option toggle) instead of blanking.
    private var lastFolderGrid: List<GridItem>? = null
    private var lastFlatGrid: List<GridItem>? = null
    private var lastGridsList: List<App>? = null
    private var cacheFieldLogged = false
    private var freshFieldLogged = false
    private var frameLogged = false
    private var islandHostsKicked = false
    // Set by reload's first task when a cache paint was posted;
    // read by its second task on the same loader thread.
    @Volatile private var cachePaintPosted = false

    private fun startElapsed(): Long = android.os.SystemClock.uptimeMillis() - procStartUptime
    private lateinit var nowPlaying: NowPlaying
    private val loader = Executors.newSingleThreadExecutor()
    private val launcherApps by lazy { getSystemService(LauncherApps::class.java) }
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private val appWidgetManager by lazy { AppWidgetManager.getInstance(this) }
    private val appWidgetHost: AppWidgetHost
        get() = sharedWidgetHost(this)

    // ── 3.2.0 Anchor Islands ────────────────────────────────────────────
    private var islandContainer: FrameLayout? = null
    private val islandViews = mutableMapOf<Int, AppWidgetHostView>()
    private val islandSlots = mutableMapOf<Int, WatchView.IslandSlot>()
    private var islandPod: FrameLayout? = null
    private var islandPodId: Int? = null
    /** The pod's hosted widget id when the top member is a system
     *  widget; null for a native pod (nothing to re-home). */
    private var islandPodWidgetId: Int? = null
    private var addTargetIsland = false
    /** 3.6.0 Part B: while non-null, the picker appends to this
     *  island slot's stack instead of creating an island. Nullable
     *  because native slot ids are negative — a -1 sentinel would
     *  collide with the first native island. */
    private var stackTargetSlot: Int? = null
    /** 3.6.0: appWidgetId -> owning island slot id (a stack's
     *  members are not slot ids). */
    private val islandSlotByWidget = mutableMapOf<Int, Int>()
    /** The picker's Built-in section container (3.6.0 Part A). */
    private var widgetsPickerBuiltinCol: LinearLayout? = null
    private val islandSnapHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val islandSnapPending = mutableSetOf<Int>()
    private var rootFrame: FrameLayout? = null
    private val prefs by lazy { getSharedPreferences("watch", Context.MODE_PRIVATE) }
    /** 3.5.0: the faces store — never part of a snapshot, so the
     *  active face id survives the switch it drives. */
    private val facesPrefs by lazy { getSharedPreferences(Faces.STORE, Context.MODE_PRIVATE) }
    @Volatile private var apps: List<App> = emptyList()
    private var battery = -1
    private var charging = false

    private val iconPx: Int
        get() {
            val unitPx = minOf(resources.displayMetrics.widthPixels.toFloat(), Metrics.WATCH_WIDTH_DP * resources.displayMetrics.density)
            return (unitPx * Metrics.FOCUS_CENTER_D).toInt().coerceIn(96, 288)
        }

    private val appsChanged = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = reload()
        override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (level >= 0) battery = level * 100 / scale
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            refreshInfo()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(START_TAG, "process create: onCreate at +" + startElapsed() + "ms")
        repo = AppRepo(this)
        repo.themedIcons = prefs.getBoolean(KEY_THEMED_ICONS, false)
        startupCache = StartupCache(this) { repo.roleStamp() + ";" + repo.iconStyleStamp() }
        ensureFacesMigrated()
        view = WatchView(
            context = this,
            onLaunch = ::launch,
            onLongPressItem = ::itemOptions,
            onCardAction = ::handleCardAction,
            onClusterOptionChanged = ::handleClusterOptionChanged,
            onTopologySelected = ::handleTopologySelected,
            onPlaceItem = ::placeItem,
            onSwapItems = ::swapItems,
            onFileIntoFolder = ::fileIntoFolder,
            onCreateFolder = ::createFolder,
            onRenameFolder = ::renameFolder,
            onDeleteFolder = ::deleteFolder,
            onRemoveFromFolder = ::removeFromFolder,
            onHideFolder = ::hideFolder,
            onUnpinItem = ::unpinItem,
            onReorderFolderMember = ::reorderFolderMember,
            onQuickInfo = ::quickAppInfo,
            onQuickUninstall = ::quickUninstall,
            onTopoViewSaved = ::handleTopoViewSaved,
        )
        refreshAccent()
        view.autoFolders = prefs.getBoolean(KEY_FOLDERS, false) // 3.0.3: fresh installs start all-loose (Matt); a stored pref still wins
        view.bloomFolders = prefs.getBoolean(KEY_BLOOM, true)
        view.fixedNeighborhoods = prefs.getBoolean(KEY_FIXED_NEIGHBORHOODS, true)
        view.perLayoutPins = prefs.getBoolean(KEY_PER_LAYOUT_PINS, false)
        view.usageGrowth = prefs.getBoolean(KEY_USAGE_GROWTH, true)
        view.livingField = prefs.getBoolean(KEY_LIVING_FIELD, true) // 3.8.1
        view.themedIcons = repo.themedIcons
        val savedTopo = prefs.getString(KEY_CLUSTER_TOPOLOGY, ClusterTopology.HONEYCOMB.name)
        view.clusterTopology = runCatching { ClusterTopology.valueOf(savedTopo ?: ClusterTopology.HONEYCOMB.name) }
            .getOrDefault(ClusterTopology.HONEYCOMB)
        // Per-design pan/zoom memory, persisted (1.11.1): reseed the view.
        ClusterTopology.entries.forEach { t ->
            val kx = "topoview:${t.name}:x"
            if (prefs.contains(kx)) {
                view.seedTopoViewMemory(
                    t,
                    prefs.getFloat(kx, 0f),
                    prefs.getFloat("topoview:${t.name}:y", 0f),
                    prefs.getFloat("topoview:${t.name}:z", 1f),
                )
            }
        }
        view.hintsSeen = prefs.getStringSet(KEY_HINTS_SEEN, emptySet()).orEmpty()
        view.onHintSeen = { id ->
            val cur = prefs.getStringSet(KEY_HINTS_SEEN, emptySet()).orEmpty().toMutableSet()
            cur += id
            prefs.edit().putStringSet(KEY_HINTS_SEEN, cur).apply()
            view.hintsSeen = cur
        }
        view.onThemePackSelected = { id -> // 3.8.2 persist; 3.8.3 (batch 2 item 5): hosted island snapshots repaint under the new pack at once instead of lagging to the next provider push
            prefs.edit().putString(KEY_THEME_PACK, id).apply()
            for (wid in islandViews.keys) scheduleIslandSnapshot(wid)
        }
        view.onFaceSelected = ::switchFace
        view.onFaceMenu = ::showFaceMenu
        pushFacesToView()
        view.onWidgetsPage = { showWidgetsPage() }
        view.onWidgetsPageClosed = { hideWidgetsPage() }
        view.onIslandTap = { id -> openIslandPod(id) }
        view.onIslandMoved = { id, ax, ay ->
            prefs.edit().putFloat(islandKey(id, "ax"), ax).putFloat(islandKey(id, "ay"), ay).apply()
        }
        view.onIslandEditTap = { id -> showIslandMenu(id) }
        view.onIslandResized = { id, scale -> applyIslandScale(id, scale) }
        view.onIslandCycle = { id -> cycleIslandStack(id) }
        IslandAwareHostView.widgetUpdateHook = { id ->
            if (id in islandViews) scheduleIslandSnapshot(id)
        }
        view.applyInitialHomeMode()
        view.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.topInset = bars.top.toFloat()
            view.bottomInset = bars.bottom.toFloat()
            v.invalidate()
            insets
        }
        // The Widgets page (1.19.0) is a native overlay above the view,
        // so the activity content is a frame holding both.
        val rootFrame = FrameLayout(this)
        rootFrame.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        this.rootFrame = rootFrame
        // 3.2.0: island host views live in a 1x1 invisible bin on the
        // root frame — attached (so providers push updates and layout
        // runs) but never seen; the cluster draws their snapshots.
        val islandBin = FrameLayout(this)
        islandBin.visibility = View.INVISIBLE
        rootFrame.addView(islandBin, FrameLayout.LayoutParams(1, 1))
        islandContainer = islandBin
        setContentView(rootFrame)
        window.setDecorFitsSystemWindows(false)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            handleBack()
        }

        val artPx = (96 * resources.displayMetrics.density).toInt()
        nowPlaying = NowPlaying(this, artPx) {
            view.nowPlayingTrack = nowPlaying.track
        }
        nowPlaying.start()

        registerStepSensor()

        launcherApps.registerCallback(appsChanged)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // 3.7.2: the island SLOTS reach the view here (placement is
        // prefs math), but the hosted widget views are created a
        // beat later, past the first frame — AppWidgetHost
        // .createView measured ~0.5s of the 3.7.1 cold path. Their
        // snapshots land when creation does; a tap inside that
        // first beat is a safe no-op (openIslandPod guards on the
        // missing view).
        pushIslandsToView()
        reload()
        // Fallback only: the first field push kicks host creation
        // promptly (see pushAppsToView); this covers a load that
        // never lands a push. Idempotent either way.
        view.postDelayed({ createIslandHostViews() }, 1500)
    }

    override fun onStart() {
        super.onStart()
        runCatching { appWidgetHost.startListening() }
    }

    override fun onStop() {
        runCatching { appWidgetHost.stopListening() }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        view.refreshMotionScale() // 2.7.0 (audit #1)
        view.onHomeReturn() // 3.8.1 Homecoming Bloom: decorative only, never gates the first paint
        refreshInfo()
        // Midnight may have passed with no step event to reset the count.
        view.stepCount = StepsToday.stepsOn(stepsState, LocalDate.now().toEpochDay()).toInt()
        if (apps.isNotEmpty()) {
            // 3.7.2 warm-resume fast path: pushAppsToView rebuilds
            // both grids from scratch, and a plain return home has
            // nothing new to show. Skip when the pushed field
            // already reflects the current apps and state; anything
            // material (a load landed, a launch changed frecency, a
            // push is mid-flight) falls through to the rebuild.
            if (apps === pushedAppsRef && stateVersion == pushedVersion && !pushInFlight) {
                Log.i(START_TAG, "warm resume: rebuild skipped (fast path)")
            } else {
                Log.i(START_TAG, "warm resume: rebuild")
                pushAppsToView(apps)
            }
        }
    }

    private var lastBackHandledMs = 0L

    /**
     * One Back press, whichever path delivered it. The OnBackInvokedCallback
     * is the primary path, but for this HOME-category activity the framework
     * does not translate KEYCODE_BACK into that callback (verified on Android
     * 13 and 16: the key reaches dispatchKeyEvent, the callback never runs,
     * and the default handler finishes the activity — Back from the Smart
     * Stack exited the app instead of popping the stack). dispatchKeyEvent
     * below covers key-delivered Back; the timestamp guard keeps a press
     * that arrives through both paths from popping two layers.
     */
    private fun handleBack() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastBackHandledMs < 350L) return
        lastBackHandledMs = now
        // The island pod is the topmost layer.
        if (islandPod != null) {
            closeIslandPod()
            return
        }
        // The widget picker, then the Widgets page, take Back first.
        if (widgetPickerVisible()) {
            hideWidgetPicker()
            return
        }
        // The Widgets page overlay takes Back first (1.19.0).
        if (widgetsPageVisible()) {
            hideWidgetsPage()
            return
        }
        // If the view didn't consume Back and we are not the default home app,
        // let the user actually leave instead of swallowing the press.
        if (!view.back() && !isDefaultHomeApp()) finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) handleBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Intent.ACTION_MAIN == intent.action && hasWindowFocus()) {
            if (widgetsPageVisible()) hideWidgetsPage()
            view.home()
        }
    }

    // Back arrives via two paths — the OnBackInvokedCallback registered in
    // onCreate and KEYCODE_BACK in dispatchKeyEvent — both funnelled into
    // handleBack(), whose 350 ms guard stops one press popping two layers.
    // (An earlier version relied on the callback alone; for this
    // HOME-category activity the framework never fires it for key-delivered
    // Back, so Stack/face Back presses finished the app instead.)

    /** Pin scope for repo calls: per-layout when enabled, else the global set. */
    private fun pinScope(): String? =
        if (view.perLayoutPins) view.clusterTopology.name else null

    /**
     * Drag-to-place drop: pin the item into the dropped slot (any previous occupant
     * is unpinned and flows). The undo bar restores both the item's previous pin
     * and the evicted occupant.
     */
    private fun placeItem(item: GridItem, slot: Int) {
        val scope = pinScope()
        val prevPin = repo.pinnedSlotOf(item.key, scope)
        val occupantKey = repo.keyPinnedAt(slot, scope)?.takeIf { it != item.key }
        repo.setPinnedSlot(item.key, slot, scope)
        pushAppsToView(apps)
        view.showPlaceUndo("📌 ${item.label} placed at Hex #${slot + 1}") {
            repo.setPinnedSlot(item.key, null, scope)
            if (occupantKey != null) repo.setPinnedSlot(occupantKey, slot, scope)
            if (prevPin != null && prevPin != slot) repo.setPinnedSlot(item.key, prevPin, scope)
            pushAppsToView(apps)
            Toast.makeText(this, "Placement undone", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * TalkBack swap (2.7.0, audit #5): pin each item at the other's slot
     * behind a single undo that restores both previous pins and any
     * evicted slot occupants.
     */
    private fun swapItems(a: GridItem, slotB: Int, b: GridItem, slotA: Int) {
        val scope = pinScope()
        val prevA = repo.pinnedSlotOf(a.key, scope)
        val prevB = repo.pinnedSlotOf(b.key, scope)
        val occA = repo.keyPinnedAt(slotA, scope)?.takeIf { it != a.key && it != b.key }
        val occB = repo.keyPinnedAt(slotB, scope)?.takeIf { it != a.key && it != b.key }
        repo.setPinnedSlot(a.key, slotB, scope)
        repo.setPinnedSlot(b.key, slotA, scope)
        pushAppsToView(apps)
        view.showPlaceUndo("↔ ${a.label} and ${b.label} swapped") {
            repo.setPinnedSlot(a.key, null, scope)
            repo.setPinnedSlot(b.key, null, scope)
            if (prevA != null) repo.setPinnedSlot(a.key, prevA, scope)
            if (prevB != null) repo.setPinnedSlot(b.key, prevB, scope)
            if (occA != null) repo.setPinnedSlot(occA, slotA, scope)
            if (occB != null) repo.setPinnedSlot(occB, slotB, scope)
            pushAppsToView(apps)
            Toast.makeText(this, "Swap undone", Toast.LENGTH_SHORT).show()
        }
    }

    /** Dragging an app onto a folder bubble files it into that folder's group. */
    /**
     * Drag-onto-app folder creation (1.5.0): if the target already lives in
     * a custom folder, the carried app simply joins it; otherwise ask for a
     * name and create the folder with the target first, carried second.
     */
    private fun createFolder(carried: App, target: App) {
        val existing = repo.customFolderOf(target.key)
        if (existing != null) {
            repo.addToCustomFolder(existing.id, carried.key)
            pushAppsToView(apps)
            view.gulpFolder("cfolder:${existing.id}", 150L)
            view.showPlaceUndo("📁 ${carried.label} added to ${existing.name}") {
                repo.removeFromCustomFolder(existing.id, carried.key)
                pushAppsToView(apps)
                Toast.makeText(this, "Removed from ${existing.name}", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val suggestion = if (target.defaultGroup != AppGroup.OTHER) target.defaultGroup.title else "Folder"
        // Let the swallow animation land before the name prompt covers it.
        view.postDelayed({
            showFolderNameDialog("New Folder", suggestion, "Create") { name ->
                val id = repo.createCustomFolder(name, listOf(target.key, carried.key))
                pushAppsToView(apps)
                view.gulpFolder("cfolder:$id", 120L)
                view.showPlaceUndo("📁 Folder \u201C$name\u201D created") {
                    repo.deleteCustomFolder(id)
                    pushAppsToView(apps)
                    Toast.makeText(this, "Folder deleted", Toast.LENGTH_SHORT).show()
                }
            }
        }, 300L)
    }

    /** Takes [app] out of custom [folder] (menu, member card, drag-out). */
    private fun removeFromFolder(folder: Folder, app: App) {
        val fid = folder.customId ?: return
        repo.removeFromCustomFolder(fid, app.key)
        pushAppsToView(apps)
        view.closeFolder()
        Toast.makeText(this, "${app.label} removed from ${folder.label}", Toast.LENGTH_SHORT).show()
    }

    /** Hides an automatic category folder; members stay loose (1.7.0). */
    private fun hideFolder(folder: Folder) {
        repo.setGroupHidden(folder.group, true)
        pushAppsToView(apps)
        view.closeFolder()
        view.showPlaceUndo("\uD83D\uDC41 ${folder.label} folder hidden") {
            repo.setGroupHidden(folder.group, false)
            pushAppsToView(apps)
        }
    }

    /** Rename prompt for a custom folder (menu + Context Card share this). */
    private fun renameFolder(item: Folder) {
        val id = item.customId ?: return
        showFolderNameDialog("Rename Folder", item.label, "Rename") { name ->
            repo.renameCustomFolder(id, name)
            pushAppsToView(apps)
            Toast.makeText(this, "Renamed to $name", Toast.LENGTH_SHORT).show()
        }
    }

    /** Delete confirm for a custom folder; its apps stay loose on the grid. */
    private fun deleteFolder(item: Folder) {
        val id = item.customId ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete \u201C${item.label}\u201D?")
            .setMessage("The folder goes away; its ${item.apps.size} apps stay on the grid.")
            .setPositiveButton("Delete") { _, _ ->
                repo.deleteCustomFolder(id)
                if (view.openCustomFolder?.customId == id) view.closeFolder()
                pushAppsToView(apps)
                Toast.makeText(this, "Folder deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFolderNameDialog(title: String, initial: String, okLabel: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSingleLine(true)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(okLabel) { _, _ ->
                onOk(input.text.toString().trim().ifEmpty { initial })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fileIntoFolder(app: App, folder: Folder, slot: Int) {
        if (folder.isCustom) {
            val fid = folder.customId!!
            if (folder.apps.any { it.key == app.key }) {
                // Already a member — treat the drop as a plain placement.
                placeItem(app, slot)
                return
            }
            repo.addToCustomFolder(fid, app.key)
            pushAppsToView(apps)
            view.showPlaceUndo("📁 ${app.label} added to ${folder.label}") {
                repo.removeFromCustomFolder(fid, app.key)
                pushAppsToView(apps)
                Toast.makeText(this, "Removed from ${folder.label}", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (repo.groupOf(app) == folder.group) {
            // Already in this group — treat the drop as a plain placement.
            placeItem(app, slot)
            return
        }
        val prevGroup = repo.groupOf(app)
        repo.setGroupOverride(app, folder.group)
        pushAppsToView(apps)
        view.showPlaceUndo("📁 ${app.label} filed into ${folder.label}") {
            repo.setGroupOverride(app, if (prevGroup == app.defaultGroup) null else prevGroup)
            pushAppsToView(apps)
            Toast.makeText(this, "Filing undone", Toast.LENGTH_SHORT).show()
        }
    }

    /** Dropping a pinned bubble on the release zone unpins it (undoable). */
    private fun unpinItem(item: GridItem) {
        val scope = pinScope()
        val prevPin = repo.pinnedSlotOf(item.key, scope) ?: return
        repo.setPinnedSlot(item.key, null, scope)
        pushAppsToView(apps)
        view.showPlaceUndo("📌 ${item.label} unpinned") {
            repo.setPinnedSlot(item.key, prevPin, scope)
            pushAppsToView(apps)
            Toast.makeText(this, "Pin restored", Toast.LENGTH_SHORT).show()
        }
    }

    /** Drag-arranging inside Folder view persists the folder's member order. */
    /** Quick zone: open the system App Info page for a carried app. */
    private fun quickAppInfo(item: App) {
        runCatching {
            launcherApps.startAppDetailsActivity(item.component, item.user, null, null)
        }
    }

    /** Quick zone: ask the system to uninstall a carried app. */
    private fun quickUninstall(item: App) {
        runCatching {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", item.component.packageName, null)))
        }
    }

    private fun reorderFolderMember(folder: Folder, from: Int, to: Int) {
        val keys = folder.apps.map { it.key }
        if (from !in keys.indices || to !in keys.indices || from == to) return
        val reordered = keys.toMutableList().apply { add(to, removeAt(from)) }
        if (folder.isCustom) {
            val fid = folder.customId!!
            repo.setCustomFolderMembers(fid, reordered)
            pushAppsToView(apps)
            view.showPlaceUndo("↕ ${folder.label} order changed") {
                repo.setCustomFolderMembers(fid, keys)
                pushAppsToView(apps)
                Toast.makeText(this, "Order restored", Toast.LENGTH_SHORT).show()
            }
            return
        }
        repo.setFolderOrder(folder.group, reordered)
        pushAppsToView(apps)
        view.showPlaceUndo("↕ ${folder.label} order changed") {
            repo.setFolderOrder(folder.group, keys)
            pushAppsToView(apps)
            Toast.makeText(this, "Order restored", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isDefaultHomeApp(): Boolean {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(homeIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == packageName
    }

    override fun onDestroy() {
        nowPlaying.stop()
        runCatching { sensorManager?.unregisterListener(this) }
        launcherApps.unregisterCallback(appsChanged)
        unregisterReceiver(batteryReceiver)
        loader.shutdownNow()
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_STEP_COUNTER) {
            val counter = event.values.firstOrNull()?.toLong() ?: return
            val day = LocalDate.now().toEpochDay()
            val next = StepsToday.fold(stepsState, counter, day)
            if (StepsToday.worthSaving(stepsSaved, next)) saveStepsState(next)
            stepsState = next
            val steps = StepsToday.stepsOn(next, day).toInt()
            if (steps != view.stepCount) {
                view.stepCount = steps
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ── 3.7.3 Steps ─────────────────────────────────────────────────────
    // TYPE_STEP_COUNTER needs the runtime ACTIVITY_RECOGNITION grant;
    // until 3.7.3 nothing asked for it, so the Vitals tile never had
    // steps. The Vitals pod's "Enable step count" chip asks now.

    /** Its own store, so layout backups (the watch store) never carry it. */
    private val stepsPrefs by lazy { getSharedPreferences("steps", Context.MODE_PRIVATE) }
    private var stepsSaved: StepsToday.State? = null
    private var stepsState: StepsToday.State? = null
    private var stepSensorRegistered = false

    private fun hasStepPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun registerStepSensor() {
        if (stepsState == null && stepsPrefs.contains("day")) {
            stepsState = StepsToday.State(
                stepsPrefs.getLong("day", 0L),
                stepsPrefs.getLong("base", 0L),
                stepsPrefs.getLong("carried", 0L),
                stepsPrefs.getLong("last", 0L),
            )
            stepsSaved = stepsState
        }
        if (stepSensorRegistered || !hasStepPermission()) return
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        // Non-wakeup and batched (10 s): steps don't need frame-rate delivery.
        stepSensorRegistered = sensorManager.registerListener(
            this, sensor, SensorManager.SENSOR_DELAY_NORMAL, 10_000_000,
        )
    }

    private fun saveStepsState(s: StepsToday.State) {
        stepsPrefs.edit()
            .putLong("day", s.day).putLong("base", s.base)
            .putLong("carried", s.carried).putLong("last", s.last)
            .apply()
        stepsSaved = s
    }

    private fun requestStepPermission() {
        if (hasStepPermission()) {
            registerStepSensor()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), REQ_STEPS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_STEPS) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            registerStepSensor()
            Toast.makeText(this, "Counting today's steps from now", Toast.LENGTH_SHORT).show()
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.ACTIVITY_RECOGNITION)) {
            // Denied for good: Android won't show the dialog again, so
            // the only way left is the app's own permission page.
            Toast.makeText(this, "Allow Physical activity for Orbital to show steps", Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
        }
    }

    private fun reload() {
        val px = iconPx
        // 3.7.2: two loader tasks. The first only reads the startup
        // cache and posts the paint; the second probes the live
        // package set and reconciles. Splitting them keeps the
        // probe's enumeration off the paint's critical path — the
        // paint's own grid build can queue ahead of it.
        loader.execute {
            if (apps.isEmpty()) {
                val cached = runCatching { startupCache.read(px) }.getOrNull()
                if (cached != null && cached.apps.isNotEmpty()) {
                    cacheFingerprint = cached.fingerprint
                    cachePaintPosted = true
                    val cachedApps = cached.apps
                    view.post {
                        if (apps.isEmpty()) {
                            apps = cachedApps
                            pushAppsToView(cachedApps, PushSource.CACHE)
                        }
                    }
                }
            }
        }
        loader.execute {
            // Reconcile: when the live package set still fingerprints
            // exactly as the painted field does, the fresh load can
            // only reproduce it — skip the expensive pass entirely.
            val probe = runCatching { startupCache.probeFingerprint(px) }.getOrNull()
            if (StartupCacheLogic.canSkipFreshLoad(cacheFingerprint, probe, apps.isNotEmpty() || cachePaintPosted)) {
                Log.i(START_TAG, "reconcile: package set unchanged — fresh load skipped")
                if (!prefs.getBoolean(KEY_PRUNED_ONCE, false)) pruneStaleAppPrefs()
                return@execute
            }
            val loaded = runCatching { repo.load(px) }
                .onFailure { Log.e(TAG, "Loading apps failed", it) }
                .getOrNull() ?: return@execute
            view.post {
                apps = loaded
                pushAppsToView(loaded, PushSource.FRESH)
            }
            // Persist the new truth for the next cold start, still
            // off the main thread and after the field has landed.
            if (probe != null) {
                runCatching { startupCache.write(loaded, probe, px) }
                    .onFailure { Log.w(TAG, "Startup cache write failed", it) }
                cacheFingerprint = probe
            }
            // The package set changed, so something may be uninstalled.
            pruneStaleAppPrefs()
        }
    }

    /** Loader thread only. Uninstalled apps' entries never show, so no re-push. */
    private fun pruneStaleAppPrefs() {
        runCatching { repo.pruneUninstalled() }
            .onSuccess { n -> if (n > 0) Log.i(TAG, "Pruned $n entries of uninstalled apps") }
            .onFailure { Log.w(TAG, "Pruning uninstalled apps failed", it) }
        prefs.edit().putBoolean(KEY_PRUNED_ONCE, true).apply()
    }

    private enum class PushSource { CACHE, FRESH, ACTION }

    private fun pushAppsToView(list: List<App>, source: PushSource = PushSource.ACTION) {
        // 2.9.0 one-time: pins were stored against grids that had the
        // clock at slot 0; shift them down one so they keep their spots.
        val wprefs = getSharedPreferences("watch", Context.MODE_PRIVATE)
        if (!wprefs.getBoolean("hub_centre_v290", false)) {
            repo.shiftPinsDownAfterHubRemoval()
            wprefs.edit().putBoolean("hub_centre_v290", true).apply()
        }
        val px = iconPx
        val fixedNeighborhoods = view.fixedNeighborhoods
        val versionAtEntry = stateVersion
        pushInFlight = true
        val seq = ++pushSeq
        val foldersFirst = view.autoFolders
        // 2.9.0: the centre slot belongs to the most-used app now — the
        // Core Clock hub is no longer placed in the grids (the builders
        // keep the parameter; the clock stays in the widget catalog).
        val coreClockHub = false
        val liveWidgets = false // 2.5.0: Live Grid Widgets setting removed — frozen at Matt's value (off); builders keep the parameter so it can return later
        loader.execute {
            val scope = pinScope()
            val rawUse = repo.byUse(list)
            val byUse = repo.applyPins(rawUse, scope)
            // 3.7.2: only the grid the user will actually see is
            // built before the first paint; the other is built in a
            // chained task below and swapped in when it lands
            // (3.7.1 built both up front — the folder grid was the
            // bulk of the push cost).
            val folders = if (foldersFirst) {
                repo.buildFolderGrid(list, rawUse, px, fixedNeighborhoods, coreClockHub, liveWidgets, scope)
            } else {
                null
            }
            val flatWithWidgets = if (foldersFirst) {
                null
            } else {
                repo.buildFlatGridWithWidgets(rawUse, px, coreClockHub, liveWidgets, scope)
            }
            val pinnedKeys = HashSet<String>()
            list.forEach { if (repo.isPinned(it.key, scope)) pinnedKeys += it.key }
            folders?.forEach { if (repo.isPinned(it.key, scope)) pinnedKeys += it.key }
            WidgetKind.entries.forEach { if (repo.isPinned(it.key, scope)) pinnedKeys += it.key }
            // Usage growth: diameter factors from the recency-decayed launch
            // scores; a folder grows with its most-used member.
            val maxScore = list.maxOfOrNull { repo.usageScore(it.key) } ?: 0f
            val factors = HashMap<String, Float>()
            list.forEach { factors[it.key] = dev.watchreborn.ui.UsageScale.factor(repo.usageScore(it.key), maxScore) }
            val folderFactors = HashMap<String, Float>()
            folders?.filterIsInstance<Folder>()?.forEach { f ->
                val hottest = f.apps.maxOfOrNull { repo.usageScore(it.key) } ?: 0f
                folderFactors[f.key] = dev.watchreborn.ui.UsageScale.factor(hottest, maxScore)
            }
            factors.putAll(folderFactors)
            view.post {
                view.hiddenFolderCount = repo.hiddenGroups().size
                view.pinnedItemKeys = pinnedKeys
                val myUser = Process.myUserHandle()
                view.uninstallableKeys = list.asSequence()
                    .filter { !it.system && it.user == myUser }
                    .map { it.key }
                    .toHashSet()
                view.usageFactors = factors
                // The deferred grid reuses the previous build when it
                // was made from this same list (e.g. an option-toggle
                // re-push), else a safe stand-in until it lands.
                val folderArg = folders ?: if (lastGridsList === list) {
                    lastFolderGrid ?: emptyList()
                } else {
                    emptyList()
                }
                val flatArg = flatWithWidgets ?: if (lastGridsList === list) {
                    lastFlatGrid ?: byUse
                } else {
                    byUse
                }
                if (folders != null) {
                    lastFolderGrid = folders
                    lastGridsList = list
                }
                if (flatWithWidgets != null) {
                    lastFlatGrid = flatWithWidgets
                    lastGridsList = list
                }
                view.setApps(list, byUse, folderArg, flatArg)
                pushedAppsRef = list
                pushedVersion = versionAtEntry
                if (source == PushSource.CACHE && !cacheFieldLogged) {
                    cacheFieldLogged = true
                    Log.i(START_TAG, "field complete from cache: +" + startElapsed() + "ms since process start")
                }
                if (source == PushSource.FRESH && !freshFieldLogged) {
                    freshFieldLogged = true
                    Log.i(START_TAG, "field complete from fresh load: +" + startElapsed() + "ms since process start")
                }
                if (source != PushSource.ACTION && !frameLogged) {
                    frameLogged = true
                    val frameSrc = source.name.lowercase()
                    android.view.Choreographer.getInstance().postFrameCallback {
                        Log.i(START_TAG, "first frame drawn (" + frameSrc + "): +" +
                            startElapsed() + "ms since process start")
                    }
                }
                // The field is up: now the island host views may be
                // created (their snapshots follow). See onCreate.
                if (!islandHostsKicked) {
                    islandHostsKicked = true
                    view.post { createIslandHostViews() }
                }
            }
            // The other grid, built once the field is on its way.
            loader.execute {
                if (seq != pushSeq) return@execute // superseded — drop it
                if (foldersFirst) {
                    val flat = repo.buildFlatGridWithWidgets(rawUse, px, coreClockHub, liveWidgets, scope)
                    view.post {
                        if (seq != pushSeq) return@post
                        lastFlatGrid = flat
                        lastGridsList = list
                        pushInFlight = false
                        view.setFlatGrid(flat)
                    }
                } else {
                    val built = repo.buildFolderGrid(list, rawUse, px, fixedNeighborhoods, coreClockHub, liveWidgets, scope)
                    val builtPinned = HashSet<String>()
                    built.forEach { if (repo.isPinned(it.key, scope)) builtPinned += it.key }
                    val builtFactors = HashMap<String, Float>()
                    built.filterIsInstance<Folder>().forEach { f ->
                        val hottest = f.apps.maxOfOrNull { repo.usageScore(it.key) } ?: 0f
                        builtFactors[f.key] = dev.watchreborn.ui.UsageScale.factor(hottest, maxScore)
                    }
                    view.post {
                        if (seq != pushSeq) return@post
                        lastFolderGrid = built
                        lastGridsList = list
                        pushInFlight = false
                        view.pinnedItemKeys = view.pinnedItemKeys + builtPinned
                        view.usageFactors = view.usageFactors + builtFactors
                        view.setFolderGrid(built)
                    }
                }
            }
        }
    }

    private fun refreshInfo() {
        val alarm = getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime
        view.info = FaceInfo(battery, charging, alarm)
        runCatching {
            val am = getSystemService(ActivityManager::class.java)
            val mem = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mem)
            if (mem.totalMem > 0) view.ramUsedRatio = ((mem.totalMem - mem.availMem).toFloat() / mem.totalMem).coerceIn(0f, 1f)
            val stat = StatFs(Environment.getDataDirectory().path)
            if (stat.totalBytes > 0) view.storageUsedRatio = ((stat.totalBytes - stat.availableBytes).toFloat() / stat.totalBytes).coerceIn(0f, 1f)
        }
        view.nowPlayingTrack = nowPlaying.track
    }

    private fun handleTopologySelected(topo: ClusterTopology) {
        prefs.edit().putString(KEY_CLUSTER_TOPOLOGY, topo.name).apply()
    }

    /** Persists one design's pan/zoom as it's left (see 1.11.0/1.11.1). */
    /** The accent is the picked colour, full stop. (Wallpaper
     *  Tint, added in 3.2.5, was removed in 3.4.0 at Matt's call:
     *  the accent no longer leans toward the wallpaper.) */
    private fun refreshAccent() {
        // 3.8.2: the accent comes from the active theme pack; the
        // view blends the pack's day/night values at draw time. (The
        // old single-colour KEY_ACCENT pref is retired: no UI in the
        // current app ever wrote it, and the Orbital pack's accent is
        // that same green, so an un-themed install changes nothing.)
        view.themePack = ThemePacks.byId(prefs.getString(KEY_THEME_PACK, ThemePacks.DEFAULT_ID))
    }

    /** 3.2.5: an island was resized on the cluster — persist the
     *  scale, resize the hosted widget so its snapshot re-renders at
     *  the new size, and re-run fit-first placement so a grown tile
     *  can't hang off the field. */
    private fun applyIslandScale(id: Int, scale: Float) {
        val clamped = IslandLayout.clampScale(scale)
        prefs.edit().putFloat(islandKey(id, "scale"), clamped).apply()
        islandSlots[id]?.let { it.scale = clamped }
        // 3.6.0: resize every hosted member of the slot — stack
        // members share the island's footprint. Native members are
        // drawn into the slot rect and follow on their own.
        val (baseW, baseH) = slotTileDp(id)
        val wDp = (baseW * clamped).toInt().coerceIn(108, 756)
        val hDp = (baseH * clamped).toInt().coerceIn(66, 540)
        val dens = resources.displayMetrics.density
        for (wid in IslandMembers.systemIds(islandMembersOf(id))) {
            val hv = islandViews[wid] ?: continue
            hv.layoutParams = FrameLayout.LayoutParams((wDp * dens).toInt(), (hDp * dens).toInt())
            sendIslandSize(wid, wDp, hDp)
            hv.post { snapshotIsland(wid) }
        }
        pushIslandsToView()
    }

    private fun handleTopoViewSaved(topo: ClusterTopology, x: Float, y: Float, zoom: Float) {
        prefs.edit()
            .putFloat("topoview:${topo.name}:x", x)
            .putFloat("topoview:${topo.name}:y", y)
            .putFloat("topoview:${topo.name}:z", zoom)
            .apply()
    }

    private fun handleClusterOptionChanged(opt: ClusterOption) {
        when (opt) {
            ClusterOption.CLUSTER_TOPOLOGY -> {
                // Handled inside WatchView: the sheet row opens the topology picker
                // and selections persist via handleTopologySelected.
            }
            ClusterOption.THEME_PACK -> {
                // Handled inside WatchView: the sheet row opens the theme picker
                // and selections persist via onThemePackSelected.
            }
            ClusterOption.UNHIDE_FOLDERS -> {
                repo.clearHiddenGroups()
                if (apps.isNotEmpty()) pushAppsToView(apps)
                Toast.makeText(this, "Hidden folders restored", Toast.LENGTH_SHORT).show()
            }
            ClusterOption.PER_LAYOUT_PINS -> {
                val next = !view.perLayoutPins
                prefs.edit().putBoolean(KEY_PER_LAYOUT_PINS, next).apply()
                view.perLayoutPins = next
                if (apps.isNotEmpty()) pushAppsToView(apps)
            }
            ClusterOption.USAGE_GROWTH -> {
                val next = !view.usageGrowth
                prefs.edit().putBoolean(KEY_USAGE_GROWTH, next).apply()
                view.usageGrowth = next
            }
            ClusterOption.LIVING_FIELD -> {
                val next = !view.livingField
                prefs.edit().putBoolean(KEY_LIVING_FIELD, next).apply()
                view.livingField = next
            }
            ClusterOption.THEMED_ICONS -> {
                val next = !view.themedIcons
                prefs.edit().putBoolean(KEY_THEMED_ICONS, next).apply()
                view.themedIcons = next
                repo.themedIcons = next
                // The painted icons are the other style: force the
                // fresh load (it rewrites the cache under the new style).
                cacheFingerprint = null
                reload()
            }
            ClusterOption.BLOOM_MODE -> {
                val next = !view.bloomFolders
                prefs.edit().putBoolean(KEY_BLOOM, next).apply()
                view.bloomFolders = next
            }
            ClusterOption.CATEGORY_CLUSTERING -> {
                // 3.2.4: the Settings toggle is back — the pref and
                // the view-side grouping survived, but no UI could
                // change the pref once Control Center went away in
                // 3.0.0. The setter rebuilds the grid itself.
                val next = !view.autoFolders
                prefs.edit().putBoolean(KEY_FOLDERS, next).apply()
                view.autoFolders = next
            }
            ClusterOption.CONSTELLATION_SEARCH -> {
                view.startConstellationSearch()
            }
            ClusterOption.FIXED_NEIGHBORHOODS -> {
                val next = !view.fixedNeighborhoods
                prefs.edit().putBoolean(KEY_FIXED_NEIGHBORHOODS, next).apply()
                view.fixedNeighborhoods = next
                if (apps.isNotEmpty()) pushAppsToView(apps)
            }
            ClusterOption.SET_DEFAULT_HOME -> {
                chooseHome()
            }
            ClusterOption.ADD_ISLAND -> {
                addTargetIsland = true
                showWidgetPicker()
            }
            ClusterOption.NEW_FACE -> {
                showNewFaceDialog()
            }
            ClusterOption.BACKUP_LAYOUT -> {
                startBackupLayout()
            }
            ClusterOption.RESTORE_LAYOUT -> {
                startRestoreLayout()
            }
            ClusterOption.CLEAR_PINS -> {
                // 2.7.0 (audit #3): clearing every pin was the one
                // destructive action with no safety net — snapshot all
                // scopes and offer the standard undo bar.
                val snapshot = repo.allPins()
                repo.clearAllPins()
                if (apps.isNotEmpty()) pushAppsToView(apps)
                view.showPlaceUndo("📌 Cleared ${snapshot.size} pinned hex slots") {
                    repo.restorePins(snapshot)
                    if (apps.isNotEmpty()) pushAppsToView(apps)
                    Toast.makeText(this, "Pins restored", Toast.LENGTH_SHORT).show()
                }
            }
            ClusterOption.FACE_ROW -> Unit // face taps carry an id; the view handles them directly
            ClusterOption.MORE_OPTIONS -> Unit // expand/collapse is view-local
            ClusterOption.CLOSE_SHEET -> Unit
        }
    }


    // ── 3.3.0 Layout backup & restore ───────────────────────────────

    private fun startBackupLayout() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "orbital-layout.json")
        }
        runCatching { startActivityForResult(intent, REQ_BACKUP_LAYOUT) }
            .onFailure { Toast.makeText(this, "File picker unavailable on this device", Toast.LENGTH_SHORT).show() }
    }

    private fun startRestoreLayout() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        runCatching { startActivityForResult(intent, REQ_RESTORE_LAYOUT) }
            .onFailure { Toast.makeText(this, "File picker unavailable on this device", Toast.LENGTH_SHORT).show() }
    }

    /** The live stores as one backup JSON — the file export and
     *  a face snapshot are the same document (3.5.0). */
    private fun serializeLiveStores(): String {
        val stores = LayoutBackup.STORES.associateWith { name ->
            getSharedPreferences(name, Context.MODE_PRIVATE).all
        }
        val info = packageManager.getPackageInfo(packageName, 0)
        return LayoutBackup.serialize(
            stores,
            info.versionName ?: "",
            info.longVersionCode.toInt(),
            System.currentTimeMillis(),
        )
    }

    private fun writeBackupTo(uri: Uri) {
        val ok = runCatching {
            val json = serializeLiveStores()
            val out = contentResolver.openOutputStream(uri, "wt")
                ?: error("no output stream")
            out.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }.isSuccess
        Toast.makeText(
            this,
            if (ok) "Layout backed up" else "Backup failed — nothing was written",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun readBackupFrom(uri: Uri) {
        val backup = runCatching {
            val input = contentResolver.openInputStream(uri) ?: error("no input stream")
            val text = input.use { it.readBytes().toString(Charsets.UTF_8) }
            LayoutBackup.parse(text)
        }.getOrElse { e ->
            // Validation failed or the file couldn't be read: the
            // current layout stays exactly as it is.
            Toast.makeText(
                this,
                if (e is LayoutBackup.BackupException) e.message else "Could not read that file.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val skippedIslands = countUnresolvableIslands(backup)
        val whenText = android.text.format.DateFormat.format(
            "MMM d, yyyy HH:mm", java.util.Date(backup.header.exportedAtMillis),
        )
        AlertDialog.Builder(this)
            .setTitle("Restore layout?")
            .setMessage(
                "Backup from Orbital ${backup.header.versionName} • $whenText • ${backup.entryCount} settings.\n\n" +
                    "Restoring replaces your current layout — topology, pins, widget islands and settings all revert to the backup." +
                    if (skippedIslands > 0) {
                        "\n\n$skippedIslands widget island(s) in the backup aren't installed on this device and will be skipped."
                    } else {
                        ""
                    },
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restore") { _, _ -> applyBackup(backup) }
            .show()
    }

    /** Islands in the backup whose SYSTEM members this device
     *  can't resolve. (3.6.0: native members always resolve — they
     *  are drawn by Orbital itself — and a stack's slot id is not
     *  a widget id.) */
    private fun countUnresolvableIslands(backup: LayoutBackup.Backup): Int {
        val watch = backup.stores[LayoutBackup.STORE_WATCH] ?: return 0
        val slotIds = (watch[KEY_ISLAND_IDS] as? String)
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return 0
        val sys = HashSet<Int>()
        for (slotId in slotIds) {
            val members = watch[islandKey(slotId, "members")] as? String
            if (members != null) sys += IslandMembers.systemIdsOfRaw(members)
            else if (slotId > 0) sys += slotId
        }
        return sys.count { appWidgetManager.getAppWidgetInfo(it) == null }
    }

    /** Rewrites both live stores from a parsed backup — the one
     *  apply path a file restore and a face switch share. */
    private fun writeBackupStores(backup: LayoutBackup.Backup) {
        for ((name, entries) in backup.stores) {
            if (name !in LayoutBackup.STORES) continue
            val edit = getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for ((key, value) in entries) {
                when (value) {
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                    is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            // commit(), not apply(): both stores must be durable on
            // disk before recreate() tears this activity down.
            edit.commit()
        }
    }

    private fun applyBackup(backup: LayoutBackup.Backup) {
        writeBackupStores(backup)
        // 3.5.0: the restored layout becomes the active face's own
        // memory, so face and live layout stay consistent.
        snapshotLiveIntoActiveFace()
        Toast.makeText(this, "Layout restored", Toast.LENGTH_SHORT).show()
        // onCreate rebuilds the view, the repo and the island set
        // from the two stores, so a recreate applies the restore
        // exactly as a cold start would.
        recreate()
    }

    // ── 3.5.0 Faces (saved cluster layouts) ─────────────────────

    private fun facesList(): List<Faces.Face> {
        val ids = facesPrefs.getString(Faces.KEY_IDS, null)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        return ids.mapNotNull { id ->
            val name = facesPrefs.getString(Faces.keyName(id), null) ?: return@mapNotNull null
            val data = facesPrefs.getString(Faces.keyData(id), null) ?: return@mapNotNull null
            Faces.Face(id, name, data)
        }
    }

    private fun activeFaceId(): String? = facesPrefs.getString(Faces.KEY_ACTIVE, null)

    private fun newFaceId(): String {
        val existing = facesList().map { it.id }.toSet()
        var id = "f" + System.currentTimeMillis().toString(36)
        var n = 0
        while (id in existing) { n++; id = "f" + System.currentTimeMillis().toString(36) + "x" + n }
        return id
    }

    /** First run: one face named "Main" holding the current live
     *  layout, active — nobody loses the layout they have. */
    private fun ensureFacesMigrated() {
        if (facesList().isNotEmpty()) return
        val face = Faces.migrationFace(newFaceId(), serializeLiveStores())
        facesPrefs.edit()
            .putString(Faces.KEY_IDS, face.id)
            .putString(Faces.keyName(face.id), face.name)
            .putString(Faces.keyData(face.id), face.data)
            .putString(Faces.KEY_ACTIVE, face.id)
            .commit()
    }

    private fun pushFacesToView() {
        val active = activeFaceId()
        view.faceEntries = facesList().map { face ->
            val topoName = Faces.topologyNameOf(face.data)
            val title = topoName?.let { name ->
                runCatching { ClusterTopology.valueOf(name).fullTitle }.getOrNull()
            } ?: topoName ?: "Saved layout"
            WatchView.FaceEntry(face.id, face.name, title, face.id == active)
        }
    }

    /** The active face re-snapshots the live stores into its own
     *  slot — how a face "remembers itself", with no save step. */
    private fun snapshotLiveIntoActiveFace() {
        val active = activeFaceId() ?: return
        if (facesList().none { it.id == active }) return
        facesPrefs.edit().putString(Faces.keyData(active), serializeLiveStores()).commit()
    }

    private fun switchFace(id: String) {
        val target = facesList().firstOrNull { it.id == id } ?: return
        if (id == activeFaceId()) return
        val backup = runCatching { LayoutBackup.parse(target.data) }.getOrElse {
            Toast.makeText(this, "That face's saved layout is unreadable", Toast.LENGTH_LONG).show()
            return
        }
        // The outgoing face remembers the live state first; the
        // active id flips in the faces store before the recreate,
        // so it survives the switch it drives.
        snapshotLiveIntoActiveFace()
        facesPrefs.edit().putString(Faces.KEY_ACTIVE, id).commit()
        writeBackupStores(backup)
        recreate()
    }

    private fun showNewFaceDialog() {
        val faces = facesList()
        if (!Faces.canAdd(faces.size)) {
            Toast.makeText(
                this,
                "That's the limit — ${Faces.MAX_FACES} faces. Delete one to add another.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val input = EditText(this).apply {
            setText(Faces.nextFaceName(faces.map { it.name }))
            setSingleLine(true)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("New Face")
            .setMessage("Copies the current layout into a new face you can switch to any time.")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                    .ifEmpty { Faces.nextFaceName(facesList().map { it.name }) }
                createFace(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createFace(name: String) {
        val faces = facesList()
        if (!Faces.canAdd(faces.size)) {
            Toast.makeText(this, "That's the limit — ${Faces.MAX_FACES} faces.", Toast.LENGTH_LONG).show()
            return
        }
        val id = newFaceId()
        val ids = (faces.map { it.id } + id).joinToString(",")
        facesPrefs.edit()
            .putString(Faces.KEY_IDS, ids)
            .putString(Faces.keyName(id), name)
            .putString(Faces.keyData(id), serializeLiveStores())
            .commit()
        pushFacesToView()
        Toast.makeText(this, "Face \u201C$name\u201D created", Toast.LENGTH_SHORT).show()
    }

    private fun showFaceMenu(id: String) {
        val face = facesList().firstOrNull { it.id == id } ?: return
        AlertDialog.Builder(this)
            .setTitle(face.name)
            .setItems(arrayOf("Rename", "Delete")) { _, which ->
                when (which) {
                    0 -> showRenameFaceDialog(face)
                    1 -> confirmDeleteFace(face)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameFaceDialog(face: Faces.Face) {
        val input = EditText(this).apply {
            setText(face.name)
            setSingleLine(true)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Face")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { face.name }
                facesPrefs.edit().putString(Faces.keyName(face.id), name).commit()
                pushFacesToView()
                Toast.makeText(this, "Renamed to $name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteFace(face: Faces.Face) {
        val faces = facesList()
        if (faces.size <= 1) {
            Toast.makeText(this, "Keep at least one face", Toast.LENGTH_SHORT).show()
            return
        }
        val isActive = face.id == activeFaceId()
        val survivor = faces.firstOrNull { it.id != face.id }
        AlertDialog.Builder(this)
            .setTitle("Delete \u201C${face.name}\u201D?")
            .setMessage(
                if (isActive) {
                    "This is the face you're using. Orbital switches to \u201C${survivor?.name}\u201D first, then deletes it — its saved layout goes away."
                } else {
                    "Its saved layout goes away. Faces that share its widget islands keep them."
                },
            )
            .setPositiveButton("Delete") { _, _ -> deleteFace(face) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteFace(face: Faces.Face) {
        val faces = facesList()
        if (faces.size <= 1) return
        val isActive = face.id == activeFaceId()
        if (isActive) {
            // Switch first (the survivor's snapshot becomes live),
            // then delete — the outgoing face's self-save would be
            // discarded along with it anyway.
            val survivor = faces.first { it.id != face.id }
            val backup = runCatching { LayoutBackup.parse(survivor.data) }.getOrNull()
            if (backup == null) {
                Toast.makeText(this, "Couldn't switch away from this face", Toast.LENGTH_LONG).show()
                return
            }
            facesPrefs.edit().putString(Faces.KEY_ACTIVE, survivor.id).commit()
            writeBackupStores(backup)
        }
        // Widget deallocation: ids the deleted face referenced that
        // no surviving face references and the live set isn't using.
        val remaining = faces.filter { it.id != face.id }
        val orphans = Faces.orphanedWidgetIds(
            Faces.islandIdsOf(face.data),
            remaining.map { Faces.islandIdsOf(it.data) },
            savedIslandIds()
                .flatMap { IslandMembers.systemIds(islandMembersOf(it)) }.toSet(),
        )
        facesPrefs.edit()
            .putString(Faces.KEY_IDS, remaining.map { it.id }.joinToString(","))
            .remove(Faces.keyName(face.id))
            .remove(Faces.keyData(face.id))
            .commit()
        orphans.forEach { runCatching { appWidgetHost.deleteAppWidgetId(it) } }
        if (isActive) {
            recreate()
        } else {
            pushFacesToView()
        }
        Toast.makeText(this, "Face \u201C${face.name}\u201D deleted", Toast.LENGTH_SHORT).show()
    }

    private fun handleCardAction(action: CardAction) {
        when (action) {
            CardAction.MEDIA_PREV -> {
                if (nowPlaying.track != null) nowPlaying.previous()
                else sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }
            CardAction.MEDIA_PLAY_PAUSE -> {
                if (nowPlaying.track != null) nowPlaying.playPause()
                else sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            }
            CardAction.MEDIA_NEXT -> {
                if (nowPlaying.track != null) nowPlaying.next()
                else sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            }
            CardAction.MEDIA_OPEN -> {
                if (nowPlaying.open()) return
                if (!nowPlaying.allowed) {
                    Toast.makeText(this, "Enable Notification Access for live Now Playing info", Toast.LENGTH_SHORT).show()
                    runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    return
                }
                val music = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
                runCatching { startActivity(music) }
            }
            CardAction.OPEN_ALARMS -> {
                if (runCatching { startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }.isFailure) {
                    apps.firstOrNull { it.label.contains("Clock", ignoreCase = true) }?.let { launch(it, RectF()) }
                }
            }
            CardAction.OPEN_BATTERY -> {
                if (runCatching { startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) }.isFailure) {
                    runCatching { startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }
                }
            }
            CardAction.OPEN_CALENDAR -> {
                val builder = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
                ContentUris.appendId(builder, System.currentTimeMillis())
                val intent = Intent(Intent.ACTION_VIEW).setData(builder.build())
                if (runCatching { startActivity(intent) }.isFailure) {
                    apps.firstOrNull { it.label.contains("Calendar", ignoreCase = true) }?.let { launch(it, RectF()) }
                }
            }
            CardAction.ENABLE_STEPS -> requestStepPermission()
        }
    }

    private fun sendMediaKey(keyCode: Int) {
        runCatching {
            val now = android.os.SystemClock.uptimeMillis()
            audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        }
    }

    private fun launch(app: App, from: RectF) {
        try {
            val f = LaunchFrom.of(view, from)
            launcherApps.startMainActivity(app.component, app.user, f?.sourceBounds, f?.options)
            repo.markOpened(app.key)
            stateVersion++ // 3.7.2: frecency changed — the next resume re-pushes
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Can't open ${app.label}", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Can't open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun itemOptions(item: GridItem, slotIndex: Int) {
        val menu = PopupMenu(this, view, Gravity.CENTER)
        val currentlyPinnedSlot = repo.pinnedSlotOf(item.key, pinScope())

        // #6 Hex Slot Pinning options for both Apps and Folders
        if (currentlyPinnedSlot != null) {
            menu.menu.add(0, 300, 0, "📌 Unpin Hex Slot (#${currentlyPinnedSlot + 1})")
        } else if (slotIndex >= 0) {
            menu.menu.add(0, 301, 0, "📌 Pin to Current Hex (#${slotIndex + 1})")
        }
        if (currentlyPinnedSlot != 0 && slotIndex != 0) {
            menu.menu.add(0, 302, 1, "📌 Pin to Center Core (#1)")
        }

        when (item) {
            is WidgetBubble -> {
                menu.menu.add(1, 319, 4, "⚡ Open Blooming Widget Pod")
                when (item.kind) {
                    WidgetKind.CORE_CLOCK -> {
                        // No extra actions since 3.0.0 (the Control Center it opened is gone).
                    }
                    WidgetKind.NOW_PLAYING -> {
                        menu.menu.add(1, 330, 5, "⏯ Play / Pause")
                        menu.menu.add(1, 331, 6, "⏭ Next Track")
                        menu.menu.add(1, 332, 7, "⏮ Previous Track")
                        menu.menu.add(1, 333, 8, "🎵 Open Media App")
                    }
                    WidgetKind.VITALS -> {
                        menu.menu.add(1, 340, 6, "🔋 Battery & Vitals Settings")
                    }
                    WidgetKind.TIMER -> {
                        menu.menu.add(1, 341, 5, "⏰ Open System Clock / Alarms")
                    }
                    WidgetKind.CALENDAR -> {
                        menu.menu.add(1, 342, 5, "📅 Open System Calendar")
                        menu.menu.add(1, 341, 6, "⏰ Open Alarms")
                    }
                }
                menu.menu.add(2, 345, 14, "🧩 Android System Widgets...")
                menu.menu.add(2, 311, 15, "⚙ Settings...")
                menu.menu.add(2, 312, 16, "🏠 Set as Default Launcher")
                menu.setOnMenuItemClickListener { mi ->
                    when (mi.itemId) {
                        300 -> {
                            repo.setPinnedSlot(item.key, null, pinScope())
                            pushAppsToView(apps)
                            Toast.makeText(this, "Unpinned ${item.label}", Toast.LENGTH_SHORT).show()
                        }
                        301 -> {
                            repo.setPinnedSlot(item.key, slotIndex, pinScope())
                            pushAppsToView(apps)
                            Toast.makeText(this, "Pinned ${item.label} to Hex #${slotIndex + 1}", Toast.LENGTH_SHORT).show()
                        }
                        302 -> {
                            repo.setPinnedSlot(item.key, 0, pinScope())
                            pushAppsToView(apps)
                            Toast.makeText(this, "Pinned ${item.label} to Center Core", Toast.LENGTH_SHORT).show()
                        }
                        319 -> view.openWidgetPod(item)
                        330 -> handleCardAction(CardAction.MEDIA_PLAY_PAUSE)
                        331 -> handleCardAction(CardAction.MEDIA_NEXT)
                        332 -> handleCardAction(CardAction.MEDIA_PREV)
                        333 -> handleCardAction(CardAction.MEDIA_OPEN)
                        340 -> handleCardAction(CardAction.OPEN_BATTERY)
                        341 -> handleCardAction(CardAction.OPEN_ALARMS)
                        342 -> handleCardAction(CardAction.OPEN_CALENDAR)
                        345 -> showWidgetsPage()
                        311 -> view.openClusterSheet()
                        312 -> chooseHome()
                    }
                    true
                }
            }
            is Folder -> {
                menu.menu.add(1, 310, 5, "Open ${item.label} (${item.apps.size} apps)")
                menu.menu.add(1, 311, 6, "⚙ Settings...")
                menu.menu.add(1, 312, 7, "🏠 Set as Default Launcher")
                if (item.isCustom) {
                    menu.menu.add(1, 314, 9, "✎ Rename Folder…")
                    menu.menu.add(1, 315, 10, "🗑 Delete Folder")
                }
                menu.setOnMenuItemClickListener { mi ->
                    when (mi.itemId) {
                        300 -> {
                            repo.setPinnedSlot(item.key, null, pinScope())
                            pushAppsToView(apps)
                            Toast.makeText(this, "Unpinned ${item.label}", Toast.LENGTH_SHORT).show()
                        }
                        301 -> {
                            repo.setPinnedSlot(item.key, slotIndex, pinScope())
                            pushAppsToView(apps)
                            Toast.makeText(this, "Pinned ${item.label} to Hex #${slotIndex + 1}", Toast.LENGTH_SHORT).show()
                        }
                        302 -> {
                            repo.setPinnedSlot(item.key, 0, pinScope())
                            pushAppsToView(apps)
                            Toast.makeText(this, "Pinned ${item.label} to Center Core", Toast.LENGTH_SHORT).show()
                        }
                        310 -> view.openFolder(item)
                        311 -> view.openClusterSheet()
                        312 -> chooseHome()
                        314 -> renameFolder(item)
                        315 -> deleteFolder(item)
                    }
                    true
                }
            }
            is App -> {
                val shortcuts = Shortcuts.forApp(launcherApps, item.component, item.user)
                shortcuts.forEachIndexed { idx, s ->
                    val label = s.shortLabel ?: s.longLabel ?: s.id
                    menu.menu.add(1, 10 + idx, 5 + idx, "⚡ $label")
                }

                // Browsed inside a custom folder? Offer removal (1.5.0).
                val hostFolder = view.openCustomFolder
                if (hostFolder != null && hostFolder.apps.any { it.key == item.key }) {
                    menu.menu.add(4, 316, 4, "Remove from ${hostFolder.label}")
                }

                // Move to Folder submenu
                val currentGroup = repo.groupOf(item)
                val folderSub = menu.menu.addSubMenu(2, 50, 20, "Folder: ${currentGroup.title}")
                AppGroup.entries.forEachIndexed { idx, g ->
                    val prefix = if (g == currentGroup) "✓ " else ""
                    folderSub.add(2, 200 + idx, idx, "$prefix${g.title}")
                }

                menu.menu.add(3, 311, 25, "⚙ Settings...")
                menu.menu.add(3, 1, 30, "App Info")
                if (!item.system && item.user == Process.myUserHandle()) {
                    menu.menu.add(3, 2, 31, "Uninstall")
                }

                menu.setOnMenuItemClickListener { mi ->
                    when (mi.itemId) {
                        300 -> {
                            repo.setPinnedSlot(item.key, null, pinScope())
                            pushAppsToView(apps)
                            Toast.makeText(this, "Unpinned ${item.label}", Toast.LENGTH_SHORT).show()
                        }
                        301 -> {
                            repo.setPinnedSlot(item.key, slotIndex, pinScope())
                            pushAppsToView(apps)
                            Toast.makeText(this, "Pinned ${item.label} to Hex #${slotIndex + 1}", Toast.LENGTH_SHORT).show()
                        }
                        302 -> {
                            repo.setPinnedSlot(item.key, 0, pinScope())
                            pushAppsToView(apps)
                            Toast.makeText(this, "Pinned ${item.label} to Center Core", Toast.LENGTH_SHORT).show()
                        }
                        in 10 until 10 + shortcuts.size -> {
                            val s = shortcuts[mi.itemId - 10]
                            runCatching { launcherApps.startShortcut(s, null, null) }
                        }
                        in 200 until 200 + AppGroup.entries.size -> {
                            val g = AppGroup.entries[mi.itemId - 200]
                            repo.setGroupOverride(item, g)
                            pushAppsToView(apps)
                        }
                        316 -> {
                            val f = view.openCustomFolder
                            if (f != null) removeFromFolder(f, item)
                        }
                        311 -> view.openClusterSheet()
                        1 -> runCatching { launcherApps.startAppDetailsActivity(item.component, item.user, null, null) }
                        2 -> runCatching { startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", item.component.packageName, null))) }
                    }
                    true
                }
            }
        }
        menu.show()
    }

    private fun chooseHome() {
        val homeSettings = Intent(Settings.ACTION_HOME_SETTINGS)
        if (runCatching { startActivity(homeSettings) }.isSuccess) return
        val roles = getSystemService(RoleManager::class.java)
        if (roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            @Suppress("DEPRECATION")
            runCatching { startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), 1) }
        } else {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
        }
    }

    @SuppressLint("WrongConstant")

    private fun savedWidgetIds(): List<Int> {
        return prefs.getStringSet(KEY_APPWIDGET_IDS, emptySet()).orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .sorted()
    }

    private fun saveWidgetIds(ids: List<Int>) {
        prefs.edit().putStringSet(KEY_APPWIDGET_IDS, ids.map { it.toString() }.toSet()).apply()
    }

    private fun dpPx(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------
    // Widgets page (1.19.0): the dock's fourth page. The Smart Stack
    // surface Matt never opened gave up its slot to a full page of real
    // Android system widgets, hosted with the same AppWidgetHost
    // pick/configure flow as the old Cluster Options portal dialog —
    // which this page replaces. The page is a native overlay above the
    // view that stops above the dock, so the dock stays live: tapping
    // any other segment leaves the page (see WatchView's dock taps),
    // and Back / Home close it too.
    // ------------------------------------------------------------------

    private var widgetsPage: FrameLayout? = null
    private var widgetsScroll: ScrollView? = null
    private var widgetsListCol: LinearLayout? = null

    private fun widgetsPageVisible(): Boolean = widgetsPage?.visibility == View.VISIBLE

    private fun showWidgetsPage() {
        val page = widgetsPage ?: buildWidgetsPage().also { widgetsPage = it }
        if (page.parent == null) {
            rootFrame?.addView(page)
        }
        populateWidgetsPage()
        page.visibility = View.VISIBLE
        view.widgetsPageOpen = true
        view.openFullHoneycomb()
    }

    private fun hideWidgetsPage() {
        widgetsPage?.visibility = View.GONE
        view.widgetsPageOpen = false
    }

    private fun buildWidgetsPage(): FrameLayout {
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(10, 11, 14))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply { bottomMargin = dpPx(118f) }
            visibility = View.GONE
            // Swallow touches so the surfaces underneath stay inert.
            isClickable = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpPx(18f), dpPx(46f), dpPx(18f), dpPx(14f))
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "🧩 Widgets"
            textSize = 19f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val addBtn = TextView(this).apply {
            text = "＋ Add"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(dpPx(16f), dpPx(9f), dpPx(16f), dpPx(9f))
            background = GradientDrawable().apply {
                setColor(view.accent)
                cornerRadius = dpPx(20f).toFloat()
            }
            setOnClickListener { addTargetIsland = false; showWidgetPicker() }
        }
        titleRow.addView(title)
        titleRow.addView(addBtn)
        col.addView(titleRow)

        val sub = TextView(this).apply {
            text = "Real Android widgets, hosted inside Orbital"
            textSize = 11.5f
            setTextColor(Color.rgb(180, 185, 195))
            setPadding(0, dpPx(4f), 0, dpPx(12f))
        }
        col.addView(sub)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        val listCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        widgetsListCol = listCol
        widgetsScroll = scroll
        scroll.addView(listCol)
        col.addView(scroll)
        frame.addView(col)
        return frame
    }

    private fun populateWidgetsPage() {
        val listCol = widgetsListCol ?: return
        listCol.removeAllViews()
        val ids = savedWidgetIds().toMutableList()
        val validIds = ArrayList<Int>()
        for (id in ids) {
            val info = appWidgetManager.getAppWidgetInfo(id)
            if (info == null) {
                runCatching { appWidgetHost.deleteAppWidgetId(id) }
                continue
            }
            validIds += id
            // 3.0.2 (Matt's call): no card — the widget sits clean on
            // the page at full width: no panel, padding, or header.
            // Removal moved to a long-press on the widget itself.
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dpPx(16f) }
            }
            val widgetLabel = info.loadLabel(packageManager) ?: "App Widget"
            val heightDp = widgetHeightDp(id, info)
            runCatching {
                val hv = appWidgetHost.createView(this, id, info)
                hv.setAppWidget(id, info)
                // A hosted widget's own buttons swallow a plain
                // long-click, so a frame around the widget steals the
                // long-press first (see WidgetLongPressFrame below).
                val frame = WidgetLongPressFrame(this)
                frame.onLongPress = {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle(widgetLabel)
                        .setMessage("Remove this widget from the page?")
                        .setPositiveButton("Remove") { _, _ ->
                            runCatching { appWidgetHost.deleteAppWidgetId(id) }
                            prefs.edit().remove("appwidget_h_$id").apply()
                            saveWidgetIds(savedWidgetIds().filter { it != id })
                            populateWidgetsPage()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                frame.addView(
                    hv,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                card.addView(
                    frame,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dpPx(heightDp.toFloat()),
                    ),
                )
                sendWidgetSize(id, heightDp)
                card.addView(buildResizeHandle(id, frame))
            }.onFailure {
                val err = TextView(this).apply {
                    text = "Widget preview unavailable"
                    textSize = 11f
                    setTextColor(Color.GRAY)
                }
                card.addView(err)
            }
            listCol.addView(card)
        }
        if (validIds.size != ids.size) saveWidgetIds(validIds)
        if (validIds.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No widgets here yet.\n\nTap '＋ Add' above to put any Clock, Weather, Calendar, Music, or third-party widget on this page."
                textSize = 12.5f
                setTextColor(Color.rgb(200, 205, 215))
                gravity = Gravity.CENTER
                setPadding(dpPx(16f), dpPx(36f), dpPx(16f), dpPx(36f))
            }
            listCol.addView(empty)
        }
    }

    /** A widget's card height in dp: the height Matt dragged it to
     *  (1.21.0), else the provider's minimum with a 150dp floor so
     *  content isn't cropped. (AppWidgetProviderInfo sizes are dp.) */
    private fun widgetHeightDp(id: Int, info: AppWidgetProviderInfo): Int {
        val saved = prefs.getInt("appwidget_h_$id", 0)
        return if (saved > 0) saved else info.minHeight.coerceAtLeast(150)
    }

    /** Report our size like a launcher does (1.19.1). Some providers —
     *  the DeskClock digital clock is one — only (re)push their content
     *  in response to the options callback; without it their card
     *  stayed blank here while simpler widgets rendered fine. */
    private fun sendWidgetSize(id: Int, heightDp: Int) {
        val dm = resources.displayMetrics
        val wDp = ((dm.widthPixels / dm.density) - 36f).toInt().coerceAtLeast(40)
        val opts = android.os.Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
        }
        runCatching { appWidgetManager.updateAppWidgetOptions(id, opts) }
    }


    /** Frame around a hosted widget that steals long-presses before
     *  the widget's own content can consume them (3.0.2): hold anywhere
     *  on the widget ~450ms without moving and [onLongPress] fires;
     *  interception cancels the child's pending click, so its buttons
     *  never trigger. Short taps and scrolls pass straight through. */
    private inner class WidgetLongPressFrame(ctx: android.content.Context) : FrameLayout(ctx) {
        var onLongPress: (() -> Unit)? = null
        private var downX = 0f
        private var downY = 0f
        private var fired = false
        private val fireRunnable = Runnable {
            if (!fired) {
                fired = true
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onLongPress?.invoke()
            }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    fired = false
                    removeCallbacks(fireRunnable)
                    postDelayed(fireRunnable, 450L)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(ev.x - downX) > dpPx(10f) || Math.abs(ev.y - downY) > dpPx(10f)) {
                        removeCallbacks(fireRunnable)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(fireRunnable)
            }
            return fired
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (fired) {
                if (ev.actionMasked == MotionEvent.ACTION_UP ||
                    ev.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    fired = false
                }
                return true
            }
            return super.onTouchEvent(ev)
        }
    }

    /** Bottom-edge drag handle (1.21.0, Matt's pick): a slim grip at
     *  the foot of each widget card. Dragging it resizes the card live;
     *  on release the height is remembered per widget and reported to
     *  the provider so it re-lays itself out at the new size. */
    private fun buildResizeHandle(id: Int, hv: View): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            // 32dp tall (1.22.0): the 24dp strip was a fiddly target,
            // and the row now announces itself to TalkBack.
            isFocusable = true
            contentDescription = "Resize widget. Drag up or down, or tap to cycle through sizes."
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpPx(32f),
            )
        }
        val grip = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.rgb(140, 146, 158))
                cornerRadius = dpPx(3f).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dpPx(48f), dpPx(6f))
        }
        row.addView(grip)
        // 2.7.0 (audit #5): tapping the handle cycles preset heights —
        // the non-drag alternative. TalkBack activation routes through
        // this click; the touch listener forwards only bare taps, so a
        // real drag still resizes freely.
        row.isClickable = true
        row.setOnClickListener {
            val curDp = if (hv.height > 0) (hv.height / resources.displayMetrics.density).toInt()
                else prefs.getInt("appwidget_h_$id", 240)
            val presets = intArrayOf(150, 240, 340, 460)
            val next = presets.firstOrNull { it > curDp + 12 } ?: presets.first()
            hv.layoutParams = hv.layoutParams.apply { height = dpPx(next.toFloat()) }
            prefs.edit().putInt("appwidget_h_$id", next).apply()
            sendWidgetSize(id, next)
        }
        var startY = 0f
        var startH = 0
        var dragged = false
        row.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = ev.rawY
                    startH = if (hv.height > 0) hv.height else hv.layoutParams.height
                    dragged = false
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(ev.rawY - startY) > dpPx(10f)) dragged = true
                    val newH = (startH + (ev.rawY - startY).toInt())
                        .coerceIn(dpPx(110f), dpPx(560f))
                    val lp = hv.layoutParams
                    if (lp.height != newH) {
                        lp.height = newH
                        hv.layoutParams = lp
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragged && ev.actionMasked == MotionEvent.ACTION_UP) {
                        v.performClick()
                    } else if (dragged) {
                        val hDp = (hv.height / resources.displayMetrics.density)
                            .toInt().coerceAtLeast(1)
                        prefs.edit().putInt("appwidget_h_$id", hDp).apply()
                        sendWidgetSize(id, hDp)
                    }
                    true
                }
                else -> true
            }
        }
        return row
    }

    // ------------------------------------------------------------------
    // Custom widget picker (1.20.0). The system's "Choose widget"
    // sheet is a bare name list — Matt couldn't tell what any widget
    // actually looked like. This picker shows each provider's own
    // preview image on a card. Tapping one binds it directly
    // (bindAppWidgetIdIfAllowed), falling back to the system's
    // one-time bind-consent screen, and as a last resort to the old
    // system list picker.
    // ------------------------------------------------------------------

    private var widgetsPicker: FrameLayout? = null
    private var pickerLoadGen = 0
    private var widgetsPickerCol: LinearLayout? = null
    private var pendingBindId: Int = -1
    private var pendingBindInfo: AppWidgetProviderInfo? = null

    private fun widgetPickerVisible(): Boolean = widgetsPicker?.visibility == View.VISIBLE

    private fun showWidgetPicker() {
        val picker = widgetsPicker ?: buildWidgetPicker().also { widgetsPicker = it }
        if (picker.parent == null) {
            rootFrame?.addView(picker)
        }
        populateWidgetPicker()
        picker.visibility = View.VISIBLE
    }

    private fun hideWidgetPicker() {
        widgetsPicker?.visibility = View.GONE
    }

    private fun buildWidgetPicker(): FrameLayout {
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(10, 11, 14))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply { bottomMargin = dpPx(118f) }
            visibility = View.GONE
            isClickable = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpPx(18f), dpPx(46f), dpPx(18f), dpPx(14f))
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "🧩 Choose a widget"
            textSize = 19f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(this).apply {
            text = "✕ Close"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dpPx(14f), dpPx(9f), dpPx(14f), dpPx(9f))
            background = GradientDrawable().apply {
                setColor(Color.rgb(52, 54, 62))
                cornerRadius = dpPx(20f).toFloat()
            }
            setOnClickListener { hideWidgetPicker() }
        }
        titleRow.addView(title)
        titleRow.addView(closeBtn)
        col.addView(titleRow)

        val sub = TextView(this).apply {
            text = "What each widget actually looks like — tap one to add it"
            textSize = 11.5f
            setTextColor(Color.rgb(180, 185, 195))
            setPadding(0, dpPx(4f), 0, dpPx(12f))
        }
        col.addView(sub)

        // 3.6.0 Part A: the Built-in section — populated per open
        // by populateBuiltinSection (island / stack targets only).
        // It lives OUTSIDE the provider list column, whose contents
        // the async provider load replaces wholesale.
        val builtinCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        widgetsPickerBuiltinCol = builtinCol
        col.addView(builtinCol)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        val listCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        widgetsPickerCol = listCol
        scroll.addView(listCol)
        col.addView(scroll)
        frame.addView(col)
        return frame
    }

    private fun populateWidgetPicker() {
        populateBuiltinSection()
        val col = widgetsPickerCol ?: return
        col.removeAllViews()
        val loading = TextView(this).apply {
            text = "Loading widgets\u2026"
            textSize = 12.5f
            setTextColor(Color.rgb(200, 205, 215))
            gravity = Gravity.CENTER
            setPadding(dpPx(16f), dpPx(36f), dpPx(16f), dpPx(36f))
        }
        col.addView(loading)
        // 3.0.3 (Matt: "a lot of latency when select add"): this used
        // to run entirely on the UI thread - every provider's label
        // lookups (repeatedly, inside the sort comparator) plus every
        // preview image, synchronously at tap time, before the picker
        // even appeared. Now the picker opens instantly and the list
        // is gathered off-thread; labels are resolved once per row.
        val gen = ++pickerLoadGen
        val density = resources.configuration.densityDpi
        kotlin.concurrent.thread(name = "widget-picker-load") {
            val pm = packageManager
            val rows = runCatching {
                appWidgetManager.installedProviders.map { info ->
                    val app = runCatching {
                        pm.getApplicationLabel(
                            pm.getApplicationInfo(info.provider.packageName, 0),
                        ).toString()
                    }.getOrDefault(info.provider.packageName)
                    val preview = runCatching {
                        info.loadPreviewImage(this@MainActivity, density)
                    }.getOrNull()
                    val icon = if (preview == null) {
                        runCatching { info.loadIcon(this@MainActivity, density) }.getOrNull()
                    } else {
                        null
                    }
                    PickerRow(info, info.loadLabel(pm) ?: "App Widget", app, preview, icon)
                }.sortedWith(compareBy({ it.app.lowercase() }, { it.label.lowercase() }))
            }.getOrDefault(emptyList())
            runOnUiThread {
                if (gen != pickerLoadGen || widgetsPickerCol !== col) return@runOnUiThread
                col.removeAllViews()
                if (rows.isEmpty()) {
                    val empty = TextView(this@MainActivity).apply {
                        text = "No widgets available on this device."
                        textSize = 12.5f
                        setTextColor(Color.rgb(200, 205, 215))
                        gravity = Gravity.CENTER
                        setPadding(dpPx(16f), dpPx(36f), dpPx(16f), dpPx(36f))
                    }
                    col.addView(empty)
                    return@runOnUiThread
                }
                for (row in rows) {
                    col.addView(buildPickerCard(row))
                }
            }
        }
    }

    /** 3.6.0 Part A: the picker's Built-in section — shown only
     *  when the picker targets an island (a new island, or a stack
     *  append); built-ins are islands, not Widgets-page widgets. */
    private fun populateBuiltinSection() {
        val col = widgetsPickerBuiltinCol ?: return
        col.removeAllViews()
        if (!addTargetIsland && stackTargetSlot == null) {
            col.visibility = View.GONE
            return
        }
        col.visibility = View.VISIBLE
        val header = TextView(this).apply {
            text = "BUILT-IN — DRAWN LIVE BY ORBITAL"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(150, 200, 130))
            setPadding(0, dpPx(2f), 0, dpPx(8f))
        }
        col.addView(header)
        for (kind in NativeIslands.pickerKinds) {
            col.addView(buildBuiltinCard(kind))
        }
    }

    private fun buildBuiltinCard(kind: NativeKind): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpPx(14f), dpPx(12f), dpPx(14f), dpPx(12f))
            background = GradientDrawable().apply {
                setColor(Color.rgb(34, 36, 44))
                cornerRadius = dpPx(18f).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dpPx(8f) }
        }
        val glyph = TextView(this).apply {
            text = kind.glyph
            textSize = 24f
            setPadding(0, 0, dpPx(12f), 0)
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val name = TextView(this).apply {
            text = kind.label
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val blurb = TextView(this).apply {
            text = kind.blurb
            textSize = 11.5f
            setTextColor(Color.rgb(170, 175, 185))
        }
        texts.addView(name)
        texts.addView(blurb)
        card.addView(glyph)
        card.addView(texts)
        card.setOnClickListener { pickNativeKind(kind) }
        return card
    }

    /** A built-in pick: appended to the target stack, or a fresh
     *  native island. */
    private fun pickNativeKind(kind: NativeKind) {
        val target = stackTargetSlot
        if (target != null) {
            stackTargetSlot = null
            appendNativeToStack(target, kind)
        } else {
            finishAddNative(kind)
        }
    }

    /** One picker row (3.0.3): everything PackageManager-bound is
     *  resolved once, off the UI thread, before views are built. */
    private data class PickerRow(
        val info: AppWidgetProviderInfo,
        val label: String,
        val app: String,
        val preview: Drawable?,
        val icon: Drawable?,
    )

    private fun buildPickerCard(row: PickerRow): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpPx(12f), dpPx(12f), dpPx(12f), dpPx(12f))
            background = GradientDrawable().apply {
                setColor(Color.rgb(34, 36, 44))
                cornerRadius = dpPx(18f).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dpPx(10f) }
            isClickable = true
            setOnClickListener { bindAndAddWidget(row.info) }
        }
        if (row.preview != null) {
            // The provider's own preview image - the whole point of
            // this picker (Matt couldn't tell widgets apart in the
            // old name-only system list).
            val iv = ImageView(this).apply {
                setImageDrawable(row.preview)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                maxHeight = dpPx(130f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dpPx(8f) }
            }
            card.addView(iv)
        } else if (row.icon != null) {
            // No preview shipped: fall back to the app icon, kept
            // small - a full-width icon looks like a broken preview.
            val iv = ImageView(this).apply {
                setImageDrawable(row.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpPx(64f),
                ).apply { bottomMargin = dpPx(8f) }
            }
            card.addView(iv)
        }
        val lbl = TextView(this).apply {
            text = row.label
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        card.addView(lbl)
        val meta = TextView(this).apply {
            text = row.app
            textSize = 11f
            setTextColor(Color.rgb(170, 175, 185))
            setPadding(0, dpPx(2f), 0, 0)
        }
        card.addView(meta)
        return card
    }

    private fun bindAndAddWidget(info: AppWidgetProviderInfo) {
        val id = appWidgetHost.allocateAppWidgetId()
        val opts = android.os.Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, info.minWidth)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, info.minWidth)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, info.minHeight)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, info.minHeight)
        }
        val bound = runCatching {
            appWidgetManager.bindAppWidgetIdIfAllowed(id, info.provider, opts)
        }.getOrDefault(false)
        if (bound) {
            configureOrFinish(id, info)
            return
        }
        // Needs the system's one-time bind consent.
        pendingBindId = id
        pendingBindInfo = info
        val bindIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
        }
        val launched = runCatching {
            startActivityForResult(bindIntent, REQ_BIND_APPWIDGET)
        }.isSuccess
        if (!launched) {
            pendingBindId = -1
            pendingBindInfo = null
            runCatching { appWidgetHost.deleteAppWidgetId(id) }
            // Last resort: the old system list picker.
            launchWidgetPicker()
        }
    }

    @Suppress("DEPRECATION")
    private fun launchWidgetPicker() {
        val appWidgetId = appWidgetHost.allocateAppWidgetId()
        val pickIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        runCatching { startActivityForResult(pickIntent, REQ_PICK_APPWIDGET) }
            .onFailure {
                appWidgetHost.deleteAppWidgetId(appWidgetId)
                Toast.makeText(this, "Widget picker unavailable on this device", Toast.LENGTH_SHORT).show()
            }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val widgetId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        if (requestCode == REQ_PICK_APPWIDGET) {
            if (resultCode == RESULT_OK && widgetId != -1) {
                val info = appWidgetManager.getAppWidgetInfo(widgetId)
                if (info?.configure != null) {
                    val cfg = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                        component = info.configure
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    }
                    if (runCatching { startActivityForResult(cfg, REQ_CREATE_APPWIDGET) }.isSuccess) return
                }
                finishAddWidget(widgetId)
            } else if (widgetId != -1) {
                runCatching { appWidgetHost.deleteAppWidgetId(widgetId) }
            }
        } else if (requestCode == REQ_CREATE_APPWIDGET) {
            if (resultCode == RESULT_OK && widgetId != -1) {
                finishAddWidget(widgetId)
            } else if (widgetId != -1) {
                runCatching { appWidgetHost.deleteAppWidgetId(widgetId) }
            }
        } else if (requestCode == REQ_BIND_APPWIDGET) {
            val bindId = pendingBindId
            val bindInfo = pendingBindInfo
            pendingBindId = -1
            pendingBindInfo = null
            if (resultCode == RESULT_OK && bindId != -1 && bindInfo != null) {
                configureOrFinish(bindId, bindInfo)
            } else if (bindId != -1) {
                runCatching { appWidgetHost.deleteAppWidgetId(bindId) }
            }
        } else if (requestCode == REQ_BACKUP_LAYOUT) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) writeBackupTo(uri)
        } else if (requestCode == REQ_RESTORE_LAYOUT) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) readBackupFrom(uri)
        }
    }

    /** After a successful bind: run the widget's configure screen if it
     *  has one, otherwise finish adding straight away. */
    private fun configureOrFinish(id: Int, info: AppWidgetProviderInfo) {
        if (info.configure != null) {
            val cfg = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                component = info.configure
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            }
            if (runCatching { startActivityForResult(cfg, REQ_CREATE_APPWIDGET) }.isSuccess) return
        }
        finishAddWidget(id)
    }

    private fun finishAddWidget(widgetId: Int) {
        val stackTarget = stackTargetSlot
        if (stackTarget != null) {
            stackTargetSlot = null
            finishAddToStack(stackTarget, widgetId)
            return
        }
        if (addTargetIsland) {
            addTargetIsland = false
            finishAddIsland(widgetId)
            return
        }
        val updated = (savedWidgetIds() + widgetId).distinct()
        saveWidgetIds(updated)
        hideWidgetPicker()
        showWidgetsPage()
        // Land on the widget just added (1.22.0): it's appended at the
        // bottom of the page, off-screen when the page is long.
        widgetsScroll?.post { widgetsScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    // ── 3.2.0 Anchor Islands: implementation ────────────────────────────

    private fun islandKey(id: Int, part: String) = "island_${id}_$part"

    private fun savedIslandIds(): List<Int> =
        prefs.getString(KEY_ISLAND_IDS, "").orEmpty()
            .split(',').mapNotNull { it.trim().toIntOrNull() }

    private fun saveIslandIds(ids: List<Int>) {
        prefs.edit().putString(KEY_ISLAND_IDS, ids.joinToString(",")).apply()
    }

    /** Tile size in dp from the provider's own minimums, clamped sane. */
    private fun islandTileDp(info: AppWidgetProviderInfo): Pair<Int, Int> {
        val w = (if (info.minWidth > 0) info.minWidth else 300).coerceIn(180, 420)
        val h = (if (info.minHeight > 0) info.minHeight else 160).coerceIn(110, 300)
        return w to h
    }

    /** The slot's ordered member tokens (3.6.0). Slots written
     *  before stacks existed have no members key: they synthesise
     *  a single system member from their own id — exactly the
     *  pre-3.6.0 behaviour. Native slots (negative ids) always
     *  carry an explicit members key. */
    private fun islandMembersOf(slotId: Int): List<String> {
        val raw = prefs.getString(islandKey(slotId, "members"), null)
        if (raw != null) return IslandMembers.parse(raw)
        return if (slotId > 0) listOf(IslandMembers.sysToken(slotId)) else emptyList()
    }

    /** Persist membership and mirror it into the live slot. */
    private fun saveIslandMembers(slotId: Int, members: List<String>, top: Int) {
        val t = IslandMembers.clampTopIndex(members, top)
        prefs.edit()
            .putString(islandKey(slotId, "members"), IslandMembers.serialize(members))
            .putInt(islandKey(slotId, "top"), t)
            .apply()
        islandSlots[slotId]?.let { slot ->
            slot.memberTokens = members
            slot.topIndex = t
            slot.label = islandSlotLabel(slotId)
        }
    }

    /** A member's display label: provider label, or the native
     *  kind's name. */
    private fun memberLabel(token: String): String {
        IslandMembers.nativeKind(token)?.let { return it.label }
        val wid = IslandMembers.sysId(token) ?: return "Widget"
        return appWidgetManager.getAppWidgetInfo(wid)
            ?.loadLabel(packageManager)?.toString() ?: "Widget"
    }

    /** The slot's label = its current top member's label. */
    private fun islandSlotLabel(slotId: Int): String {
        val members = islandMembersOf(slotId)
        val top = IslandMembers.clampTopIndex(
            members, prefs.getInt(islandKey(slotId, "top"), 0),
        )
        return members.getOrNull(top)?.let { memberLabel(it) } ?: "Widget"
    }

    /** The slot's tile size: its FIRST member's footprint — stack
     *  members share the island's established size. */
    private fun slotTileDp(slotId: Int): Pair<Int, Int> {
        val first = islandMembersOf(slotId).firstOrNull()
        if (first != null) {
            val wid = IslandMembers.sysId(first)
            if (wid != null) {
                val info = appWidgetManager.getAppWidgetInfo(wid)
                if (info != null) return islandTileDp(info)
            }
            IslandMembers.nativeKind(first)?.let { return it.defaultWDp to it.defaultHDp }
        }
        return 300 to 160
    }

    /** Native island slot ids are a persisted negative sequence —
     *  never appWidgetIds — so every island pref (anchors, scale,
     *  members, faces snapshots, backups) round-trips without
     *  anything trying to resolve them as hosted widgets. */
    private fun nextNativeSlotId(): Int {
        val seq = prefs.getInt("island_native_seq", 0) + 1
        prefs.edit().putInt("island_native_seq", seq).apply()
        return -seq
    }

    private fun sendIslandSize(id: Int, wDp: Int, hDp: Int) {
        val opts = android.os.Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp)
        }
        runCatching { appWidgetManager.updateAppWidgetOptions(id, opts) }
    }

    private fun loadIslands() {
        createIslandHostViews()
        pushIslandsToView()
    }

    /** Creates the hosted view for every system island member.
     *  3.7.2: onCreate calls this deferred, past the first frame;
     *  idempotent — members already hosted are skipped. */
    private fun createIslandHostViews() {
        for (slotId in savedIslandIds()) {
            for (wid in IslandMembers.systemIds(islandMembersOf(slotId))) {
                if (wid in islandViews) continue
                val info = appWidgetManager.getAppWidgetInfo(wid) ?: continue
                createIslandView(slotId, wid, info)
            }
        }
    }

    /** Host one system member of a slot (3.6.0: [slotId] owns the
     *  tile size and scale; [id] is the member's appWidgetId). */
    private fun createIslandView(slotId: Int, id: Int, info: AppWidgetProviderInfo) {
        val bin = islandContainer ?: return
        val hv = appWidgetHost.createView(this, id, info)
        val (baseW, baseH) = slotTileDp(slotId)
        // 3.2.5: a resized island keeps its scale across restarts.
        val scale0 = IslandLayout.clampScale(prefs.getFloat(islandKey(slotId, "scale"), 1f))
        val wDp = (baseW * scale0).toInt().coerceIn(108, 756)
        val hDp = (baseH * scale0).toInt().coerceIn(66, 540)
        val dens = resources.displayMetrics.density
        hv.layoutParams = FrameLayout.LayoutParams((wDp * dens).toInt(), (hDp * dens).toInt())
        bin.addView(hv)
        islandViews[id] = hv
        islandSlotByWidget[id] = slotId
        sendIslandSize(id, wDp, hDp)
        hv.post { snapshotIsland(id) }
    }

    private fun pushIslandsToView() {
        val hexPx = (resources.displayMetrics.widthPixels * Metrics.GRID_PITCH * 1.10f).coerceAtLeast(1f)
        val dens = resources.displayMetrics.density
        val slots = savedIslandIds().map { id ->
            islandSlots.getOrPut(id) {
                val (wDp, hDp) = slotTileDp(id)
                WatchView.IslandSlot(
                    id = id,
                    anchorX = prefs.getFloat(islandKey(id, "ax"), 0f),
                    anchorY = prefs.getFloat(islandKey(id, "ay"), 2.05f),
                    halfW = ((wDp * dens) / hexPx / 2f).coerceIn(0.75f, 1.60f),
                    halfH = ((hDp * dens) / hexPx / 2f).coerceIn(0.45f, 1.20f),
                    label = islandSlotLabel(id),
                ).also { slot ->
                    slot.scale = IslandLayout.clampScale(prefs.getFloat(islandKey(id, "scale"), 1f))
                }
            }
        }
        // 3.6.0: membership can change under a live slot (stack
        // appends / removals / cycling) — refresh it before the
        // fit-first heal below reads anchors and extents.
        for (slot in slots) {
            val members = islandMembersOf(slot.id)
            slot.memberTokens = members
            slot.topIndex = IslandMembers.clampTopIndex(
                members, prefs.getInt(islandKey(slot.id, "top"), 0),
            )
            slot.label = islandSlotLabel(slot.id)
        }
        // 3.2.2 fit-first placement: an island's anchor is pulled in
        // until the whole tile fits the field, so wide/tall widgets
        // never hang off a screen edge — on first add and on load.
        // (Drags stay free in-session; a lost-off-edge island heals
        // back into view on the next load.) Corrected anchors are
        // written back to prefs so the fix sticks.
        val dm = resources.displayMetrics
        // 3.6.1: the heal's pitch must be the VIEW's pitch —
        // its unit is min(width, 340dp), not the raw display
        // width. The width-based pitch made the heal's field
        // ~20% narrower than the field the view snaps and clamps
        // to, so a snapped anchor was pulled back toward the
        // centre column on every load (for a wide tile its x
        // limit collapsed to 0). Same unit formula as iconPx.
        val unitPxC = minOf(
            dm.widthPixels.toFloat(),
            Metrics.WATCH_WIDTH_DP * dm.density,
        )
        val hexPxC = (unitPxC * Metrics.GRID_PITCH * 1.10f).coerceAtLeast(1f)
        fun limXFor(slot: WatchView.IslandSlot) =
            ((dm.widthPixels / 2f) / hexPxC - slot.halfW - 0.12f).coerceAtLeast(0f)
        fun limYFor(slot: WatchView.IslandSlot) =
            ((dm.heightPixels / 2f) / hexPxC - slot.halfH - 0.12f).coerceAtLeast(0f)
        for (slot in slots) {
            if (slot.bornAt == 0L) slot.bornAt = android.os.SystemClock.uptimeMillis()
            slot.anchorX = slot.anchorX.coerceIn(-limXFor(slot), limXFor(slot))
            slot.anchorY = slot.anchorY.coerceIn(-limYFor(slot), limYFor(slot))
        }
        // Islands must not overlap each other either: clamping can pull
        // several wide tiles into the same centre column. Stack pass —
        // tiles whose x-ranges overlap are separated vertically, first
        // downward, then back upward for whatever the bottom squeezed.
        val byY = slots.sortedBy { it.anchorY }
        fun xOverlap(a: WatchView.IslandSlot, b: WatchView.IslandSlot) =
            a.anchorX - a.halfW < b.anchorX + b.halfW + 0.05f &&
                b.anchorX - b.halfW < a.anchorX + a.halfW + 0.05f
        for (pass in 0..1) {
            val seq = if (pass == 0) byY else byY.asReversed()
            var prev: WatchView.IslandSlot? = null
            for (slot in seq) {
                val p = prev
                if (p != null && xOverlap(slot, p)) {
                    if (pass == 0) {
                        val minTop = p.anchorY + p.halfH + 0.08f
                        val need = minTop - (slot.anchorY - slot.halfH)
                        if (need > 0f) slot.anchorY =
                            (slot.anchorY + need).coerceAtMost(limYFor(slot))
                    } else {
                        val maxBottom = p.anchorY - p.halfH - 0.08f
                        val over = (slot.anchorY + slot.halfH) - maxBottom
                        if (over > 0f) slot.anchorY =
                            (slot.anchorY - over).coerceAtLeast(-limYFor(slot))
                    }
                }
                prev = slot
            }
        }
        for (slot in slots) {
            val storedX = prefs.getFloat(islandKey(slot.id, "ax"), Float.NaN)
            val storedY = prefs.getFloat(islandKey(slot.id, "ay"), Float.NaN)
            if (storedX != slot.anchorX || storedY != slot.anchorY) {
                prefs.edit()
                    .putFloat(islandKey(slot.id, "ax"), slot.anchorX)
                    .putFloat(islandKey(slot.id, "ay"), slot.anchorY)
                    .apply()
            }
        }
        view.islands = slots
        view.invalidate()
    }

    private fun scheduleIslandSnapshot(id: Int) {
        if (!islandSnapPending.add(id)) return
        islandSnapHandler.postDelayed({
            islandSnapPending.remove(id)
            snapshotIsland(id)
        }, 350)
    }

    private fun snapshotIsland(id: Int) {
        val hv = islandViews[id] ?: return
        val slotId = islandSlotByWidget[id] ?: return
        val slot = islandSlots[slotId] ?: return
        val w = hv.width
        val h = hv.height
        if (w <= 0 || h <= 0) {
            hv.post { snapshotIsland(id) }
            return
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        hv.draw(Canvas(bmp))
        val token = IslandMembers.sysToken(id)
        val old = slot.bitmaps[token]
        slot.bitmaps[token] = bmp
        if (old != null && old !== bmp) old.recycle()
        if (view.clusterTopology == ClusterTopology.FORCE_FIELD ||
            view.clusterTopology == ClusterTopology.HONEYCOMB ||
            view.clusterTopology == ClusterTopology.GRAVITY
        ) view.invalidate()
    }

    private fun finishAddIsland(widgetId: Int) {
        val info = appWidgetManager.getAppWidgetInfo(widgetId)
        val n = savedIslandIds().size
        // Anchor on a loose ring around the core, golden-angle spaced, so
        // several islands don't land on the same spot.
        // Start due south of the core: bar-shaped widgets stay centred
        // horizontally instead of hanging off the east edge.
        val ang = 1.5708f + n * 2.39996f
        prefs.edit()
            .putFloat(islandKey(widgetId, "ax"), kotlin.math.cos(ang) * 2.05f)
            .putFloat(islandKey(widgetId, "ay"), kotlin.math.sin(ang) * 2.05f)
            .apply()
        saveIslandIds((savedIslandIds() + widgetId).distinct())
        saveIslandMembers(widgetId, listOf(IslandMembers.sysToken(widgetId)), 0)
        if (info != null) createIslandView(widgetId, widgetId, info)
        pushIslandsToView()
        hideWidgetPicker()
        val islandTopologies = setOf(
            ClusterTopology.HONEYCOMB, ClusterTopology.GRAVITY, ClusterTopology.FORCE_FIELD)
        if (view.clusterTopology !in islandTopologies) {
            prefs.edit().putString(KEY_CLUSTER_TOPOLOGY, ClusterTopology.FORCE_FIELD.name).apply()
            view.clusterTopology = ClusterTopology.FORCE_FIELD
        }
        view.invalidate()
        Toast.makeText(this, "Widget island added", Toast.LENGTH_LONG).show()
    }

    /** Picker -> a fresh native island (3.6.0 Part A): mirrors
     *  finishAddIsland's placement, but the slot id is a negative
     *  sequence number and the members key names the native kind. */
    private fun finishAddNative(kind: NativeKind) {
        val slotId = nextNativeSlotId()
        val n = savedIslandIds().size
        val ang = 1.5708f + n * 2.39996f
        prefs.edit()
            .putFloat(islandKey(slotId, "ax"), kotlin.math.cos(ang) * 2.05f)
            .putFloat(islandKey(slotId, "ay"), kotlin.math.sin(ang) * 2.05f)
            .apply()
        saveIslandMembers(slotId, listOf(IslandMembers.nativeToken(kind)), 0)
        saveIslandIds((savedIslandIds() + slotId).distinct())
        pushIslandsToView()
        hideWidgetPicker()
        val islandTopologies = setOf(
            ClusterTopology.HONEYCOMB, ClusterTopology.GRAVITY, ClusterTopology.FORCE_FIELD)
        if (view.clusterTopology !in islandTopologies) {
            prefs.edit().putString(KEY_CLUSTER_TOPOLOGY, ClusterTopology.FORCE_FIELD.name).apply()
            view.clusterTopology = ClusterTopology.FORCE_FIELD
        }
        view.invalidate()
        Toast.makeText(this, "${kind.label} island added", Toast.LENGTH_LONG).show()
    }

    /** Picker (system path) -> append the widget to a stack. The
     *  new member becomes the visible one, so the add is seen. */
    private fun finishAddToStack(slotId: Int, widgetId: Int) {
        val info = appWidgetManager.getAppWidgetInfo(widgetId)
        val members = islandMembersOf(slotId).toMutableList()
        val token = IslandMembers.sysToken(widgetId)
        if (token !in members) members += token
        saveIslandMembers(slotId, members, members.size - 1)
        if (info != null) createIslandView(slotId, widgetId, info)
        pushIslandsToView()
        hideWidgetPicker()
        view.invalidate()
        Toast.makeText(this, "Added to the stack", Toast.LENGTH_SHORT).show()
    }

    /** Picker (built-in path) -> append a native to a stack. */
    private fun appendNativeToStack(slotId: Int, kind: NativeKind) {
        val members = islandMembersOf(slotId).toMutableList()
        val token = IslandMembers.nativeToken(kind)
        if (token in members) {
            Toast.makeText(
                this, "${kind.label} is already in this stack", Toast.LENGTH_SHORT,
            ).show()
            hideWidgetPicker()
            return
        }
        members += token
        saveIslandMembers(slotId, members, members.size - 1)
        pushIslandsToView()
        hideWidgetPicker()
        view.invalidate()
        Toast.makeText(this, "${kind.label} added to the stack", Toast.LENGTH_SHORT).show()
    }

    /** Dot-row tap (3.6.0 Part B): advance the stack's top member
     *  with the view's slide animation; membership persists, so
     *  the cycle survives restarts and rides face snapshots. */
    private fun cycleIslandStack(slotId: Int) {
        val members = islandMembersOf(slotId)
        if (members.size <= 1) return
        val cur = IslandMembers.clampTopIndex(
            members, prefs.getInt(islandKey(slotId, "top"), 0),
        )
        val next = IslandMembers.nextTopIndex(members, cur)
        islandSlots[slotId]?.let { slot ->
            slot.cycleFromIndex = cur
            slot.cycleStartMs = android.os.SystemClock.uptimeMillis()
        }
        saveIslandMembers(slotId, members, next)
        view.invalidate()
    }

    /** True while any live island or any saved face still
     *  references [wid] — a stack member's appWidgetId is only
     *  released when nothing references it (the 3.5.0 face-orphan
     *  rule, applied live). */
    private fun widgetReferencedElsewhere(wid: Int): Boolean {
        for (slotId in savedIslandIds()) {
            if (wid in IslandMembers.systemIds(islandMembersOf(slotId))) return true
        }
        val faceIds = facesPrefs.getString(Faces.KEY_IDS, null)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        for (fid in faceIds) {
            val data = facesPrefs.getString(Faces.keyData(fid), null) ?: continue
            if (wid in Faces.islandIdsOf(data)) return true
        }
        return false
    }

    /** Edit-menu action (3.6.0 Part B): remove the slot's current
     *  top member. Down to one member the stack dissolves back
     *  into a plain island (same model, a single member). */
    private fun removeStackMember(slotId: Int) {
        val members = islandMembersOf(slotId).toMutableList()
        if (members.size <= 1) return
        val top = IslandMembers.clampTopIndex(
            members, prefs.getInt(islandKey(slotId, "top"), 0),
        )
        val removed = members.removeAt(top)
        val removedLabel = memberLabel(removed)
        saveIslandMembers(slotId, members, top)
        IslandMembers.sysId(removed)?.let { wid ->
            islandViews.remove(wid)?.let { hv ->
                (hv.parent as? ViewGroup)?.removeView(hv)
            }
            islandSlotByWidget.remove(wid)
            islandSlots[slotId]?.bitmaps?.remove(IslandMembers.sysToken(wid))?.recycle()
            if (!widgetReferencedElsewhere(wid)) {
                runCatching { appWidgetHost.deleteAppWidgetId(wid) }
            }
        }
        pushIslandsToView()
        Toast.makeText(
            this, "Removed $removedLabel from the stack", Toast.LENGTH_SHORT,
        ).show()
    }

    /** Open the pod for an island SLOT (3.6.0: the pod shows the
     *  slot's current top member — a system widget's live view, or
     *  an enlarged native rendering for a built-in). */
    private fun openIslandPod(id: Int) {
        val members = islandMembersOf(id)
        val top = IslandMembers.clampTopIndex(
            members, prefs.getInt(islandKey(id, "top"), 0),
        )
        val token = members.getOrNull(top) ?: return
        IslandMembers.nativeKind(token)?.let { kind ->
            openNativeIslandPod(id, kind)
            return
        }
        val wid = IslandMembers.sysId(token) ?: return
        val hv = islandViews[wid] ?: return
        if (appWidgetManager.getAppWidgetInfo(wid) == null) return
        closeIslandPod()
        // 3.2.5: the pod's "tile size" is the tile as resized.
        val (tileW, tileH) = slotTileDp(id)
        val podScale = islandSlots[id]?.scale ?: 1f
        val wDp = (tileW * podScale).toInt().coerceIn(108, 756)
        val hDp = (tileH * podScale).toInt().coerceIn(66, 540)
        val dens = resources.displayMetrics.density
        val screenWDp = (resources.displayMetrics.widthPixels / dens).toInt()
        val podW = minOf(wDp * 2, screenWDp - 32).coerceAtLeast(120)
        val podH = (podW.toFloat() * hDp / wDp).toInt().coerceAtLeast(80)
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            isClickable = true
            setOnClickListener { closeIslandPod() }
        }
        val card = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(24, 28, 36))
            elevation = 12f * dens
        }
        (hv.parent as? ViewGroup)?.removeView(hv)
        hv.layoutParams = FrameLayout.LayoutParams((podW * dens).toInt(), (podH * dens).toInt())
        card.addView(hv)
        sendIslandSize(wid, podW, podH)
        scrim.addView(
            card,
            FrameLayout.LayoutParams((podW * dens).toInt(), (podH * dens).toInt(), android.view.Gravity.CENTER),
        )
        rootFrame?.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        islandPod = scrim
        islandPodId = id
        islandPodWidgetId = wid
        // 3.2.2: the pod grows out of the island tile it came from
        // (shared-element feel) while the scrim fades in under it.
        val tileRect = view.islands.firstOrNull { it.id == id }?.rect?.takeIf { !it.isEmpty }
        if (tileRect != null) {
            val vLoc = IntArray(2)
            view.getLocationOnScreen(vLoc)
            val tileCx = vLoc[0] + tileRect.centerX()
            val tileCy = vLoc[1] + tileRect.centerY()
            scrim.background?.alpha = 0
            card.post {
                if (islandPod !== scrim) return@post
                val cLoc = IntArray(2)
                card.getLocationOnScreen(cLoc)
                card.pivotX = card.width / 2f
                card.pivotY = card.height / 2f
                card.translationX = tileCx - (cLoc[0] + card.width / 2f)
                card.translationY = tileCy - (cLoc[1] + card.height / 2f)
                card.scaleX = (tileRect.width() / card.width).coerceIn(0.05f, 1f)
                card.scaleY = (tileRect.height() / card.height).coerceIn(0.05f, 1f)
                card.alpha = 0.35f
                card.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                    .alpha(1f).setDuration(260)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
                android.animation.ValueAnimator.ofInt(0, 190).apply {
                    duration = 260
                    addUpdateListener {
                        scrim.setBackgroundColor(Color.argb(it.animatedValue as Int, 0, 0, 0))
                    }
                    start()
                }
            }
        }
    }

    /** Pod for a native top member (3.6.0 Part A): the system
     *  pod's chrome with a NativePodView inside — enlarged live
     *  rendering plus the kind's action chips. No hosted view is
     *  re-homed afterwards; teardown is just the scrim. */
    private fun openNativeIslandPod(slotId: Int, kind: NativeKind) {
        closeIslandPod()
        val (tileW, tileH) = slotTileDp(slotId)
        val podScale = islandSlots[slotId]?.scale ?: 1f
        val wDp = (tileW * podScale).toInt().coerceIn(108, 756)
        val hDp = (tileH * podScale).toInt().coerceIn(66, 540)
        val dens = resources.displayMetrics.density
        val screenWDp = (resources.displayMetrics.widthPixels / dens).toInt()
        val podW = minOf(wDp * 2, screenWDp - 32).coerceAtLeast(120)
        // +52dp: room for the pod view's chip rows under the content.
        val podH = ((podW.toFloat() * hDp / wDp).toInt() + 52).coerceAtLeast(120)
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            isClickable = true
            setOnClickListener { closeIslandPod() }
        }
        val card = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(24, 28, 36))
            elevation = 12f * dens
        }
        val podView = NativePodView(this, view, kind)
        podView.onAction = { actionId -> view.onNativePodAction(actionId) }
        card.addView(
            podView,
            FrameLayout.LayoutParams((podW * dens).toInt(), (podH * dens).toInt()),
        )
        scrim.addView(
            card,
            FrameLayout.LayoutParams(
                (podW * dens).toInt(), (podH * dens).toInt(), android.view.Gravity.CENTER,
            ),
        )
        rootFrame?.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        islandPod = scrim
        islandPodId = slotId
        islandPodWidgetId = null
        // The pod grows out of the island tile it came from.
        val tileRect = view.islands.firstOrNull { it.id == slotId }?.rect?.takeIf { !it.isEmpty }
        if (tileRect != null) {
            val vLoc = IntArray(2)
            view.getLocationOnScreen(vLoc)
            val tileCx = vLoc[0] + tileRect.centerX()
            val tileCy = vLoc[1] + tileRect.centerY()
            scrim.background?.alpha = 0
            card.post {
                if (islandPod !== scrim) return@post
                val cLoc = IntArray(2)
                card.getLocationOnScreen(cLoc)
                card.pivotX = card.width / 2f
                card.pivotY = card.height / 2f
                card.translationX = tileCx - (cLoc[0] + card.width / 2f)
                card.translationY = tileCy - (cLoc[1] + card.height / 2f)
                card.scaleX = (tileRect.width() / card.width).coerceIn(0.05f, 1f)
                card.scaleY = (tileRect.height() / card.height).coerceIn(0.05f, 1f)
                card.alpha = 0.35f
                card.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                    .alpha(1f).setDuration(260)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
                android.animation.ValueAnimator.ofInt(0, 190).apply {
                    duration = 260
                    addUpdateListener {
                        scrim.setBackgroundColor(Color.argb(it.animatedValue as Int, 0, 0, 0))
                    }
                    start()
                }
            }
        }
    }

    private fun closeIslandPod() {
        val scrim = islandPod ?: return
        val id = islandPodId
        val podWid = islandPodWidgetId
        islandPod = null
        islandPodId = null
        islandPodWidgetId = null
        // 3.2.2: the pod shrinks back into its island tile; teardown
        // (re-homing the live view into its bin) waits for the
        // animation, and only runs if the view is still in this pod —
        // a reopen in the meantime steals it and wins.
        val card = scrim.getChildAt(0) as? FrameLayout
        val tileRect = if (id != null)
            view.islands.firstOrNull { it.id == id }?.rect?.takeIf { !it.isEmpty } else null
        fun finishClose() {
            // 3.6.0: the slot id keys the tile; the pod's widget id
            // keys the hosted view (for a native pod podWid is null
            // and there is nothing to re-home).
            if (id != null && podWid != null) {
                val hv = islandViews[podWid]
                val bin = islandContainer
                if (hv != null && bin != null && card != null && hv.parent === card) {
                    card.removeView(hv)
                    val (baseW2, baseH2) = slotTileDp(id)
                    // 3.2.5: the resize scale survives the pod round-trip.
                    val sc = islandSlots[id]?.scale
                        ?: IslandLayout.clampScale(prefs.getFloat(islandKey(id, "scale"), 1f))
                    val wDp = (baseW2 * sc).toInt().coerceIn(108, 756)
                    val hDp = (baseH2 * sc).toInt().coerceIn(66, 540)
                    val dens = resources.displayMetrics.density
                    hv.layoutParams = FrameLayout.LayoutParams((wDp * dens).toInt(), (hDp * dens).toInt())
                    bin.addView(hv)
                    sendIslandSize(podWid, wDp, hDp)
                    hv.post { snapshotIsland(podWid) }
                }
            }
            (scrim.parent as? ViewGroup)?.removeView(scrim)
        }
        if (card != null && tileRect != null && card.width > 0) {
            val vLoc = IntArray(2)
            view.getLocationOnScreen(vLoc)
            val tileCx = vLoc[0] + tileRect.centerX()
            val tileCy = vLoc[1] + tileRect.centerY()
            val cLoc = IntArray(2)
            card.getLocationOnScreen(cLoc)
            card.pivotX = card.width / 2f
            card.pivotY = card.height / 2f
            card.animate()
                .translationX(tileCx - (cLoc[0] + card.width / 2f))
                .translationY(tileCy - (cLoc[1] + card.height / 2f))
                .scaleX((tileRect.width() / card.width).coerceIn(0.05f, 1f))
                .scaleY((tileRect.height() / card.height).coerceIn(0.05f, 1f))
                .alpha(0.3f)
                .setDuration(180)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction { finishClose() }
                .start()
            android.animation.ValueAnimator.ofInt(190, 0).apply {
                duration = 180
                addUpdateListener {
                    scrim.setBackgroundColor(Color.argb(it.animatedValue as Int, 0, 0, 0))
                }
                start()
            }
        } else {
            finishClose()
        }
    }

    private fun showIslandMenu(id: Int) {
        val members = islandMembersOf(id)
        val top = IslandMembers.clampTopIndex(
            members, prefs.getInt(islandKey(id, "top"), 0),
        )
        val topToken = members.getOrNull(top)
        val topKind = topToken?.let { IslandMembers.nativeKind(it) }
        val label = topToken?.let { memberLabel(it) } ?: "Widget"
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels += if (topKind != null) "Open built-in" else "Open live widget"
        actions += { openIslandPod(id) }
        labels += "Add to Stack\u2026"
        actions += {
            stackTargetSlot = id
            addTargetIsland = false
            showWidgetPicker()
        }
        if (members.size > 1 && topToken != null) {
            labels += "Remove \u201C${memberLabel(topToken)}\u201D from Stack"
            actions += { removeStackMember(id) }
        }
        labels += "Remove island"
        actions += { removeIsland(id) }
        AlertDialog.Builder(this)
            .setTitle(
                if (members.size > 1) "$label — stack of ${members.size}" else label,
            )
            .setItems(labels.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeIsland(id: Int) {
        if (islandPodId == id) closeIslandPod()
        // 3.6.0: every member goes — hosted views are torn down,
        // snapshots recycled, and each system member's appWidgetId
        // released. (Whole-island removal keeps its pre-stack
        // semantics; a face that still names those ids skips them
        // on load, as it always has. Stack-MEMBER removal is the
        // reference-checked path — see removeStackMember.)
        for (token in islandMembersOf(id)) {
            val wid = IslandMembers.sysId(token) ?: continue
            islandViews.remove(wid)?.let { hv ->
                (hv.parent as? ViewGroup)?.removeView(hv)
            }
            islandSlotByWidget.remove(wid)
            runCatching { appWidgetHost.deleteAppWidgetId(wid) }
        }
        islandSlots.remove(id)?.bitmaps?.values?.forEach { it.recycle() }
        saveIslandIds(savedIslandIds() - id)
        prefs.edit().remove(islandKey(id, "ax")).remove(islandKey(id, "ay"))
            .remove(islandKey(id, "scale")).remove(islandKey(id, "members"))
            .remove(islandKey(id, "top")).apply()
        pushIslandsToView()
        Toast.makeText(this, "Island removed", Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "WatchReborn"
        const val START_TAG = "OrbitalStart"
        const val REQ_STEPS = 37
        const val KEY_PRUNED_ONCE = "pruned_uninstalled_v375"
        const val APPWIDGET_HOST_ID = 2048

        // One AppWidgetHost for the whole process (1.19.1). The host
        // used to be a per-activity lazy, so a second MainActivity
        // instance (recreate, stacked HOME intent) built a second host
        // object: the service then delivered widget views to whichever
        // host had listened last, while the Widgets page registered
        // its views with another — and widgets rendered blank. A
        // single shared host keeps allocation, binding, callbacks and
        // hosted views on the one object.
        @Volatile
        private var sharedHost: AppWidgetHost? = null

        fun sharedWidgetHost(context: Context): AppWidgetHost =
            sharedHost ?: synchronized(this) {
                sharedHost ?: object : AppWidgetHost(context.applicationContext, APPWIDGET_HOST_ID) {
                    override fun onCreateView(
                        ctx: Context,
                        appWidgetId: Int,
                        appWidget: AppWidgetProviderInfo?,
                    ): AppWidgetHostView = IslandAwareHostView(ctx)
                }.also { sharedHost = it }
            }
        const val REQ_PICK_APPWIDGET = 401
        const val REQ_CREATE_APPWIDGET = 402
        const val REQ_BIND_APPWIDGET = 403
        const val REQ_BACKUP_LAYOUT = 404
        const val REQ_RESTORE_LAYOUT = 405
        const val KEY_ACCENT = "accent"
        const val KEY_FOLDERS = "auto_folders"
        const val KEY_BLOOM = "bloom_folders"
        const val KEY_FIXED_NEIGHBORHOODS = "fixed_neighborhoods"
        const val KEY_CLUSTER_TOPOLOGY = "cluster_topology"
        const val KEY_PER_LAYOUT_PINS = "per_layout_pins"
        const val KEY_USAGE_GROWTH = "usage_growth"
        const val KEY_LIVING_FIELD = "living_field"
        const val KEY_THEME_PACK = "theme_pack"
        const val KEY_THEMED_ICONS = "themed_icons"
        const val KEY_HINTS_SEEN = "hints_seen"
        const val KEY_APPWIDGET_IDS = "appwidget_ids"
        const val KEY_ISLAND_IDS = "island_ids"
        val ACCENTS = listOf(
            "Green" to Color.rgb(166, 227, 110),
            "Orange" to Color.rgb(255, 159, 10),
            "Blue" to Color.rgb(100, 210, 255),
            "Pink" to Color.rgb(255, 100, 160),
            "White" to Color.rgb(235, 235, 235),
        )
    }
}

/**
 * Host view that reports every RemoteViews update (3.2.0): island
 * snapshots refresh when a provider pushes new content.
 */
class IslandAwareHostView(context: Context) : AppWidgetHostView(context) {
    override fun updateAppWidget(remoteViews: RemoteViews?) {
        super.updateAppWidget(remoteViews)
        widgetUpdateHook?.invoke(appWidgetId)
    }

    companion object {
        @Volatile
        var widgetUpdateHook: ((Int) -> Unit)? = null
    }
}
