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
import android.content.pm.LauncherApps
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
import dev.watchreborn.apps.Folder
import dev.watchreborn.apps.GridItem
import dev.watchreborn.apps.WidgetBubble
import dev.watchreborn.apps.WidgetKind
import dev.watchreborn.ui.CardAction
import dev.watchreborn.ui.ClusterOption
import dev.watchreborn.ui.ClusterTopology
import dev.watchreborn.ui.FaceInfo
import dev.watchreborn.ui.Metrics
import dev.watchreborn.ui.WatchView
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity(), SensorEventListener {
    private lateinit var view: WatchView
    private lateinit var repo: AppRepo
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
    private var addTargetIsland = false
    private val islandSnapHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val islandSnapPending = mutableSetOf<Int>()
    private var rootFrame: FrameLayout? = null
    private val prefs by lazy { getSharedPreferences("watch", Context.MODE_PRIVATE) }
    private var apps: List<App> = emptyList()
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
        repo = AppRepo(this)
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
        view.accent = prefs.getInt(KEY_ACCENT, ACCENTS[0].second)
        view.autoFolders = prefs.getBoolean(KEY_FOLDERS, false) // 3.0.3: fresh installs start all-loose (Matt); a stored pref still wins
        view.bloomFolders = prefs.getBoolean(KEY_BLOOM, true)
        view.fixedNeighborhoods = prefs.getBoolean(KEY_FIXED_NEIGHBORHOODS, true)
        view.perLayoutPins = prefs.getBoolean(KEY_PER_LAYOUT_PINS, false)
        view.usageGrowth = prefs.getBoolean(KEY_USAGE_GROWTH, true)
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
        view.onWidgetsPage = { showWidgetsPage() }
        view.onWidgetsPageClosed = { hideWidgetsPage() }
        view.onIslandTap = { id -> openIslandPod(id) }
        view.onIslandMoved = { id, ax, ay ->
            prefs.edit().putFloat(islandKey(id, "ax"), ax).putFloat(islandKey(id, "ay"), ay).apply()
        }
        view.onIslandEditTap = { id -> showIslandMenu(id) }
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

        runCatching {
            sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        }

        launcherApps.registerCallback(appsChanged)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        loadIslands()
        reload()
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
        refreshInfo()
        if (apps.isNotEmpty()) pushAppsToView(apps)
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
            val steps = event.values.firstOrNull()?.toInt() ?: 0
            if (steps != view.stepCount) {
                view.stepCount = steps
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun reload() {
        val px = iconPx
        loader.execute {
            val loaded = runCatching { repo.load(px) }
                .onFailure { Log.e(TAG, "Loading apps failed", it) }
                .getOrNull() ?: return@execute
            view.post {
                apps = loaded
                pushAppsToView(loaded)
            }
        }
    }

    private fun pushAppsToView(list: List<App>) {
        // 2.9.0 one-time: pins were stored against grids that had the
        // clock at slot 0; shift them down one so they keep their spots.
        val wprefs = getSharedPreferences("watch", Context.MODE_PRIVATE)
        if (!wprefs.getBoolean("hub_centre_v290", false)) {
            repo.shiftPinsDownAfterHubRemoval()
            wprefs.edit().putBoolean("hub_centre_v290", true).apply()
        }
        val px = iconPx
        val fixedNeighborhoods = view.fixedNeighborhoods
        // 2.9.0: the centre slot belongs to the most-used app now — the
        // Core Clock hub is no longer placed in the grids (the builders
        // keep the parameter; the clock stays in the widget catalog).
        val coreClockHub = false
        val liveWidgets = false // 2.5.0: Live Grid Widgets setting removed — frozen at Matt's value (off); builders keep the parameter so it can return later
        loader.execute {
            val scope = pinScope()
            val rawUse = repo.byUse(list)
            val byUse = repo.applyPins(rawUse, scope)
            val folders = repo.buildFolderGrid(list, rawUse, px, fixedNeighborhoods, coreClockHub, liveWidgets, scope)
            val flatWithWidgets = repo.buildFlatGridWithWidgets(rawUse, px, coreClockHub, liveWidgets, scope)
            val pinnedKeys = HashSet<String>()
            list.forEach { if (repo.isPinned(it.key, scope)) pinnedKeys += it.key }
            folders.forEach { if (repo.isPinned(it.key, scope)) pinnedKeys += it.key }
            WidgetKind.entries.forEach { if (repo.isPinned(it.key, scope)) pinnedKeys += it.key }
            // Usage growth: diameter factors from the recency-decayed launch
            // scores; a folder grows with its most-used member.
            val maxScore = list.maxOfOrNull { repo.usageScore(it.key) } ?: 0f
            val factors = HashMap<String, Float>()
            list.forEach { factors[it.key] = dev.watchreborn.ui.UsageScale.factor(repo.usageScore(it.key), maxScore) }
            folders.filterIsInstance<Folder>().forEach { f ->
                val hottest = f.apps.maxOfOrNull { repo.usageScore(it.key) } ?: 0f
                factors[f.key] = dev.watchreborn.ui.UsageScale.factor(hottest, maxScore)
            }
            view.post {
                view.hiddenFolderCount = repo.hiddenGroups().size
                view.pinnedItemKeys = pinnedKeys
                val myUser = Process.myUserHandle()
                view.uninstallableKeys = list.asSequence()
                    .filter { !it.system && it.user == myUser }
                    .map { it.key }
                    .toHashSet()
                view.usageFactors = factors
                view.setApps(list, byUse, folders, flatWithWidgets)
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
            ClusterOption.BLOOM_MODE -> {
                val next = !view.bloomFolders
                prefs.edit().putBoolean(KEY_BLOOM, next).apply()
                view.bloomFolders = next
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
            ClusterOption.MORE_OPTIONS -> Unit // expand/collapse is view-local
            ClusterOption.CLOSE_SHEET -> Unit
        }
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
        for (id in savedIslandIds()) {
            val info = appWidgetManager.getAppWidgetInfo(id) ?: continue
            createIslandView(id, info)
        }
        pushIslandsToView()
    }

    private fun createIslandView(id: Int, info: AppWidgetProviderInfo) {
        val bin = islandContainer ?: return
        val hv = appWidgetHost.createView(this, id, info)
        val (wDp, hDp) = islandTileDp(info)
        val dens = resources.displayMetrics.density
        hv.layoutParams = FrameLayout.LayoutParams((wDp * dens).toInt(), (hDp * dens).toInt())
        bin.addView(hv)
        islandViews[id] = hv
        sendIslandSize(id, wDp, hDp)
        hv.post { snapshotIsland(id) }
    }

    private fun pushIslandsToView() {
        val hexPx = (resources.displayMetrics.widthPixels * Metrics.GRID_PITCH * 1.10f).coerceAtLeast(1f)
        val dens = resources.displayMetrics.density
        val slots = savedIslandIds().map { id ->
            islandSlots.getOrPut(id) {
                val info = appWidgetManager.getAppWidgetInfo(id)
                val (wDp, hDp) = if (info != null) islandTileDp(info) else (300 to 160)
                WatchView.IslandSlot(
                    id = id,
                    anchorX = prefs.getFloat(islandKey(id, "ax"), 0f),
                    anchorY = prefs.getFloat(islandKey(id, "ay"), 2.05f),
                    halfW = ((wDp * dens) / hexPx / 2f).coerceIn(0.75f, 1.60f),
                    halfH = ((hDp * dens) / hexPx / 2f).coerceIn(0.45f, 1.20f),
                    label = info?.loadLabel(packageManager)?.toString() ?: "Widget",
                )
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
        val slot = islandSlots[id] ?: return
        val w = hv.width
        val h = hv.height
        if (w <= 0 || h <= 0) {
            hv.post { snapshotIsland(id) }
            return
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        hv.draw(Canvas(bmp))
        val old = slot.bitmap
        slot.bitmap = bmp
        if (old != null && old !== bmp) old.recycle()
        if (view.clusterTopology == ClusterTopology.FORCE_FIELD) view.invalidate()
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
        if (info != null) createIslandView(widgetId, info)
        pushIslandsToView()
        hideWidgetPicker()
        if (view.clusterTopology != ClusterTopology.FORCE_FIELD) {
            prefs.edit().putString(KEY_CLUSTER_TOPOLOGY, ClusterTopology.FORCE_FIELD.name).apply()
            view.clusterTopology = ClusterTopology.FORCE_FIELD
        }
        view.invalidate()
        Toast.makeText(this, "Widget island added to the Force Field", Toast.LENGTH_LONG).show()
    }

    private fun openIslandPod(id: Int) {
        val hv = islandViews[id] ?: return
        val info = appWidgetManager.getAppWidgetInfo(id) ?: return
        closeIslandPod()
        val (wDp, hDp) = islandTileDp(info)
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
        sendIslandSize(id, podW, podH)
        scrim.addView(
            card,
            FrameLayout.LayoutParams((podW * dens).toInt(), (podH * dens).toInt(), android.view.Gravity.CENTER),
        )
        rootFrame?.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        islandPod = scrim
        islandPodId = id
    }

    private fun closeIslandPod() {
        val scrim = islandPod ?: return
        val id = islandPodId
        islandPod = null
        islandPodId = null
        if (id != null) {
            val hv = islandViews[id]
            val bin = islandContainer
            if (hv != null && bin != null) {
                (hv.parent as? ViewGroup)?.removeView(hv)
                val info = appWidgetManager.getAppWidgetInfo(id)
                val (wDp, hDp) = if (info != null) islandTileDp(info) else (300 to 160)
                val dens = resources.displayMetrics.density
                hv.layoutParams = FrameLayout.LayoutParams((wDp * dens).toInt(), (hDp * dens).toInt())
                bin.addView(hv)
                sendIslandSize(id, wDp, hDp)
                hv.post { snapshotIsland(id) }
            }
        }
        (scrim.parent as? ViewGroup)?.removeView(scrim)
    }

    private fun showIslandMenu(id: Int) {
        val info = appWidgetManager.getAppWidgetInfo(id)
        val label = info?.loadLabel(packageManager)?.toString() ?: "Widget"
        AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(arrayOf("Open live widget", "Remove island")) { _, which ->
                when (which) {
                    0 -> openIslandPod(id)
                    1 -> removeIsland(id)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeIsland(id: Int) {
        if (islandPodId == id) closeIslandPod()
        islandViews.remove(id)?.let { hv -> (hv.parent as? ViewGroup)?.removeView(hv) }
        islandSlots.remove(id)?.bitmap?.recycle()
        runCatching { appWidgetHost.deleteAppWidgetId(id) }
        saveIslandIds(savedIslandIds() - id)
        prefs.edit().remove(islandKey(id, "ax")).remove(islandKey(id, "ay")).apply()
        pushIslandsToView()
        Toast.makeText(this, "Island removed", Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "WatchReborn"
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
        const val KEY_ACCENT = "accent"
        const val KEY_FOLDERS = "auto_folders"
        const val KEY_BLOOM = "bloom_folders"
        const val KEY_FIXED_NEIGHBORHOODS = "fixed_neighborhoods"
        const val KEY_CLUSTER_TOPOLOGY = "cluster_topology"
        const val KEY_PER_LAYOUT_PINS = "per_layout_pins"
        const val KEY_USAGE_GROWTH = "usage_growth"
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
