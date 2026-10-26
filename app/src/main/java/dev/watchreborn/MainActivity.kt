package dev.watchreborn

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.Dialog
import android.app.role.RoleManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothManager
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
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.StatFs
import android.os.UserHandle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.EditText
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
import dev.watchreborn.ui.ControlAction
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
    private val cameraManager by lazy { getSystemService(CameraManager::class.java) }
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private val appWidgetManager by lazy { AppWidgetManager.getInstance(this) }
    private val appWidgetHost by lazy { AppWidgetHost(this, APPWIDGET_HOST_ID) }
    private var widgetsDialog: Dialog? = null
    private val prefs by lazy { getSharedPreferences("watch", Context.MODE_PRIVATE) }
    private var apps: List<App> = emptyList()
    private var battery = -1
    private var charging = false
    private var torchCameraId: String? = null
    private var torchOn = false
    private var screenFlashlight = false

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

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCameraId) {
                torchOn = enabled
                syncControlState()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = AppRepo(this)
        view = WatchView(
            context = this,
            onLaunch = ::launch,
            onLongPressItem = ::itemOptions,
            onLongPressFace = ::faceOptions,
            onControlAction = ::handleControlAction,
            onCardAction = ::handleCardAction,
            onTogglePinCard = ::togglePinCard,
            onClusterOptionChanged = ::handleClusterOptionChanged,
            onTopologySelected = ::handleTopologySelected,
            onPlaceItem = ::placeItem,
            onFileIntoFolder = ::fileIntoFolder,
            onCreateFolder = ::createFolder,
            onRenameFolder = ::renameFolder,
            onDeleteFolder = ::deleteFolder,
            onUnpinItem = ::unpinItem,
            onReorderFolderMember = ::reorderFolderMember,
            onQuickInfo = ::quickAppInfo,
            onQuickUninstall = ::quickUninstall,
        )
        view.accent = prefs.getInt(KEY_ACCENT, ACCENTS[0].second)
        view.preferList = prefs.getBoolean(KEY_LIST, false)
        view.autoFolders = prefs.getBoolean(KEY_FOLDERS, true)
        view.honeycombHome = prefs.getBoolean(KEY_HONEYCOMB_HOME, true)
        view.bloomFolders = prefs.getBoolean(KEY_BLOOM, true)
        view.quickSnipe = prefs.getBoolean(KEY_QUICK_SNIPE, false)
        view.liveWidgets = prefs.getBoolean(KEY_LIVE_WIDGETS, true)
        view.fixedNeighborhoods = prefs.getBoolean(KEY_FIXED_NEIGHBORHOODS, true)
        view.bubblePhysics = prefs.getBoolean(KEY_BUBBLE_PHYSICS, true)
        view.perLayoutPins = prefs.getBoolean(KEY_PER_LAYOUT_PINS, false)
        view.usageGrowth = prefs.getBoolean(KEY_USAGE_GROWTH, true)
        val savedTopo = prefs.getString(KEY_CLUSTER_TOPOLOGY, ClusterTopology.HONEYCOMB.name)
        view.clusterTopology = runCatching { ClusterTopology.valueOf(savedTopo ?: ClusterTopology.HONEYCOMB.name) }
            .getOrDefault(ClusterTopology.HONEYCOMB)
        view.pinnedCardIds = prefs.getStringSet(KEY_PINNED_CARDS, emptySet()).orEmpty()
        view.hintsSeen = prefs.getStringSet(KEY_HINTS_SEEN, emptySet()).orEmpty()
        view.onHintSeen = { id ->
            val cur = prefs.getStringSet(KEY_HINTS_SEEN, emptySet()).orEmpty().toMutableSet()
            cur += id
            prefs.edit().putStringSet(KEY_HINTS_SEEN, cur).apply()
            view.hintsSeen = cur
        }
        view.preferListChanged = { prefs.edit().putBoolean(KEY_LIST, it).apply() }
        view.applyInitialHomeMode()
        view.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.topInset = bars.top.toFloat()
            view.bottomInset = bars.bottom.toFloat()
            v.invalidate()
            insets
        }
        setContentView(view)
        window.setDecorFitsSystemWindows(false)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            handleBack()
        }

        val artPx = (96 * resources.displayMetrics.density).toInt()
        nowPlaying = NowPlaying(this, artPx) {
            view.nowPlayingTrack = nowPlaying.track
            view.refreshCards()
        }
        nowPlaying.start()

        findTorchCamera()
        runCatching { cameraManager.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper())) }
        runCatching {
            sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        }

        launcherApps.registerCallback(appsChanged)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
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
        refreshInfo()
        syncControlState()
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
        if (Intent.ACTION_MAIN == intent.action && hasWindowFocus()) view.home()
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
        runCatching { cameraManager.unregisterTorchCallback(torchCallback) }
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
                view.refreshCards()
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
        val px = iconPx
        val fixedNeighborhoods = view.fixedNeighborhoods
        val coreClockHub = view.honeycombHome
        val liveWidgets = view.liveWidgets
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
        view.refreshCards()
    }

    private fun findTorchCamera() {
        torchCameraId = runCatching {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
    }

    private fun syncControlState() {
        val wifiOn = runCatching {
            applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
        }.getOrDefault(false)
        val btOn = runCatching {
            getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        }.getOrDefault(false)
        val ringer = runCatching { audioManager.ringerMode }.getOrDefault(AudioManager.RINGER_MODE_NORMAL)
        val maxVol = runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }.getOrDefault(15)
        val curVol = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(7)

        view.controlCenter.state = view.controlCenter.state.copy(
            wifiOn = wifiOn,
            bluetoothOn = btOn,
            torchOn = torchOn,
            screenFlashlight = screenFlashlight,
            ringerMode = ringer,
            autoFolders = view.autoFolders,
            notifCount = NowPlayingListener.activePackages.size,
            volume = (curVol.toFloat() / maxVol).coerceIn(0f, 1f),
        )
        view.invalidate()
    }

    private fun handleTopologySelected(topo: ClusterTopology) {
        prefs.edit().putString(KEY_CLUSTER_TOPOLOGY, topo.name).apply()
    }

    private fun handleClusterOptionChanged(opt: ClusterOption) {
        when (opt) {
            ClusterOption.CLUSTER_TOPOLOGY -> {
                // Handled inside WatchView: the sheet row opens the topology picker
                // and selections persist via handleTopologySelected.
            }
            ClusterOption.BUBBLE_PHYSICS -> {
                val next = !view.bubblePhysics
                prefs.edit().putBoolean(KEY_BUBBLE_PHYSICS, next).apply()
                view.bubblePhysics = next
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
            ClusterOption.EDIT_LAYOUT -> {
                // Handled inside WatchView: the sheet row enters edit mode directly.
            }
            ClusterOption.HONEYCOMB_HOME -> {
                val next = !view.honeycombHome
                prefs.edit().putBoolean(KEY_HONEYCOMB_HOME, next).apply()
                view.honeycombHome = next
                if (apps.isNotEmpty()) pushAppsToView(apps)
            }
            ClusterOption.BLOOM_MODE -> {
                val next = !view.bloomFolders
                prefs.edit().putBoolean(KEY_BLOOM, next).apply()
                view.bloomFolders = next
            }
            ClusterOption.QUICK_SNIPE -> {
                val next = !view.quickSnipe
                prefs.edit().putBoolean(KEY_QUICK_SNIPE, next).apply()
                view.quickSnipe = next
            }
            ClusterOption.CONSTELLATION_SEARCH -> {
                view.startConstellationSearch()
            }
            ClusterOption.LIVE_WIDGETS -> {
                val next = !view.liveWidgets
                prefs.edit().putBoolean(KEY_LIVE_WIDGETS, next).apply()
                view.liveWidgets = next
                if (apps.isNotEmpty()) pushAppsToView(apps)
            }
            ClusterOption.SYSTEM_WIDGETS -> {
                openSystemWidgetsPortal()
            }
            ClusterOption.FIXED_NEIGHBORHOODS -> {
                val next = !view.fixedNeighborhoods
                prefs.edit().putBoolean(KEY_FIXED_NEIGHBORHOODS, next).apply()
                view.fixedNeighborhoods = next
                if (apps.isNotEmpty()) pushAppsToView(apps)
            }
            ClusterOption.AUTO_FOLDERS -> {
                handleControlAction(ControlAction.FOLDERS, null)
            }
            ClusterOption.SET_DEFAULT_HOME -> {
                chooseHome()
            }
            ClusterOption.CLEAR_PINS -> {
                repo.clearAllPins()
                if (apps.isNotEmpty()) pushAppsToView(apps)
                Toast.makeText(this, "Cleared all pinned hex slots", Toast.LENGTH_SHORT).show()
            }
            ClusterOption.CLOSE_SHEET -> Unit
        }
    }

    private fun handleControlAction(action: ControlAction, value: Float?) {
        when (action) {
            ControlAction.WIFI -> {
                val panel = Intent(Settings.Panel.ACTION_WIFI)
                if (runCatching { startActivity(panel) }.isFailure) {
                    runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                }
            }
            ControlAction.BLUETOOTH -> {
                runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            }
            ControlAction.TORCH -> {
                if (screenFlashlight) {
                    screenFlashlight = false
                    setWindowBrightness(-1f)
                    syncControlState()
                    return
                }
                val camId = torchCameraId
                if (camId != null && runCatching { cameraManager.setTorchMode(camId, !torchOn) }.isSuccess) {
                    return
                }
                // Fallback to bright watchOS screen-flashlight overlay
                screenFlashlight = !screenFlashlight
                setWindowBrightness(if (screenFlashlight) 1f else -1f)
                syncControlState()
            }
            ControlAction.RINGER -> {
                val nextMode = if (audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                    AudioManager.RINGER_MODE_VIBRATE
                } else {
                    AudioManager.RINGER_MODE_NORMAL
                }
                if (runCatching { audioManager.ringerMode = nextMode }.isFailure) {
                    runCatching { startActivity(Intent(Settings.Panel.ACTION_VOLUME)) }
                }
                syncControlState()
            }
            ControlAction.FOLDERS -> {
                val next = !view.autoFolders
                prefs.edit().putBoolean(KEY_FOLDERS, next).apply()
                view.autoFolders = next
                syncControlState()
            }
            ControlAction.LOCK -> {
                if (!TurnOffService.perform(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) {
                    Toast.makeText(this, "Enable Watch Reborn in Accessibility to lock screen", Toast.LENGTH_SHORT).show()
                    runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }
            }
            ControlAction.NOTIFICATIONS -> {
                view.toFace()
                expandNotifications()
            }
            ControlAction.SET_HOME -> {
                chooseHome()
            }
            ControlAction.VOLUME_DOWN -> {
                runCatching { audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0) }
                syncControlState()
            }
            ControlAction.VOLUME_UP -> {
                if (value != null) {
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    val target = (value * maxVol).roundToInt().coerceIn(0, maxVol)
                    runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
                } else {
                    runCatching { audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0) }
                }
                syncControlState()
            }
        }
    }

    private fun setWindowBrightness(brightness: Float) {
        val lp = window.attributes
        lp.screenBrightness = brightness
        window.attributes = lp
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

    private fun togglePinCard(cardId: String) {
        val current = prefs.getStringSet(KEY_PINNED_CARDS, emptySet()).orEmpty().toMutableSet()
        val pinned = if (cardId in current) { current.remove(cardId); false } else { current.add(cardId); true }
        prefs.edit().putStringSet(KEY_PINNED_CARDS, current).apply()
        view.pinnedCardIds = current
        view.refreshCards()
        Toast.makeText(this, if (pinned) "Card pinned to top" else "Card unpinned", Toast.LENGTH_SHORT).show()
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
                        menu.menu.add(1, 320, 5, "⌚ Open Full Watch Face")
                        menu.menu.add(1, 321, 6, "📊 Open Smart Stack")
                        menu.menu.add(1, 322, 7, "🎛️ Open Control Center")
                    }
                    WidgetKind.NOW_PLAYING -> {
                        menu.menu.add(1, 330, 5, "⏯ Play / Pause")
                        menu.menu.add(1, 331, 6, "⏭ Next Track")
                        menu.menu.add(1, 332, 7, "⏮ Previous Track")
                        menu.menu.add(1, 333, 8, "🎵 Open Media App")
                    }
                    WidgetKind.CONTROLS -> {
                        menu.menu.add(1, 322, 5, "🎛️ Open Control Center")
                    }
                    WidgetKind.VITALS -> {
                        menu.menu.add(1, 321, 5, "📊 Open Smart Stack")
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
                menu.menu.add(2, 311, 15, "⚙ Honeycomb Cluster Options...")
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
                        320 -> view.toFace()
                        321 -> view.openStack()
                        322 -> view.openControlCenter()
                        330 -> handleCardAction(CardAction.MEDIA_PLAY_PAUSE)
                        331 -> handleCardAction(CardAction.MEDIA_NEXT)
                        332 -> handleCardAction(CardAction.MEDIA_PREV)
                        333 -> handleCardAction(CardAction.MEDIA_OPEN)
                        340 -> handleCardAction(CardAction.OPEN_BATTERY)
                        341 -> handleCardAction(CardAction.OPEN_ALARMS)
                        342 -> handleCardAction(CardAction.OPEN_CALENDAR)
                        345 -> openSystemWidgetsPortal()
                        311 -> view.openClusterSheet()
                        312 -> chooseHome()
                    }
                    true
                }
            }
            is Folder -> {
                menu.menu.add(1, 310, 5, "Open ${item.label} (${item.apps.size} apps)")
                menu.menu.add(1, 311, 6, "⚙ Honeycomb Cluster Options...")
                menu.menu.add(1, 312, 7, "🏠 Set as Default Launcher")
                menu.menu.add(1, 313, 8, "✥ Edit Layout Mode")
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
                        313 -> view.enterEditMode()
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

                menu.menu.add(3, 311, 25, "⚙ Honeycomb Cluster Options...")
                menu.menu.add(3, 313, 26, "✥ Edit Layout Mode")
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
                            val fid = f?.customId
                            if (f != null && fid != null) {
                                repo.removeFromCustomFolder(fid, item.key)
                                pushAppsToView(apps)
                                view.back()
                                Toast.makeText(this, "${item.label} removed from ${f.label}", Toast.LENGTH_SHORT).show()
                            }
                        }
                        311 -> view.openClusterSheet()
                        313 -> view.enterEditMode()
                        1 -> runCatching { launcherApps.startAppDetailsActivity(item.component, item.user, null, null) }
                        2 -> runCatching { startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", item.component.packageName, null))) }
                    }
                    true
                }
            }
        }
        menu.show()
    }

    private fun faceOptions() {
        val menu = PopupMenu(this, view, Gravity.CENTER)
        menu.menu.add(0, 10, 0, "Open Control Center")
        menu.menu.add(0, 13, 1, "⚙ Honeycomb Cluster Options...")
        menu.menu.add(0, 11, 2, "Auto Folders: ${if (view.autoFolders) "On" else "Off"}")
        if (!nowPlaying.allowed) {
            menu.menu.add(0, 12, 3, "Enable Now Playing & Badges")
        }
        ACCENTS.forEachIndexed { i, (name, _) -> menu.menu.add(1, 100 + i, 10 + i, "Colour: $name") }
        menu.menu.add(2, 2, 50, "Choose Home App")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                10 -> view.openControlCenter()
                13 -> {
                    view.openApps()
                    view.openClusterSheet()
                }
                11 -> handleControlAction(ControlAction.FOLDERS, null)
                12 -> runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                2 -> chooseHome()
                in 100 until 100 + ACCENTS.size -> {
                    val c = ACCENTS[item.itemId - 100].second
                    prefs.edit().putInt(KEY_ACCENT, c).apply()
                    view.accent = c
                }
            }
            true
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
    private fun expandNotifications() {
        runCatching {
            val sb = getSystemService("statusbar")
            sb.javaClass.getMethod("expandNotificationsPanel").invoke(sb)
        }.onFailure { Log.w(TAG, "Can't open notifications", it) }
    }

    private fun savedWidgetIds(): List<Int> {
        return prefs.getStringSet(KEY_APPWIDGET_IDS, emptySet()).orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .sorted()
    }

    private fun saveWidgetIds(ids: List<Int>) {
        prefs.edit().putStringSet(KEY_APPWIDGET_IDS, ids.map { it.toString() }.toSet()).apply()
    }

    private fun dpPx(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun openSystemWidgetsPortal() {
        widgetsDialog?.dismiss()
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpPx(18f), dpPx(20f), dpPx(18f), dpPx(18f))
            background = GradientDrawable().apply {
                setColor(Color.rgb(22, 24, 30))
                cornerRadius = dpPx(26f).toFloat()
                setStroke(dpPx(1.5f), view.accent)
            }
        }

        val title = TextView(this).apply {
            text = "🧩 Android System Widgets"
            textSize = 17f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(title)

        val sub = TextView(this).apply {
            text = "Host real interactive Android widgets inside Watch Reborn"
            textSize = 11.5f
            setTextColor(Color.rgb(180, 185, 195))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dpPx(4f), 0, dpPx(12f))
        }
        root.addView(sub)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.56f).toInt(),
            )
        }
        val listCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listCol)
        root.addView(scroll)

        fun populateWidgets() {
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
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dpPx(12f), dpPx(10f), dpPx(12f), dpPx(12f))
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(34, 36, 44))
                        cornerRadius = dpPx(18f).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dpPx(10f) }
                }

                val hdr = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val lbl = TextView(this).apply {
                    text = info.loadLabel(packageManager) ?: "App Widget"
                    textSize = 12.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(view.accent)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val rem = TextView(this).apply {
                    text = "✕ Remove"
                    textSize = 11f
                    setTextColor(Color.rgb(255, 100, 100))
                    setPadding(dpPx(8f), dpPx(4f), dpPx(8f), dpPx(4f))
                    setOnClickListener {
                        runCatching { appWidgetHost.deleteAppWidgetId(id) }
                        saveWidgetIds(savedWidgetIds().filter { it != id })
                        populateWidgets()
                    }
                }
                hdr.addView(lbl)
                hdr.addView(rem)
                card.addView(hdr)

                runCatching {
                    val hv = appWidgetHost.createView(applicationContext, id, info)
                    hv.setAppWidget(id, info)
                    val minH = info.minHeight.coerceAtLeast(dpPx(110f))
                    card.addView(
                        hv,
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, minH).apply {
                            topMargin = dpPx(6f)
                        },
                    )
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
                    text = "No Android system widgets added yet.\n\nTap '+ Add Android Widget' below to pick any Clock, Weather, Calendar, Music, or third-party widget installed on your phone!"
                    textSize = 12.5f
                    setTextColor(Color.rgb(200, 205, 215))
                    gravity = Gravity.CENTER
                    setPadding(dpPx(16f), dpPx(36f), dpPx(16f), dpPx(36f))
                }
                listCol.addView(empty)
            }
        }

        populateWidgets()

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dpPx(12f), 0, 0)
        }

        val addBtn = TextView(this).apply {
            text = "＋ Add Android Widget"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(dpPx(18f), dpPx(10f), dpPx(18f), dpPx(10f))
            background = GradientDrawable().apply {
                setColor(view.accent)
                cornerRadius = dpPx(20f).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dpPx(8f)
            }
            setOnClickListener {
                launchWidgetPicker()
            }
        }

        val closeBtn = TextView(this).apply {
            text = "Done"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dpPx(18f), dpPx(10f), dpPx(18f), dpPx(10f))
            background = GradientDrawable().apply {
                setColor(Color.rgb(52, 54, 62))
                cornerRadius = dpPx(20f).toFloat()
            }
            setOnClickListener { d.dismiss() }
        }

        btnRow.addView(addBtn)
        btnRow.addView(closeBtn)
        root.addView(btnRow)

        d.setContentView(root)
        d.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        widgetsDialog = d
        d.show()
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
        }
    }

    private fun finishAddWidget(widgetId: Int) {
        val updated = (savedWidgetIds() + widgetId).distinct()
        saveWidgetIds(updated)
        openSystemWidgetsPortal()
    }

    private companion object {
        const val TAG = "WatchReborn"
        const val APPWIDGET_HOST_ID = 2048
        const val REQ_PICK_APPWIDGET = 401
        const val REQ_CREATE_APPWIDGET = 402
        const val KEY_ACCENT = "accent"
        const val KEY_LIST = "list"
        const val KEY_FOLDERS = "auto_folders"
        const val KEY_HONEYCOMB_HOME = "honeycomb_home"
        const val KEY_BLOOM = "bloom_folders"
        const val KEY_QUICK_SNIPE = "quick_snipe_opt"
        const val KEY_LIVE_WIDGETS = "live_widgets"
        const val KEY_FIXED_NEIGHBORHOODS = "fixed_neighborhoods"
        const val KEY_CLUSTER_TOPOLOGY = "cluster_topology"
        const val KEY_BUBBLE_PHYSICS = "bubble_physics"
        const val KEY_PER_LAYOUT_PINS = "per_layout_pins"
        const val KEY_USAGE_GROWTH = "usage_growth"
        const val KEY_PINNED_CARDS = "pinned_cards"
        const val KEY_HINTS_SEEN = "hints_seen"
        const val KEY_APPWIDGET_IDS = "appwidget_ids"
        val ACCENTS = listOf(
            "Green" to Color.rgb(166, 227, 110),
            "Orange" to Color.rgb(255, 159, 10),
            "Blue" to Color.rgb(100, 210, 255),
            "Pink" to Color.rgb(255, 100, 160),
            "White" to Color.rgb(235, 235, 235),
        )
    }
}
