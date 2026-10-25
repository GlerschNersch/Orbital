package dev.watchreborn.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.os.SystemClock
import android.text.InputType
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import dev.watchreborn.NowPlaying
import dev.watchreborn.NowPlayingListener
import dev.watchreborn.apps.App
import dev.watchreborn.apps.Folder
import dev.watchreborn.apps.GridItem
import dev.watchreborn.apps.WidgetBubble
import dev.watchreborn.apps.WidgetKind
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class ClusterTopology(val shortTitle: String, val fullTitle: String, val subtitle: String) {
    HONEYCOMB("⬡ Hex", "⬡ Classic Honeycomb", "6-Axis Hexagonal Fisheye Cluster"),
    ORBITALS("🪐 Orbit", "🪐 Planetary Orbitals", "Concentric Astrolabe Rings (Spin to Rotate)"),
    RIVER("🌊 River", "🌊 Vertical River", "watchOS 10 Vertical 2–3–2 Hex Ribbon"),
    FIBONACCI("🌻 Spiral", "🌻 Fibonacci Spiral", "137.5° Golden-Angle Sunflower Cluster"),
    CHRONO_BEZEL("🎡 Bezel", "🎡 Chrono Bezel", "Live Center Watch HUD + Rotating Ring"),
    NEBULA("🌌 Nebula", "🌌 Nebula Islands", "Expanded Category Galaxy Islands in Space"),
    GRAVITY("🫧 Gravity", "🫧 Gravity Sizes", "Usage-Weighted Hero & Satellite Bubbles"),
    DNA_HELIX("🧬 Helix", "🧬 Double Helix", "3D Rotating Twin-Strand DNA Ribbon"),
    HOURGLASS("⏳ Vortex", "⏳ Chrono Hourglass", "Upper & Lower Funnels Around Live Core"),
    MAGNETIC_DOCK("🧲 Arch", "🧲 Thumb Arc Dock", "One-Handed Bottom Ergonomic Concentric Arcs"),
    LIQUID_POOL("🫧 Pool", "🫧 Zero-G Billiards", "Free-Floating Tilt & Collision Bubble Tank"),
}

enum class ClusterOption {
    CLUSTER_TOPOLOGY,
    BUBBLE_PHYSICS,
    HONEYCOMB_HOME,
    BLOOM_MODE,
    QUICK_SNIPE,
    CONSTELLATION_SEARCH,
    LIVE_WIDGETS,
    SYSTEM_WIDGETS,
    FIXED_NEIGHBORHOODS,
    AUTO_FOLDERS,
    SET_DEFAULT_HOME,
    CLEAR_PINS,
    CLOSE_SHEET,
}

/**
 * The whole launcher on one canvas:
 * - Watch Face (with interactive top & bottom complications)
 * - App Views: Focus Cluster, Full Honeycomb Grid (with #1 Core Clock Hub Home, #2 Blooming Clusters,
 *   #3 Quick-Snipe Mini-Icons, #4 Constellation Search, #5 Live Media/Control/Vitals Widget Bubbles,
 *   #6 Fixed Compass Neighborhoods & Hex Slot Pinning, Pinch Overview, and Edge Digital Crown zoom),
 *   and List View (with live Search bar and A-Z Edge Scrubber)
 * - Smart Stack (Now Playing media controls, Activity/System rings, Alarm, Battery, Recent Apps, card pinning)
 * - Control Center (Wi-Fi, Bluetooth, Flashlight, Sound/Vibrate, Auto Folders, Lock Screen, Volume slider, Notifications)
 */
@SuppressLint("ViewConstructor")
class WatchView(
    context: Context,
    private val onLaunch: (App, RectF) -> Unit,
    private val onLongPressItem: (GridItem, Int) -> Unit,
    private val onLongPressFace: () -> Unit,
    private val onControlAction: (ControlAction, Float?) -> Unit,
    private val onCardAction: (CardAction) -> Unit,
    private val onTogglePinCard: (String) -> Unit,
    private val onClusterOptionChanged: (ClusterOption) -> Unit,
    private val onTopologySelected: (ClusterTopology) -> Unit,
) : View(context) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    val face = Face()
    val stack = Stack()
    val controlCenter = ControlCenter()
    var info = FaceInfo()
    var nowPlayingTrack: NowPlaying.Track? = null
    var ramUsedRatio: Float = 0.5f
    var storageUsedRatio: Float = 0.4f
    var stepCount: Int = 0
    var pinnedCardIds: Set<String> = emptySet()
    var pinnedItemKeys: Set<String> = emptySet()
        set(v) {
            val grew = v.size > field.size
            field = v
            // Pinning something proves the long-press gesture is known.
            if (grew) markHintSeen(HINT_PIN)
        }

    /** One-time coach hints: ids already seen (persisted by the activity). */
    var hintsSeen: Set<String> = emptySet()
    var onHintSeen: (String) -> Unit = {}
    private val coachHintRect = RectF()
    private var coachHintId: String? = null

    /** Apps A-Z (List view), by use (grid and cluster), folder-grouped grid items, and flat grid with widgets. */
    private var alphabetical: List<App> = emptyList()
    private var byUse: List<App> = emptyList()
    private var folderItems: List<GridItem> = emptyList()
    private var flatWidgetItems: List<GridItem> = emptyList()
    private var gridHexes: List<Hex> = emptyList()

    /** Active open folder or blooming widget pod in the honeycomb, if any, and its anchor index on the main grid. */
    private var activeFolder: Folder? = null
    private var activeWidget: WidgetBubble? = null
    private var bloomAnchorIndex: Int = -1
    private var folderHexes: List<Hex> = emptyList()

    /** Live Quick Timer state for WidgetKind.TIMER (#5). */
    private var timerTotalSec: Int = 300
    private var timerRemainingMs: Long = 300_000L
    private var timerRunning: Boolean = false
    private var timerEndUptimeMs: Long = 0L

    var accent: Int = Color.rgb(166, 227, 110)
        set(v) {
            field = v
            face.accent = v
            stack.accent = v
            controlCenter.accent = v
            invalidate()
        }

    var autoFolders: Boolean = true
        set(v) {
            field = v
            controlCenter.state = controlCenter.state.copy(autoFolders = v)
            rebuildGridHexes()
            invalidate()
        }

    /** #1: True = Honeycomb Grid is the primary home screen with a live Clock & Status Core Bubble at (0, 0). */
    var honeycombHome: Boolean = true
        set(v) { field = v; invalidate() }

    /** #2: True = Folders bloom open in-place with a radial shockwave; False = separate full-screen folder view. */
    var bloomFolders: Boolean = true
        set(v) { field = v; invalidate() }

    /** #3: True = Tapping the 6 outer mini-icons inside a centered folder launches that app directly; False = any tap opens folder. */
    var quickSnipe: Boolean = false
        set(v) { field = v; invalidate() }

    /** #5: True = Places live Media, Control Cluster, and Vitals/Rings bubbles directly on the Honeycomb Grid. */
    var liveWidgets: Boolean = true
        set(v) { field = v; invalidate() }

    /** #6: True = Fixed Compass Neighborhoods layout; False = Dynamic Usage Spiral layout. */
    var fixedNeighborhoods: Boolean = true
        set(v) { field = v; invalidate() }

    /** Active bubble cluster geometry / topology. */
    var clusterTopology: ClusterTopology = ClusterTopology.HONEYCOMB
        set(v) {
            field = v
            panX.target = 0f
            panY.target = 0f
            overview.target = 0f
            userZoom.target = 1f
            if (v == ClusterTopology.LIQUID_POOL) {
                resetPoolSimulation()
            }
            invalidate()
        }

    /** True = Elastic Jello / Wake Wave / Rotary Spin Momentum / Tilt Parallax / Billiards Physics enabled. */
    var bubblePhysics: Boolean = true
        set(v) { field = v; invalidate() }

    /** Rotary angle (radians) & angular momentum (rad/s) for Orbitals, Chrono Bezel, Spiral, and Double Helix. */
    private var orbitalAngle: Float = 0f
    private var orbitalVelocity: Float = 0f
    private var lastSpinTickStep: Int = 0

    /** Live device tilt vector (-1f..1f) from accelerometer/gravity sensor for parallax & zero-G pool gravity. */
    private var tiltX: Float = 0f
    private var tiltY: Float = 0f
    private var targetTiltX: Float = 0f
    private var targetTiltY: Float = 0f

    /** Per-bubble springy wake-displacement & velocity (x, y) for Jello / Wake Wave physics across all topologies. */
    private var wakeDispX = FloatArray(0)
    private var wakeDispY = FloatArray(0)
    private var wakeVelX = FloatArray(0)
    private var wakeVelY = FloatArray(0)
    private var lastPanXForWake = 0f
    private var lastPanYForWake = 0f
    private var wakeInitialized = false

    /** Expandable shockwave ripple ring triggered by double-tap or shake. */
    private var shockwaveOriginX = 0f
    private var shockwaveOriginY = 0f
    private var shockwaveProgress = 1f // 0..1, 1 = inactive

    /** Zero-G Billiards Pool (`LIQUID_POOL`) free-body positions & velocities in world coordinates. */
    private var poolPosX = FloatArray(0)
    private var poolPosY = FloatArray(0)
    private var poolVelX = FloatArray(0)
    private var poolVelY = FloatArray(0)
    private var poolInitialized = false
    private var grabbedPoolIndex: Int = -1

    /** 0 = face, 1 = apps. */
    private val apps = Spring(0f)
    /** 0 = focus cluster, 1 = full grid. */
    private val grid = Spring(0f)
    /** 0 = normal full grid, 1 = zoomed-out overview of all hexes (CSLOverviewHexLayout). */
    private val overview = Spring(0f)
    /** Continuous user pinch/crown zoom factor on the Honeycomb Grid (0.42f = Overview .. 1.0f = Normal .. 1.35f = Magnified). */
    private val userZoom = Spring(1f, 0.32f)
    /** Continuous user pinch zoom factor inside the Unraveled Constellation (0.75f .. 1.35f). */
    private val constellationZoom = Spring(1f, 0.32f)
    /** 0 = main grid, 1 = inside [activeFolder] (either blooming in-place or full screen). */
    private val folderIn = Spring(0f)
    /** 0 = main grid, 1 = inside [activeWidget] blooming widget pod. */
    private val widgetIn = Spring(0f)
    /** 0 = closed, 1 = Cluster Mode selector sheet open. */
    private val clusterSheet = Spring(0f)
    /** 0 = coiled in folders, 1 = unraveled into full-screen Star Constellation (#4). */
    private val constellationIn = Spring(0f)
    /** 0 = grid, 1 = list. */
    private val list = Spring(0f)
    /** 0 = face, 1 = Smart Stack. */
    private val stackIn = Spring(0f)
    /** 0 = face, 1 = Control Center. */
    private val controlIn = Spring(0f)

    private val panX = Spring(0f, 0.35f)
    private val panY = Spring(0f, 0.35f)
    private val folderPanX = Spring(0f, 0.35f)
    private val folderPanY = Spring(0f, 0.35f)
    private val constellationPanX = Spring(0f, 0.35f)
    private val constellationPanY = Spring(0f, 0.35f)
    private val stackScroll = Spring(0f, 0.3f)
    private var listScroll = 0f
    private var listVelocity = 0f

    /** Live search filter (used by both List View search and #4 Honeycomb Constellation Search). */
    var searchQuery: String = ""
        private set
    private var searching = false
    private var gridSearching = false
    private var constellationFilter = "ALL"
    private var constellationCategoryFilter: dev.watchreborn.apps.AppGroup? = null
    private val searchBarRect = RectF()
    private val searchClearRect = RectF()
    private val centerSearchPillRect = RectF()

    private val chronoHudRect = RectF()
    private val nebulaHeaderRects = ArrayList<Triple<RectF, Float, Float>>()
    private val constellationChipRects = ArrayList<Pair<RectF, String>>()
    private val constellationCategoryChipRects = ArrayList<Pair<RectF, dev.watchreborn.apps.AppGroup>>()
    private val folderHeaderRect = RectF()
    private val clusterPillRect = RectF()
    private val sheetRowRects = ArrayList<Pair<RectF, ClusterOption>>()
    private val sheetBoundsRect = RectF()
    private val topologyRowRects = ArrayList<Pair<RectF, ClusterTopology>>()
    private val previewTmp = FloatArray(2)
    private val pickerBackRect = RectF()
    private var topologyPickerOpen = false
    private val widgetActionRects = ArrayList<Pair<RectF, String>>()

    /** Alphabet scrubber state in List View. */
    private var scrubbingLetter: Char? = null
    private var edgeCrownDragging = false

    /** Open the List view rather than the cluster (the app view the user last chose). */
    var preferList = false
        set(v) { field = v; list.snap(if (v) 1f else 0f) }

    var topInset = 0f
    var bottomInset = 0f

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    private val rect = RectF()
    private val arcRect = RectF()
    private val clipPath = Path()
    private val tmp = FloatArray(2)
    private val anchorTmp = FloatArray(2)
    private val pill = RectF()

    /** Every item drawn this frame (centre, radius) for hit testing; last drawn is on top. */
    private val hits = ArrayList<Hit>()
    private class Hit(val x: Float, val y: Float, val r: Float, val item: GridItem, val slotIndex: Int = -1)

    private var lastFrame = 0L

    private val density = resources.displayMetrics.density
    private val fontScale = resources.configuration.fontScale
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = v * density * fontScale

    private val unit get() = minOf(width.toFloat(), dp(Metrics.WATCH_WIDTH_DP))

    private var centredItemKey: String = ""

    private val activeGridItems: List<GridItem>
        get() = if (autoFolders && folderItems.isNotEmpty()) {
            folderItems
        } else if (flatWidgetItems.isNotEmpty()) {
            flatWidgetItems
        } else {
            byUse
        }

    fun setApps(alpha: List<App>, use: List<App>, folders: List<GridItem>, flatWithWidgets: List<GridItem> = use) {
        alphabetical = alpha
        byUse = use
        folderItems = folders
        flatWidgetItems = flatWithWidgets
        rebuildGridHexes()
        activeFolder?.let { cur ->
            val updated = folders.filterIsInstance<Folder>().firstOrNull { it.group == cur.group }
            if (updated != null) {
                activeFolder = updated
                bloomAnchorIndex = activeGridItems.indexOf(updated)
                folderHexes = Hex.spiral(updated.apps.size)
            }
        }
        face.favorites = use.take(3)
        rebuildStackCards()
        invalidate()
    }

    private fun rebuildGridHexes() {
        gridHexes = Hex.spiral(activeGridItems.size)
    }

    fun refreshCards() {
        rebuildStackCards()
        invalidate()
    }

    private fun rebuildStackCards() {
        val recents = byUse.take(4)
        val raw = ArrayList<Card>()

        val tr = nowPlayingTrack
        if (tr != null) {
            raw += Card(
                id = "now_playing",
                tint = Color.rgb(255, 55, 95),
                title = if (tr.playing) "Now Playing" else "Paused",
                big = tr.title,
                detail = tr.artist.orEmpty().ifEmpty { "Media" },
                art = tr.art,
                mediaPlaying = tr.playing,
                tapAction = CardAction.MEDIA_OPEN,
                pinned = "now_playing" in pinnedCardIds,
            )
        } else {
            raw += Card(
                id = "now_playing",
                tint = Color.rgb(255, 55, 95),
                title = "Now Playing",
                big = "Nothing Playing",
                detail = "Tap Play to resume media",
                mediaPlaying = false,
                tapAction = CardAction.MEDIA_OPEN,
                pinned = "now_playing" in pinnedCardIds,
            )
        }

        info.nextAlarm?.let {
            raw += Card(
                id = "alarm",
                tint = Color.rgb(255, 159, 10),
                title = "Alarm",
                big = timeText(it),
                detail = "Tap to open Alarms",
                tapAction = CardAction.OPEN_ALARMS,
                pinned = "alarm" in pinnedCardIds,
            )
        }

        val batPct = if (info.battery >= 0) "${info.battery}%" else "--"
        val ramPct = (ramUsedRatio * 100).toInt().coerceIn(0, 100)
        val storPct = (storageUsedRatio * 100).toInt().coerceIn(0, 100)
        raw += Card(
            id = "battery",
            tint = Color.rgb(48, 209, 88),
            title = if (info.charging) "Charging ⚡" else "Battery & Vitals",
            big = batPct,
            detail = "RAM $ramPct% • Storage $storPct%",
            rings = floatArrayOf(
                (info.battery.coerceAtLeast(0)) / 100f,
                (1f - ramUsedRatio).coerceIn(0.05f, 1f),
                (1f - storageUsedRatio).coerceIn(0.05f, 1f),
            ),
            tapAction = CardAction.OPEN_BATTERY,
            pinned = "battery" in pinnedCardIds,
        )

        if (stepCount > 0) {
            raw += Card(
                id = "activity",
                tint = Color.rgb(250, 17, 79),
                title = "Activity",
                big = "$stepCount steps",
                detail = "${(stepCount * 0.04f).toInt()} kcal • ${"%.1f".format(stepCount * 0.00076f)} km",
                rings = floatArrayOf(
                    (stepCount / 8000f).coerceIn(0.05f, 1f),
                    (stepCount / 6000f).coerceIn(0.05f, 1f),
                    (stepCount / 4000f).coerceIn(0.05f, 1f),
                ),
                pinned = "activity" in pinnedCardIds,
            )
        }

        if (recents.isNotEmpty()) {
            raw += Card(
                id = "recents",
                tint = Color.rgb(100, 210, 255),
                title = "Recent Apps",
                big = "",
                apps = recents,
                pinned = "recents" in pinnedCardIds,
            )
        }

        stack.cards = raw.sortedByDescending { it.pinned }
    }

    private fun timeText(ms: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(ms)

    // ---------------------------------------------------------------- navigation

    val onFace get() = apps.target == 0f && stackIn.target == 0f && controlIn.target == 0f

    /** Snap to the initial view on startup based on [honeycombHome]. */
    fun applyInitialHomeMode() {
        if (honeycombHome) {
            apps.snap(1f)
            grid.snap(1f)
            panX.snap(0f)
            panY.snap(0f)
        } else {
            apps.snap(0f)
            grid.snap(0f)
        }
        invalidate()
    }

    fun home() {
        hideKeyboard()
        if (gridSearching) {
            clearSearch()
            return
        }
        if (honeycombHome) {
            if (onFace || stackIn.target > 0f || controlIn.target > 0f) {
                openFullHoneycomb()
            } else if (folderIn.target > 0f || widgetIn.target > 0f || overview.target > 0f || list.target > 0f || grid.target < 0.5f) {
                openFullHoneycomb()
            } else if (hypot(panX.target, panY.target) > dp(8f)) {
                panX.target = 0f
                panY.target = 0f
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                invalidate()
            } else {
                toFace()
            }
        } else {
            if (onFace) openApps() else toFace()
        }
    }

    fun back(): Boolean {
        // The layer to pop is decided by the pure NavBack priority (unit-tested);
        // this method only snapshots state and performs the side effects.
        val state = NavState(
            searching = searching || gridSearching || searchQuery.isNotEmpty(),
            topologyPickerOpen = topologyPickerOpen && clusterSheet.target > 0f,
            sheetOpen = clusterSheet.target > 0f,
            torchOffViaBack = controlIn.target > 0f && controlCenter.state.screenFlashlight,
            stackOrControlOpen = stackIn.target > 0f || controlIn.target > 0f,
            inApps = apps.target == 1f,
            widgetOpen = widgetIn.target == 1f,
            folderOpen = folderIn.target == 1f,
            overviewOpen = overview.target == 1f,
            listOpen = list.target >= 0.5f,
            gridOpen = grid.target == 1f,
            panned = hypot(panX.target, panY.target) > dp(12f),
            onFace = onFace,
            honeycombHome = honeycombHome,
        )
        return when (NavBack.layerToPop(state)) {
            NavLayer.SEARCH -> {
                clearSearch()
                true
            }
            NavLayer.TOPOLOGY_PICKER -> {
                topologyPickerOpen = false
                invalidate()
                true
            }
            NavLayer.SHEET -> {
                clusterSheet.target = 0f
                invalidate()
                true
            }
            NavLayer.TORCH_OFF -> {
                onControlAction(ControlAction.TORCH, null)
                true
            }
            NavLayer.STACK_OR_CONTROL -> {
                if (honeycombHome) openFullHoneycomb() else toFace()
                true
            }
            NavLayer.WIDGET_POD -> {
                closeWidgetPod()
                true
            }
            NavLayer.FOLDER -> {
                closeFolder()
                true
            }
            NavLayer.OVERVIEW -> {
                overview.target = 0f
                invalidate()
                true
            }
            NavLayer.LIST -> {
                list.target = 0f
                grid.target = 1f
                invalidate()
                true
            }
            NavLayer.RECENTER -> {
                panX.target = 0f
                panY.target = 0f
                invalidate()
                true
            }
            NavLayer.FACE -> {
                openFullHoneycomb()
                true
            }
            NavLayer.EXIT_TO_FACE -> {
                toFace()
                true
            }
            NavLayer.NONE -> false
        }
    }

    fun openApps() {
        hideKeyboard()
        gridSearching = false
        constellationIn.snap(0f)
        stackIn.target = 0f
        controlIn.target = 0f
        clusterSheet.snap(0f)
        grid.snap(if (honeycombHome) 1f else 0f)
        overview.snap(0f)
        folderIn.snap(0f)
        widgetIn.snap(0f)
        activeFolder = null
        activeWidget = null
        panX.snap(0f); panY.snap(0f)
        apps.target = 1f
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        invalidate()
    }

    fun openFullHoneycomb() {
        hideKeyboard()
        gridSearching = false
        constellationIn.target = 0f
        stackIn.target = 0f
        controlIn.target = 0f
        clusterSheet.target = 0f
        list.target = 0f
        overview.target = 0f
        userZoom.target = 1f
        folderIn.target = 0f
        widgetIn.target = 0f
        activeFolder = null
        activeWidget = null
        panX.target = 0f
        panY.target = 0f
        apps.target = 1f
        grid.target = 1f
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        invalidate()
    }

    fun toFace() {
        hideKeyboard()
        gridSearching = false
        constellationIn.target = 0f
        apps.target = 0f
        stackIn.target = 0f
        controlIn.target = 0f
        clusterSheet.target = 0f
        overview.target = 0f
        folderIn.target = 0f
        widgetIn.target = 0f
        stackScroll.target = 0f
        invalidate()
    }

    fun openStack() {
        hideKeyboard()
        gridSearching = false
        constellationIn.target = 0f
        apps.target = 0f
        controlIn.target = 0f
        widgetIn.target = 0f
        stackIn.target = 1f
        stackScroll.snap(0f)
        stack.scroll = 0f
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        invalidate()
    }

    fun openControlCenter() {
        hideKeyboard()
        gridSearching = false
        constellationIn.target = 0f
        apps.target = 0f
        stackIn.target = 0f
        widgetIn.target = 0f
        controlIn.target = 1f
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        invalidate()
    }

    private fun markHintSeen(id: String) {
        if (id !in hintsSeen) {
            hintsSeen = hintsSeen + id
            onHintSeen(id)
            invalidate()
        }
    }

    /** The one coach hint to show right now, if any: gesture-only features advertise themselves once. */
    private fun currentCoachHint(): String? {
        if (apps.target != 1f || clusterSheet.target > 0f || constellationIn.target > 0.5f ||
            folderIn.target > 0.5f || widgetIn.target > 0.5f || gridSearching || searching
        ) return null
        return if (list.target >= 0.5f) {
            HINT_SCRUB.takeIf { it !in hintsSeen }
        } else if (grid.target >= 0.5f) {
            listOf(HINT_PINCH, HINT_PIN, HINT_CROWN).firstOrNull { it !in hintsSeen }
        } else {
            null
        }
    }

    private fun drawCoachHint(c: Canvas, w: Float, bottom: Float, alpha: Float) {
        val id = currentCoachHint()
        coachHintId = id
        if (id == null) {
            coachHintRect.setEmpty()
            return
        }
        val label = when (id) {
            HINT_PINCH -> "🤏 Pinch out for an overview of everything"
            HINT_PIN -> "📌 Long-press any bubble to pin it in place"
            HINT_CROWN -> "↕ Drag the right screen edge to zoom"
            HINT_SCRUB -> "🔤 Drag the A–Z edge to jump through the list"
            else -> return
        }
        text.textSize = sp(12.5f)
        val tw = text.measureText(label)
        val hh = dp(30f)
        val hw = tw + dp(28f)
        val hy = bottom - dp(16f) - dp(Metrics.PHONE_PILL_H_DP) - dp(10f) - hh
        coachHintRect.set(w / 2 - hw / 2, hy, w / 2 + hw / 2, hy + hh)
        fill.color = Color.argb((alpha * 235).toInt(), 30, 34, 40)
        c.drawRoundRect(coachHintRect, hh / 2, hh / 2, fill)
        stroke.strokeWidth = dp(1.2f)
        stroke.color = withAlpha(accent, (alpha * 170).toInt())
        c.drawRoundRect(coachHintRect, hh / 2, hh / 2, stroke)
        text.color = Color.argb((alpha * 255).toInt(), 255, 255, 255)
        c.drawText(label, coachHintRect.centerX() - tw / 2, coachHintRect.centerY() + text.textSize * 0.35f, text)
    }

    fun openClusterSheet() {
        topologyPickerOpen = false
        clusterSheet.target = 1f
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        invalidate()
    }

    fun startConstellationSearch() {
        clusterSheet.target = 0f
        if (apps.target < 0.5f) apps.target = 1f
        if (grid.target < 0.5f) grid.target = 1f
        list.target = 0f
        overview.target = 0f
        userZoom.target = 1f
        constellationZoom.snap(1f)
        folderIn.target = 0f
        widgetIn.target = 0f
        panX.target = 0f
        panY.target = 0f
        constellationPanX.snap(0f)
        constellationPanY.snap(0f)
        searchQuery = ""
        constellationFilter = "ALL"
        constellationCategoryFilter = null
        gridSearching = true
        constellationIn.target = 1f
        hideKeyboard()
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        invalidate()
    }

    fun toggleConstellation() {
        if (gridSearching || constellationIn.target > 0.5f) {
            clearSearch()
        } else {
            startConstellationSearch()
        }
    }

    /** Applies a layout chosen in the topology picker and reports it for persistence. */
    fun selectTopology(topo: ClusterTopology) {
        clusterTopology = topo
        overview.target = 0f
        userZoom.target = if (topo == ClusterTopology.NEBULA) 0.72f else 1f
        topologyPickerOpen = false
        onTopologySelected(topo)
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        invalidate()
    }

    /**
     * Computes the 2D world coordinates `(out[0], out[1])` for item [index] of [total]
     * under the active [clusterTopology].
     */
    private fun computeTopologyWorldPos(index: Int, total: Int, pitch: Float, out: FloatArray) =
        computeTopologyWorldPos(clusterTopology, index, total, pitch, out)

    private fun computeTopologyWorldPos(topo: ClusterTopology, index: Int, total: Int, pitch: Float, out: FloatArray) {
        when (topo) {
            ClusterTopology.HONEYCOMB, ClusterTopology.NEBULA -> {
                if (index in gridHexes.indices) pointyCenter(gridHexes[index], pitch, out)
                else { out[0] = 0f; out[1] = 0f }
            }
            ClusterTopology.GRAVITY -> {
                if (index in gridHexes.indices) {
                    pointyCenter(gridHexes[index], pitch * 1.10f, out)
                } else {
                    out[0] = 0f; out[1] = 0f
                }
            }
            ClusterTopology.ORBITALS -> {
                if (index == 0) {
                    out[0] = 0f; out[1] = 0f
                    return
                }
                when {
                    index <= 6 -> {
                        val cnt = min(6, (total - 1).coerceAtLeast(1))
                        val ang = (index - 1) * (2.0 * PI / cnt) - PI / 2.0 + orbitalAngle
                        val r = pitch * 1.06f
                        out[0] = (cos(ang) * r).toFloat()
                        out[1] = (sin(ang) * r).toFloat()
                    }
                    index <= 16 -> {
                        val cnt = min(10, (total - 7).coerceAtLeast(1))
                        val ang = (index - 7) * (2.0 * PI / cnt) - PI / 2.0 - orbitalAngle * 0.72f
                        val r = pitch * 1.98f
                        out[0] = (cos(ang) * r).toFloat()
                        out[1] = (sin(ang) * r).toFloat()
                    }
                    else -> {
                        val cnt = (total - 17).coerceAtLeast(1)
                        val ang = (index - 17) * (2.0 * PI / cnt) - PI / 2.0 + orbitalAngle * 0.48f
                        val r = pitch * 2.88f
                        out[0] = (cos(ang) * r).toFloat()
                        out[1] = (sin(ang) * r).toFloat()
                    }
                }
            }
            ClusterTopology.RIVER -> {
                // watchOS 10+ Vertical River: alternating rows of 2 and 3 bubbles
                val pair = index / 5
                val rem = index % 5
                val isTwoRow = rem < 2
                val row = pair * 2 + (if (isTwoRow) 0 else 1)
                val col = if (isTwoRow) rem else rem - 2
                val cols = if (isTwoRow) 2 else 3
                out[0] = (col - (cols - 1) / 2f) * (pitch * 1.08f)
                out[1] = (row - 1) * (pitch * 0.90f)
            }
            ClusterTopology.FIBONACCI -> {
                if (index == 0) {
                    out[0] = 0f; out[1] = 0f
                    return
                }
                val goldenAngle = 2.3999632f // 137.50776 degrees in radians
                val ang = index * goldenAngle + orbitalAngle * 0.6f
                val r = pitch * 0.68f * sqrt(index.toFloat())
                out[0] = cos(ang) * r
                out[1] = sin(ang) * r
            }
            ClusterTopology.CHRONO_BEZEL -> {
                if (index == 0) {
                    out[0] = 0f; out[1] = 0f
                    return
                }
                if (index <= 10) {
                    val cnt = min(10, (total - 1).coerceAtLeast(1))
                    val ang = (index - 1) * (2.0 * PI / cnt) - PI / 2.0 + orbitalAngle
                    val r = pitch * 1.56f
                    out[0] = (cos(ang) * r).toFloat()
                    out[1] = (sin(ang) * r).toFloat()
                } else {
                    val cnt = (total - 11).coerceAtLeast(1)
                    val ang = (index - 11) * (2.0 * PI / cnt) - PI / 2.0 - orbitalAngle * 0.65f
                    val r = pitch * 2.40f
                    out[0] = (cos(ang) * r).toFloat()
                    out[1] = (sin(ang) * r).toFloat()
                }
            }
            ClusterTopology.DNA_HELIX -> {
                // 🧬 3D Rotating Twin-Strand DNA Ribbon
                if (index == 0) {
                    out[0] = 0f; out[1] = 0f
                    return
                }
                val rung = (index + 1) / 2
                val strand = if (index % 2 == 1) 0 else 1
                val phase = rung * 0.56f + orbitalAngle + strand * PI.toFloat()
                val amp = pitch * 1.24f
                out[0] = sin(phase) * amp
                out[1] = (rung - 2.5f) * (pitch * 0.72f)
            }
            ClusterTopology.HOURGLASS -> {
                // ⏳ Chrono Hourglass Vortex: pinched waist at (0,0) expanding into upper & lower cones
                if (index == 0) {
                    out[0] = 0f; out[1] = 0f
                    return
                }
                val rowSizes = intArrayOf(1, 2, 2, 3, 3, 4, 4, 5, 5, 5, 5)
                var rem = index - 1
                var rIdx = 1
                while (rIdx < rowSizes.size && rem >= rowSizes[rIdx]) {
                    rem -= rowSizes[rIdx]
                    rIdx++
                }
                val cols = if (rIdx < rowSizes.size) rowSizes[rIdx] else 5
                val col = rem.coerceIn(0, cols - 1)
                val tier = (rIdx + 1) / 2
                val isUpper = rIdx % 2 == 1
                val ySign = if (isUpper) -1f else 1f
                val spread = pitch * (0.94f + tier * 0.14f)
                out[0] = (col - (cols - 1) / 2f) * spread
                out[1] = ySign * tier * (pitch * 0.88f)
            }
            ClusterTopology.MAGNETIC_DOCK -> {
                // 🧲 Ergonomic One-Handed Thumb Arc Dock radiating upward from bottom-center
                val pivotY = pitch * 2.15f
                if (index == 0) {
                    out[0] = 0f
                    out[1] = pitch * 0.85f
                    return
                }
                val arcCapacities = intArrayOf(4, 6, 8, 10)
                var rem = index - 1
                var aIdx = 0
                while (aIdx < arcCapacities.size && rem >= arcCapacities[aIdx]) {
                    rem -= arcCapacities[aIdx]
                    aIdx++
                }
                val cnt = if (aIdx < arcCapacities.size) arcCapacities[aIdx] else max(6, total - 19)
                val slot = rem.coerceIn(0, (cnt - 1).coerceAtLeast(0))
                val radius = pitch * (1.28f + aIdx * 0.88f)
                val span = (0.72f + aIdx * 0.08f).coerceAtMost(1.05f) * PI.toFloat()
                val frac = if (cnt <= 1) 0f else (slot.toFloat() / (cnt - 1)) - 0.5f
                val ang = -PI.toFloat() / 2f + frac * span + orbitalAngle * 0.35f
                out[0] = cos(ang) * radius
                out[1] = pivotY + sin(ang) * radius
            }
            ClusterTopology.LIQUID_POOL -> {
                ensurePoolCapacity(total, pitch)
                if (index in poolPosX.indices) {
                    out[0] = poolPosX[index]
                    out[1] = poolPosY[index]
                } else {
                    out[0] = 0f; out[1] = 0f
                }
            }
        }
    }

    /** 3D depth factor (-1f back .. +1f front) for [ClusterTopology.DNA_HELIX]. */
    private fun dnaHelixDepth(index: Int): Float {
        if (index == 0) return 1f
        val rung = (index + 1) / 2
        val strand = if (index % 2 == 1) 0 else 1
        val phase = rung * 0.56f + orbitalAngle + strand * PI.toFloat()
        return cos(phase)
    }

    /** Updates live accelerometer/gravity tilt vector (-1f..1f) from [MainActivity]. */
    fun setDeviceTilt(nx: Float, ny: Float) {
        targetTiltX = nx.coerceIn(-1f, 1f)
        targetTiltY = ny.coerceIn(-1f, 1f)
        if (apps.value > 0f && bubblePhysics && (clusterTopology == ClusterTopology.LIQUID_POOL || hypot(targetTiltX - tiltX, targetTiltY - tiltY) > 0.025f)) {
            invalidate()
        }
    }

    /** Triggers a radial shockwave ripple across the bubble cluster from screen coordinates ([sx], [sy]). */
    fun triggerShockwave(sx: Float = width / 2f, sy: Float = height / 2f, strength: Float = 1f) {
        val u = unit.coerceAtLeast(1f)
        val cx = width / 2f
        val cy = height / 2f
        val wx = sx - cx - panX.value
        val wy = sy - cy - panY.value
        shockwaveOriginX = sx
        shockwaveOriginY = sy
        shockwaveProgress = 0f
        val items = activeGridItems
        val pitch = Metrics.GRID_PITCH * u
        ensureWakeCapacity(items.size)
        for (i in items.indices) {
            computeTopologyWorldPos(i, items.size, pitch, anchorTmp)
            val dx = anchorTmp[0] - wx
            val dy = anchorTmp[1] - wy
            val dist = hypot(dx, dy).coerceAtLeast(dp(12f))
            val falloff = (1f / (1f + dist / (u * 0.9f))).coerceIn(0.18f, 1f)
            val impulse = u * 2.6f * strength * falloff
            wakeVelX[i] += (dx / dist) * impulse
            wakeVelY[i] += (dy / dist) * impulse
            if (clusterTopology == ClusterTopology.LIQUID_POOL && i in poolVelX.indices && i > 0) {
                poolVelX[i] += (dx / dist) * impulse * 1.35f
                poolVelY[i] += (dy / dist) * impulse * 1.35f
            }
        }
        orbitalVelocity += 3.2f * strength * (if (sx >= cx) 1f else -1f)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    fun resetPoolSimulation() {
        poolInitialized = false
        grabbedPoolIndex = -1
    }

    private fun ensureWakeCapacity(count: Int) {
        if (wakeDispX.size != count) {
            wakeDispX = FloatArray(count)
            wakeDispY = FloatArray(count)
            wakeVelX = FloatArray(count)
            wakeVelY = FloatArray(count)
            wakeInitialized = false
        }
    }

    private fun ensurePoolCapacity(count: Int, pitch: Float) {
        if (poolPosX.size != count || !poolInitialized) {
            poolPosX = FloatArray(count)
            poolPosY = FloatArray(count)
            poolVelX = FloatArray(count)
            poolVelY = FloatArray(count)
            for (i in 0 until count) {
                if (i in gridHexes.indices) {
                    pointyCenter(gridHexes[i], pitch * 0.88f, anchorTmp)
                    poolPosX[i] = anchorTmp[0]
                    poolPosY[i] = anchorTmp[1]
                }
            }
            poolInitialized = true
        }
    }

    /**
     * Steps all real-time bubble physics for frame [dt]:
     * 1. Smooth tilt interpolation
     * 2. Rotary angular momentum & magnetic detent settling
     * 3. Elastic Jello / Wake Wave spring-damper simulation per bubble
     * 4. Zero-G Billiards collision & tank wall bounce for [ClusterTopology.LIQUID_POOL]
     */
    private fun stepPhysics(dt: Float): Boolean {
        var active = false

        // 1. Smooth tilt filter
        val tiltStep = (dt * 9f).coerceIn(0f, 1f)
        tiltX += (targetTiltX - tiltX) * tiltStep
        tiltY += (targetTiltY - tiltY) * tiltStep

        // 2. Shockwave ripple progress
        if (shockwaveProgress < 1f) {
            shockwaveProgress = (shockwaveProgress + dt * 1.65f).coerceAtMost(1f)
            active = true
        }

        val items = activeGridItems
        val count = items.size
        if (count == 0) return active
        val u = unit.coerceAtLeast(1f)
        val pitch = Metrics.GRID_PITCH * u

        // 3. Rotary angular momentum & magnetic notch detent
        val isRotary = clusterTopology == ClusterTopology.ORBITALS ||
            clusterTopology == ClusterTopology.CHRONO_BEZEL ||
            clusterTopology == ClusterTopology.FIBONACCI ||
            clusterTopology == ClusterTopology.DNA_HELIX
        if (!dragging && isRotary && bubblePhysics) {
            if (abs(orbitalVelocity) > 0.02f) {
                val oldTick = (orbitalAngle / 0.30f).roundToInt()
                orbitalAngle += orbitalVelocity * dt
                val newTick = (orbitalAngle / 0.30f).roundToInt()
                if (oldTick != newTick) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                orbitalVelocity *= exp(-2.8f * dt)
                active = true
            } else {
                orbitalVelocity = 0f
                // Magnetic detent snap on Chrono Bezel & Orbitals so a bubble cleanly locks into the 12 o'clock notch!
                if (clusterTopology == ClusterTopology.CHRONO_BEZEL || clusterTopology == ClusterTopology.ORBITALS) {
                    val ringCount = if (clusterTopology == ClusterTopology.CHRONO_BEZEL) min(10, (count - 1).coerceAtLeast(1)) else min(6, (count - 1).coerceAtLeast(1))
                    val stepAng = (2.0 * PI / ringCount).toFloat()
                    val nearestNotch = (orbitalAngle / stepAng).roundToInt() * stepAng
                    val diff = nearestNotch - orbitalAngle
                    if (abs(diff) > 0.002f) {
                        orbitalAngle += diff * (dt * 10f).coerceIn(0f, 1f)
                        active = true
                    } else {
                        orbitalAngle = nearestNotch
                    }
                }
            }
        } else if (!bubblePhysics) {
            orbitalVelocity = 0f
        }

        // 4. Elastic Jello / Wake Wave spring-damper simulation per bubble
        ensureWakeCapacity(count)
        if (!wakeInitialized) {
            lastPanXForWake = panX.value
            lastPanYForWake = panY.value
            wakeInitialized = true
        }
        val dPanX = panX.value - lastPanXForWake
        val dPanY = panY.value - lastPanYForWake
        lastPanXForWake = panX.value
        lastPanYForWake = panY.value

        if (bubblePhysics) {
            val stiffness = 175f
            val damping = 15.5f
            val maxWake = pitch * 0.42f
            for (i in 0 until count) {
                val ringDist = if (i in gridHexes.indices) {
                    val h = gridHexes[i]
                    maxOf(abs(h.q), abs(h.r), abs(h.q + h.r)).toFloat()
                } else {
                    1f
                }
                // Outer rings lag slightly behind inner rings during pan/fling for an organic underwater jello wave
                val lagFactor = (0.12f + 0.14f * ringDist).coerceAtMost(0.62f)
                wakeDispX[i] = (wakeDispX[i] - dPanX * lagFactor).coerceIn(-maxWake, maxWake)
                wakeDispY[i] = (wakeDispY[i] - dPanY * lagFactor).coerceIn(-maxWake, maxWake)

                val ax = -stiffness * wakeDispX[i] - damping * wakeVelX[i]
                val ay = -stiffness * wakeDispY[i] - damping * wakeVelY[i]
                wakeVelX[i] += ax * dt
                wakeVelY[i] += ay * dt
                wakeDispX[i] = (wakeDispX[i] + wakeVelX[i] * dt).coerceIn(-maxWake, maxWake)
                wakeDispY[i] = (wakeDispY[i] + wakeVelY[i] * dt).coerceIn(-maxWake, maxWake)

                if (abs(wakeDispX[i]) > 0.35f || abs(wakeDispY[i]) > 0.35f || abs(wakeVelX[i]) > 2f || abs(wakeVelY[i]) > 2f) {
                    active = true
                } else {
                    wakeDispX[i] = 0f; wakeDispY[i] = 0f; wakeVelX[i] = 0f; wakeVelY[i] = 0f
                }
            }
        } else {
            for (i in 0 until count) {
                wakeDispX[i] = 0f; wakeDispY[i] = 0f; wakeVelX[i] = 0f; wakeVelY[i] = 0f
            }
        }

        // 5. Zero-G Billiards Pool (`LIQUID_POOL`) multi-body physics & elastic collisions
        if (clusterTopology == ClusterTopology.LIQUID_POOL) {
            ensurePoolCapacity(count, pitch)
            val tankR = pitch * 2.55f
            val bubbleR = pitch * 0.45f
            val minDist = bubbleR * 2.02f
            val gravScale = if (bubblePhysics) u * 1.45f else 0f

            // Anchor bubble 0 (Core Clock Hub) gently near (0,0) as a central buoy, while all other bubbles float & collide!
            for (i in 0 until count) {
                if (i == grabbedPoolIndex && dragging) continue
                if (i == 0) {
                    poolPosX[0] *= (1f - 8f * dt).coerceAtLeast(0f)
                    poolPosY[0] *= (1f - 8f * dt).coerceAtLeast(0f)
                    poolVelX[0] = 0f
                    poolVelY[0] = 0f
                    continue
                }
                // Tilt gravity + gentle central attraction so bubbles stay in a lively cluster
                val gx = tiltX * gravScale - poolPosX[i] * 0.45f
                val gy = tiltY * gravScale - poolPosY[i] * 0.45f
                poolVelX[i] = (poolVelX[i] + gx * dt) * exp(-1.65f * dt)
                poolVelY[i] = (poolVelY[i] + gy * dt) * exp(-1.65f * dt)
                poolPosX[i] += poolVelX[i] * dt
                poolPosY[i] += poolVelY[i] * dt

                // Circular containment tank wall bounce
                val distFromCenter = hypot(poolPosX[i], poolPosY[i])
                val maxCenterDist = tankR - bubbleR
                if (distFromCenter > maxCenterDist && distFromCenter > 1e-3f) {
                    val nx = poolPosX[i] / distFromCenter
                    val ny = poolPosY[i] / distFromCenter
                    poolPosX[i] = nx * maxCenterDist
                    poolPosY[i] = ny * maxCenterDist
                    val vn = poolVelX[i] * nx + poolVelY[i] * ny
                    if (vn > 0f) {
                        poolVelX[i] -= 1.75f * vn * nx
                        poolVelY[i] -= 1.75f * vn * ny
                    }
                }
            }

            // Pairwise elastic bubble-to-bubble collisions (2 relaxation passes per frame)
            for (pass in 0 until 2) {
                for (i in 0 until count) {
                    for (j in i + 1 until count) {
                        val dx = poolPosX[j] - poolPosX[i]
                        val dy = poolPosY[j] - poolPosY[i]
                        val dist = hypot(dx, dy)
                        if (dist < minDist && dist > 1e-3f) {
                            val overlap = minDist - dist
                            val nx = dx / dist
                            val ny = dy / dist
                            if (i == 0 || (i == grabbedPoolIndex && dragging)) {
                                poolPosX[j] += nx * overlap
                                poolPosY[j] += ny * overlap
                            } else if (j == grabbedPoolIndex && dragging) {
                                poolPosX[i] -= nx * overlap
                                poolPosY[i] -= ny * overlap
                            } else {
                                poolPosX[i] -= nx * overlap * 0.5f
                                poolPosY[i] -= ny * overlap * 0.5f
                                poolPosX[j] += nx * overlap * 0.5f
                                poolPosY[j] += ny * overlap * 0.5f
                            }
                            if (pass == 0) {
                                val rvx = poolVelX[j] - poolVelX[i]
                                val rvy = poolVelY[j] - poolVelY[i]
                                val vn = rvx * nx + rvy * ny
                                if (vn < 0f) {
                                    val restitution = 0.78f
                                    val imp = -(1f + restitution) * vn * 0.5f
                                    if (i != 0 && i != grabbedPoolIndex) {
                                        poolVelX[i] -= imp * nx
                                        poolVelY[i] -= imp * ny
                                    }
                                    if (j != grabbedPoolIndex) {
                                        poolVelX[j] += imp * nx
                                        poolVelY[j] += imp * ny
                                    }
                                    if (abs(vn) > u * 0.85f) {
                                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            for (i in 1 until count) {
                if (hypot(poolVelX[i], poolVelY[i]) > 3.5f) {
                    active = true
                    break
                }
            }
        }

        return active
    }

    private fun gravityWeightFor(index: Int, item: GridItem): Float {
        if (clusterTopology != ClusterTopology.GRAVITY) return 1f
        return when {
            index == 0 -> 1.28f
            item is WidgetBubble -> 1.14f
            item is Folder -> 1.16f
            index <= 6 -> 1.22f
            index <= 12 -> 0.98f
            else -> 0.78f
        }
    }

    fun openFolder(folder: Folder) {
        if (grid.target == 0f) grid.target = 1f
        widgetIn.target = 0f
        activeWidget = null
        activeFolder = folder
        val items = activeGridItems
        bloomAnchorIndex = items.indexOfFirst { it.key == folder.key }
        if (bloomFolders && bloomAnchorIndex in items.indices && clusterTopology != ClusterTopology.CHRONO_BEZEL) {
            computeTopologyWorldPos(bloomAnchorIndex, items.size, Metrics.GRID_PITCH * unit, anchorTmp)
            panX.target = -anchorTmp[0]
            panY.target = -anchorTmp[1]
        }
        folderHexes = Hex.spiral(folder.apps.size)
        folderPanX.snap(0f)
        folderPanY.snap(0f)
        folderIn.target = 1f
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        invalidate()
    }

    private fun closeFolder() {
        folderIn.target = 0f
        folderPanX.target = 0f
        folderPanY.target = 0f
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        invalidate()
    }

    fun openWidgetPod(widget: WidgetBubble) {
        if (grid.target == 0f) grid.target = 1f
        folderIn.target = 0f
        activeFolder = null
        activeWidget = widget
        val items = activeGridItems
        bloomAnchorIndex = items.indexOfFirst { it.key == widget.key }
        if (bloomAnchorIndex in items.indices && clusterTopology != ClusterTopology.CHRONO_BEZEL && clusterTopology != ClusterTopology.NEBULA) {
            computeTopologyWorldPos(bloomAnchorIndex, items.size, Metrics.GRID_PITCH * unit, anchorTmp)
            panX.target = -anchorTmp[0]
            panY.target = -anchorTmp[1]
        }
        widgetIn.target = 1f
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        invalidate()
    }

    fun closeWidgetPod() {
        widgetIn.target = 0f
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        invalidate()
    }

    private fun currentTimerRemainingMs(): Long {
        if (!timerRunning) return timerRemainingMs
        val rem = timerEndUptimeMs - SystemClock.uptimeMillis()
        if (rem <= 0L) {
            timerRunning = false
            timerRemainingMs = 0L
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            return 0L
        }
        timerRemainingMs = rem
        return rem
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 1 / 60f else ((now - lastFrame) / 1000f).coerceAtMost(0.05f)
        lastFrame = now

        val springs = arrayOf(apps, grid, overview, userZoom, constellationZoom, folderIn, widgetIn, clusterSheet, constellationIn, list, stackIn, controlIn, panX, panY, folderPanX, folderPanY, constellationPanX, constellationPanY, stackScroll)
        for (s in springs) s.step(dt)
        stepListFling(dt)
        val physicsAnimating = stepPhysics(dt)

        val w = width.toFloat()
        val h = height.toFloat()
        val top = topInset + 0.02f * w
        val bottom = h - bottomInset - 0.02f * w
        val cal = Calendar.getInstance()

        face.is24Hour = android.text.format.DateFormat.is24HourFormat(context)
        c.drawColor(Color.BLACK)

        val overlayMax = maxOf(apps.value, stackIn.value, controlIn.value)
        val faceAlpha = (1f - overlayMax).coerceIn(0f, 1f)
        if (faceAlpha > 0f) {
            val scale = 1f - 0.12f * overlayMax
            c.save()
            c.scale(scale, scale, w / 2, h / 2)
            face.draw(c, w, h, top, bottom, cal, info, faceAlpha)
            c.restore()
        }

        if (stackIn.value > 0f) {
            stack.scroll = stackScroll.value
            stack.draw(c, w, h, top, bottom, cal, stackIn.value)
        }

        if (controlIn.value > 0f) {
            controlCenter.draw(c, w, h, top, bottom, cal, info.battery, info.charging, controlIn.value)
        }

        hits.clear()
        val a = apps.value
        if (a > 0f) {
            val fIn = folderIn.value
            val useSeparateFolderView = !bloomFolders
            val focusAlpha = a * (1f - grid.value) * (1f - list.value) * (if (useSeparateFolderView) 1f - fIn else 1f)
            val gridAlpha = a * grid.value * (1f - list.value) * (if (useSeparateFolderView) 1f - fIn else 1f)
            val folderAlpha = if (useSeparateFolderView) a * fIn * (1f - list.value) else 0f
            val listAlpha = a * list.value
            val zoom = Metrics.ICONS_FROM_SCALE + (1f - Metrics.ICONS_FROM_SCALE) * a
            if (focusAlpha > 0.001f) drawFocus(c, w, h, top, cal, focusAlpha, zoom * (1f - 0.12f * grid.value), a)
            if (gridAlpha > 0.001f) drawGrid(c, w, h, top, cal, gridAlpha, 1.15f - 0.15f * grid.value)
            if (folderAlpha > 0.001f) drawFolderView(c, w, h, top, folderAlpha)
            if (listAlpha > 0.001f) drawList(c, w, h, top, bottom, listAlpha)
            drawPill(c, w, bottom, a)
            drawCoachHint(c, w, bottom, a)
            drawCrownTrack(c, w, h, top, bottom, a)
            if (clusterSheet.value > 0.001f) drawClusterSheet(c, w, h, top, bottom, clusterSheet.value)
        }

        val animating = springs.any { !it.settled } || abs(listVelocity) > 1f || dragging || physicsAnimating
        if (animating) postInvalidateOnAnimation()
        else {
            lastFrame = 0L
            val hasPulseOrTimer = a > 0f && (timerRunning || NowPlayingListener.activePackages.isNotEmpty())
            val hasLiveGridBubbles = a > 0f && (honeycombHome || liveWidgets)
            if (hasPulseOrTimer) {
                postInvalidateDelayed(75L)
            } else if (faceAlpha > 0f || stackIn.value > 0f || controlIn.value > 0f || hasLiveGridBubbles) {
                postInvalidateDelayed(1000L - System.currentTimeMillis() % 1000L)
            }
        }
    }

    /** The cluster: most used app in the middle, the next six round it, the rest blurred behind. */
    private fun drawFocus(c: Canvas, w: Float, h: Float, top: Float, cal: Calendar, alpha: Float, zoom: Float, sharpness: Float) {
        val u = unit
        val cx = w / 2
        val cy = h / 2
        val items = activeGridItems
        for (i in 7 until items.size) {
            if (i >= gridHexes.size) break
            pointyCenter(gridHexes[i], Metrics.GRID_PITCH * u * 1.25f * zoom, tmp)
            val d = Metrics.GRID_CENTER_D * u * 0.85f * zoom
            if (abs(tmp[1]) > h / 2 + d || abs(tmp[0]) > w / 2 + d) continue
            drawIcon(c, items[i].blurred, cx + tmp[0], cy + tmp[1], d * items[i].blurredScale, alpha * Metrics.FOCUS_BACKDROP_ALPHA, false, false)
        }
        for (i in 0 until minOf(7, items.size)) {
            val hex = if (i == 0) Hex(0, 0) else FOCUS_RING_ORDER[i - 1]
            flatCenter(hex, Metrics.FOCUS_RING * u * zoom, tmp)
            val d = (if (i == 0) Metrics.FOCUS_CENTER_D else Metrics.FOCUS_RING_D) * u * zoom
            val x = cx + tmp[0]; val y = cy + tmp[1]
            val item = items[i]
            drawIcon(c, item.blurred, x, y, d * item.blurredScale, alpha * (1f - sharpness), false, false)
            drawGridItem(c, item, x, y, d, alpha * sharpness, cal)
            hits += Hit(x, y, d / 2, item, i)
        }
        drawGridHeader(c, w, top, items.firstOrNull(), 0f, alpha * sharpness)
    }

    /**
     * The full grid: pointy-topped rows with fisheye, Overview zoom, in-place Blooming Clusters (#2),
     * Constellation Search (#4), and Live Widget & Core Clock Bubbles (#1, #5).
     */
    private fun drawGrid(c: Canvas, w: Float, h: Float, top: Float, cal: Calendar, alpha: Float, zoom: Float) {
        val u = unit
        val cx = w / 2
        val cy = h / 2
        val sx = max(1f, w / u)
        val sy = max(1f, (h / u) / Metrics.WATCH_ASPECT)
        val items = activeGridItems
        val uz = userZoom.value
        val ov = max(overview.value, ((0.82f - uz) / (0.82f - 0.48f)).coerceIn(0f, 1f))
        val effZoom = zoom * (uz * (1f - overview.value) + 0.48f * overview.value)

        val bFolder = if (bloomFolders) activeFolder else null
        val bWidget = activeWidget
        val fVal = if (bFolder != null) folderIn.value else 0f
        val wVal = if (bWidget != null) widgetIn.value else 0f
        val bVal = max(fVal, wVal)
        val cVal = constellationIn.value

        chronoHudRect.setEmpty()
        nebulaHeaderRects.clear()

        // Special full-galaxy renderer for 🌌 NEBULA ISLANDS when not unraveling
        if (clusterTopology == ClusterTopology.NEBULA && cVal <= 0.001f && !gridSearching) {
            drawNebulaIslands(c, w, h, top, cal, alpha, effZoom, ov, bFolder, bWidget, fVal, wVal, bVal)
            return
        }

        var anchorX = 0f
        var anchorY = 0f
        if (bVal > 0.001f && bloomAnchorIndex in items.indices) {
            computeTopologyWorldPos(bloomAnchorIndex, items.size, Metrics.GRID_PITCH * u, anchorTmp)
            anchorX = anchorTmp[0]
            anchorY = anchorTmp[1]
        }
        val shockwavePush = u * 0.54f * bVal

        var nearest: GridItem? = null
        var nearestD = Float.MAX_VALUE
        val bubbleX = FloatArray(items.size)
        val bubbleY = FloatArray(items.size)
        val bubbleDia = FloatArray(items.size)
        val bubbleDist = FloatArray(items.size) { Float.MAX_VALUE }
        val basePitch = Metrics.GRID_PITCH * u * (1f + 0.32f * ov)

        val isBezel = clusterTopology == ClusterTopology.CHRONO_BEZEL
        val isRiver = clusterTopology == ClusterTopology.RIVER
        val isOrbitals = clusterTopology == ClusterTopology.ORBITALS
        val isHelix = clusterTopology == ClusterTopology.DNA_HELIX
        val isHourglass = clusterTopology == ClusterTopology.HOURGLASS
        val isDock = clusterTopology == ClusterTopology.MAGNETIC_DOCK
        val isPool = clusterTopology == ClusterTopology.LIQUID_POOL

        ensureWakeCapacity(items.size)
        val bubbleDepthAlpha = FloatArray(items.size) { 1f }

        for (i in items.indices) {
            val item = items[i]
            if ((bFolder != null || bWidget != null) && i == bloomAnchorIndex && bVal > 0.15f) continue
            // In Chrono Bezel mode, item 0 (Core Clock) is rendered as the large central Chrono Watch HUD
            if (isBezel && i == 0 && item is WidgetBubble && item.kind == WidgetKind.CORE_CLOCK) continue

            computeTopologyWorldPos(i, items.size, basePitch, tmp)
            var worldX = tmp[0]
            var worldY = tmp[1]

            // Apply Jello / Wake Wave spring displacement & tilt parallax when physics is enabled
            if (bubblePhysics && !isPool && i in wakeDispX.indices) {
                worldX += wakeDispX[i] * (1f - 0.5f * ov)
                worldY += wakeDispY[i] * (1f - 0.5f * ov)
                val ringDist = if (i in gridHexes.indices) {
                    val hHex = gridHexes[i]
                    maxOf(abs(hHex.q), abs(hHex.r), abs(hHex.q + hHex.r)).toFloat()
                } else 1f
                val parallax = dp(5.5f) * (0.4f + 0.25f * ringDist) * (1f - ov)
                worldX += tiltX * parallax
                worldY += tiltY * parallax
            }

            if (bVal > 0.001f && i != bloomAnchorIndex) {
                val vx = worldX - anchorX
                val vy = worldY - anchorY
                val dist = hypot(vx, vy)
                if (dist > 1e-3f) {
                    val falloff = (1f / (1f + (dist / (u * 0.85f)))).coerceIn(0.35f, 1f)
                    worldX += (vx / dist) * shockwavePush * falloff
                    worldY += (vy / dist) * shockwavePush * falloff
                }
            }

            val px = if (isBezel || isRiver || isHelix || isHourglass || isDock || isPool) 0f else panX.value * (1f - ov)
            val py = if (isBezel || isDock || isPool) 0f else panY.value * (1f - ov)
            val dx = (worldX + px) / u
            val dy = (worldY + py) / u
            val d = hypot(dx / sx, dy / sy)
            bubbleDist[i] = d

            if (isBezel) {
                // In Chrono Bezel mode, the active item is the one closest to the 12 o'clock launch notch!
                val notchDist = hypot(worldX, worldY + basePitch * 1.56f)
                if (notchDist < nearestD) { nearestD = notchDist; nearest = item }
            } else if (d < nearestD) {
                nearestD = d
                nearest = item
            }

            val helixZ = if (isHelix) dnaHelixDepth(i) else 1f
            if (isHelix) {
                bubbleDepthAlpha[i] = (0.72f + 0.28f * helixZ).coerceIn(0.45f, 1f)
            }

            val fisheyeSize = when {
                isBezel -> {
                    val notchFactor = (1f - hypot(worldX, worldY + basePitch * 1.56f) / (basePitch * 1.15f)).coerceIn(0f, 1f)
                    0.68f + 0.34f * notchFactor
                }
                isHelix -> {
                    val depthScale = if (i == 0) 1.16f else (0.84f + 0.24f * helixZ)
                    Fisheye.size(d * 0.85f) * depthScale
                }
                isPool -> {
                    (if (i == 0) 1.08f else 0.84f) * Fisheye.size(d * 0.65f)
                }
                isDock -> {
                    (if (i == 0) 1.14f else 0.88f) * Fisheye.size(d * 0.72f)
                }
                else -> {
                    Fisheye.size(d) * gravityWeightFor(i, item)
                }
            }
            val size = fisheyeSize * (1f - ov) + 0.88f * ov
            if (size <= 0.02f) continue
            val pulledK = if (isBezel || isPool || isDock) 1f else if (d > 1e-4f) Fisheye.pulled(d) / d else 1f
            val k = pulledK * (1f - ov) + 1f * ov
            bubbleX[i] = cx + dx * k * u * effZoom
            bubbleY[i] = cy + dy * k * u * effZoom
            if ((isRiver || isHelix || isHourglass) && (bubbleY[i] < top + dp(52f) || bubbleY[i] > h - dp(84f))) {
                bubbleDia[i] = 0f
                continue
            }
            val scaleMod = 1f - 0.55f * cVal
            bubbleDia[i] = Metrics.GRID_CENTER_D * u * size * effZoom * scaleMod
        }

        val itemAlpha = alpha * (1f - 0.68f * bVal) * (1f - cVal)
        if (itemAlpha > 0.01f) {
            val aInt = (itemAlpha * 255).toInt().coerceIn(0, 255)

            // Topology-specific backdrop matrices, orbital rings, river tracks, or chrono bezel HUD
            when (clusterTopology) {
                ClusterTopology.ORBITALS -> {
                    val sunX = if (items.isNotEmpty() && bubbleDia[0] > 0f) bubbleX[0] else cx + panX.value * (1f - ov) * effZoom
                    val sunY = if (items.isNotEmpty() && bubbleDia[0] > 0f) bubbleY[0] else cy + panY.value * (1f - ov) * effZoom
                    val radii = floatArrayOf(basePitch * 1.06f * effZoom, basePitch * 1.98f * effZoom, basePitch * 2.88f * effZoom)
                    for ((rIdx, rad) in radii.withIndex()) {
                        stroke.strokeWidth = dp(if (rIdx == 0) 1.8f else 1.3f)
                        stroke.color = withAlpha(accent, (aInt * (0.36f - rIdx * 0.07f)).toInt())
                        c.drawCircle(sunX, sunY, rad, stroke)
                        // Astrolabe degree tick marks around each orbital ring
                        stroke.strokeWidth = dp(1.2f)
                        for (t in 0 until 24) {
                            val ang = t * (PI / 12.0) + orbitalAngle * (if (rIdx == 1) -0.72f else 0.5f)
                            val ca = cos(ang).toFloat()
                            val sa = sin(ang).toFloat()
                            c.drawLine(sunX + ca * (rad - dp(4f)), sunY + sa * (rad - dp(4f)), sunX + ca * (rad + dp(4f)), sunY + sa * (rad + dp(4f)), stroke)
                        }
                    }
                }
                ClusterTopology.RIVER -> {
                    val railOffset = basePitch * 1.38f * effZoom
                    stroke.strokeWidth = dp(1.6f)
                    stroke.color = withAlpha(accent, (aInt * 0.28f).toInt())
                    c.drawLine(cx - railOffset, top + dp(52f), cx - railOffset, h - dp(80f), stroke)
                    c.drawLine(cx + railOffset, top + dp(52f), cx + railOffset, h - dp(80f), stroke)
                    // Subtle river spine & connecting lattice
                    val maxLinkDist = basePitch * effZoom * 1.35f
                    stroke.strokeWidth = dp(1.2f)
                    for (i in items.indices) {
                        if (bubbleDia[i] <= 2f) continue
                        for (j in i + 1 until min(items.size, i + 5)) {
                            if (bubbleDia[j] <= 2f) continue
                            val dist = hypot(bubbleX[j] - bubbleX[i], bubbleY[j] - bubbleY[i])
                            if (dist <= maxLinkDist) {
                                stroke.color = withAlpha(accent, (aInt * 0.22f).toInt())
                                c.drawLine(bubbleX[i], bubbleY[i], bubbleX[j], bubbleY[j], stroke)
                            }
                        }
                    }
                }
                ClusterTopology.FIBONACCI -> {
                    // Connect Fibonacci parastichy spiral arms (steps of 3, 5, 8)
                    val steps = intArrayOf(3, 5, 8)
                    for (step in steps) {
                        stroke.strokeWidth = dp(if (step == 5) 1.8f else 1.2f)
                        stroke.color = withAlpha(accent, (aInt * (if (step == 5) 0.34f else 0.18f)).toInt())
                        for (i in 1 until items.size - step) {
                            if (bubbleDia[i] <= 2f || bubbleDia[i + step] <= 2f) continue
                            c.drawLine(bubbleX[i], bubbleY[i], bubbleX[i + step], bubbleY[i + step], stroke)
                        }
                    }
                }
                ClusterTopology.CHRONO_BEZEL -> {
                    drawChronoBezelHud(c, cx, cy, basePitch * effZoom, cal, itemAlpha)
                }
                ClusterTopology.DNA_HELIX -> {
                    // 🧬 Draw base-pair rungs connecting Strand A (odd) and Strand B (even), plus twin-strand backbones
                    stroke.strokeWidth = dp(1.8f)
                    var idx = 1
                    while (idx + 1 < items.size) {
                        if (bubbleDia[idx] > 2f && bubbleDia[idx + 1] > 2f) {
                            stroke.color = withAlpha(accent, (aInt * 0.34f).toInt())
                            c.drawLine(bubbleX[idx], bubbleY[idx], bubbleX[idx + 1], bubbleY[idx + 1], stroke)
                            // Central base-pair node dot
                            fill.color = withAlpha(accent, (aInt * 0.58f).toInt())
                            c.drawCircle((bubbleX[idx] + bubbleX[idx + 1]) * 0.5f, (bubbleY[idx] + bubbleY[idx + 1]) * 0.5f, dp(3f), fill)
                        }
                        idx += 2
                    }
                    // Connect each strand vertically
                    stroke.strokeWidth = dp(1.5f)
                    for (strandStart in 1..2) {
                        var cur = strandStart
                        while (cur + 2 < items.size) {
                            if (bubbleDia[cur] > 2f && bubbleDia[cur + 2] > 2f) {
                                stroke.color = if (strandStart == 1) withAlpha(accent, (aInt * 0.42f).toInt()) else Color.argb((aInt * 0.34f).toInt(), 100, 210, 255)
                                c.drawLine(bubbleX[cur], bubbleY[cur], bubbleX[cur + 2], bubbleY[cur + 2], stroke)
                            }
                            cur += 2
                        }
                    }
                }
                ClusterTopology.HOURGLASS -> {
                    // ⏳ Draw Chrono Hourglass funnel rails & waist rings
                    val topY = top + dp(56f)
                    val botY = h - dp(86f)
                    val waistW = basePitch * 0.62f * effZoom
                    val rimW = basePitch * 2.15f * effZoom
                    stroke.strokeWidth = dp(1.7f)
                    stroke.color = withAlpha(accent, (aInt * 0.32f).toInt())
                    c.drawLine(cx - rimW, topY, cx - waistW, cy, stroke)
                    c.drawLine(cx - waistW, cy, cx - rimW, botY, stroke)
                    c.drawLine(cx + rimW, topY, cx + waistW, cy, stroke)
                    c.drawLine(cx + waistW, cy, cx + rimW, botY, stroke)
                    stroke.strokeWidth = dp(1.3f)
                    stroke.color = withAlpha(accent, (aInt * 0.45f).toInt())
                    c.drawCircle(cx, cy, waistW * 0.92f, stroke)
                }
                ClusterTopology.MAGNETIC_DOCK -> {
                    // 🧲 Draw concentric thumb-reach arc guides radiating from bottom-center
                    val pivotY = cy + basePitch * 2.15f * effZoom
                    for (aIdx in 0..3) {
                        val rArc = basePitch * (1.28f + aIdx * 0.88f) * effZoom
                        arcRect.set(cx - rArc, pivotY - rArc, cx + rArc, pivotY + rArc)
                        stroke.strokeWidth = dp(if (aIdx == 0) 1.8f else 1.2f)
                        stroke.color = withAlpha(accent, (aInt * (0.34f - aIdx * 0.06f)).toInt())
                        c.drawArc(arcRect, -162f, 144f, false, stroke)
                    }
                }
                ClusterTopology.LIQUID_POOL -> {
                    // 🫧 Draw Zero-G Billiards Containment Tank ring & tilt vector indicator
                    val tankR = basePitch * 2.55f * effZoom
                    fill.color = Color.argb((aInt * 0.25f).toInt(), 12, 22, 32)
                    c.drawCircle(cx, cy, tankR, fill)
                    stroke.strokeWidth = dp(2.2f)
                    stroke.color = withAlpha(accent, (aInt * 0.55f).toInt())
                    c.drawCircle(cx, cy, tankR, stroke)
                    // Subtle inner sonar grid rings
                    stroke.strokeWidth = dp(1.0f)
                    stroke.color = withAlpha(accent, (aInt * 0.16f).toInt())
                    c.drawCircle(cx, cy, tankR * 0.66f, stroke)
                    c.drawCircle(cx, cy, tankR * 0.33f, stroke)
                }
                else -> {
                    // Reactive Hexagonal Lattice Matrix (used by HONEYCOMB & GRAVITY)
                    val maxLinkDist = basePitch * effZoom * 1.42f
                    stroke.strokeWidth = dp(1.3f)
                    for (i in items.indices) {
                        if (bubbleDia[i] <= 2f) continue
                        val x1 = bubbleX[i]; val y1 = bubbleY[i]
                        for (j in i + 1 until items.size) {
                            if (bubbleDia[j] <= 2f) continue
                            val x2 = bubbleX[j]; val y2 = bubbleY[j]
                            val dist = hypot(x2 - x1, y2 - y1)
                            if (dist <= maxLinkDist) {
                                val centerBoost = (1f - min(bubbleDist[i], bubbleDist[j]) * 1.4f).coerceIn(0.18f, 1f)
                                stroke.color = withAlpha(accent, (itemAlpha * 58f * centerBoost).toInt())
                                c.drawLine(x1, y1, x2, y2, stroke)
                            }
                        }
                    }
                }
            }

            // Expandable Shockwave Ripple ring when triggered by double-tap or shake
            if (shockwaveProgress < 1f) {
                val swR = shockwaveProgress * u * 1.65f
                val swAlpha = ((1f - shockwaveProgress) * itemAlpha * 220f).toInt().coerceIn(0, 255)
                stroke.strokeWidth = dp(3.2f * (1f - shockwaveProgress * 0.6f))
                stroke.color = withAlpha(accent, swAlpha)
                c.drawCircle(shockwaveOriginX, shockwaveOriginY, swR, stroke)
            }

            // Draw each bubble + Gravity hero rings + Chrono Bezel top notch highlight + Overview labels
            // For DNA_HELIX, draw back-strand bubbles first so front-strand bubbles overlap cleanly in 3D!
            val drawOrder = if (isHelix) {
                items.indices.sortedBy { dnaHelixDepth(it) }
            } else {
                items.indices.toList()
            }
            for (i in drawOrder) {
                val dia = bubbleDia[i]
                if (dia <= 2f) continue
                val item = items[i]
                val x = bubbleX[i]
                val y = bubbleY[i]
                val bAlpha = itemAlpha * bubbleDepthAlpha[i]

                if (clusterTopology == ClusterTopology.GRAVITY && gravityWeightFor(i, item) >= 1.20f) {
                    stroke.strokeWidth = dp(1.8f)
                    stroke.color = withAlpha(accent, (aInt * 0.45f).toInt())
                    c.drawCircle(x, y, dia * 0.54f, stroke)
                }
                if (isBezel && item == nearest) {
                    stroke.strokeWidth = dp(2.4f)
                    stroke.color = withAlpha(accent, aInt)
                    c.drawCircle(x, y, dia * 0.55f, stroke)
                }
                if (isPool && i == grabbedPoolIndex && dragging) {
                    stroke.strokeWidth = dp(2.4f)
                    stroke.color = withAlpha(accent, aInt)
                    c.drawCircle(x, y, dia * 0.56f, stroke)
                }

                drawGridItem(c, item, x, y, dia, bAlpha, cal)
                if (bVal < 0.5f && cVal < 0.3f) hits += Hit(x, y, dia / 2, item, i)

                // Overview Cluster Labels on Folder bubbles when zoomed out (ov > 0.35f)
                if (ov > 0.35f && item is Folder) {
                    val ovAlpha = ((ov - 0.35f) / 0.5f).coerceIn(0f, 1f) * itemAlpha
                    val ovInt = (ovAlpha * 255).toInt()
                    text.textSize = sp(9f)
                    val rawLbl = "${item.group.title.substringBefore(' ')} · ${item.apps.size}"
                    val lbl = TextUtils.ellipsize(rawLbl, text, dia * 1.55f, TextUtils.TruncateAt.END).toString()
                    val lw = text.measureText(lbl)
                    val lRect = RectF(x - lw / 2 - dp(5f), y + dia * 0.46f, x + lw / 2 + dp(5f), y + dia * 0.46f + dp(14f))
                    fill.color = Color.argb((ovInt * 0.90f).toInt(), 18, 20, 26)
                    c.drawRoundRect(lRect, dp(7f), dp(7f), fill)
                    val tint = item.group.tint
                    stroke.strokeWidth = dp(1f)
                    stroke.color = withAlpha(tint, (ovInt * 0.7f).toInt())
                    c.drawRoundRect(lRect, dp(7f), dp(7f), stroke)
                    text.color = Color.argb(ovInt, 255, 255, 255)
                    c.drawText(lbl, x - lw / 2, lRect.centerY() + text.textSize * 0.35f, text)
                }
            }
        }

        // #4: Unraveled Star Constellation — unravels all folders into connected star constellations on screen by default!
        if (cVal > 0.001f || gridSearching) {
            drawConstellationSearch(c, w, h, top, alpha * max(cVal, if (gridSearching) 1f else 0f), cVal)
            return
        }

        // #2: Draw the in-place Bloomed Cluster Island on top of the parted honeycomb
        if (bFolder != null && fVal > 0.001f) {
            drawBloomedIsland(c, w, h, top, bFolder, fVal, alpha)
            return
        }

        // #5: Draw the in-place Blooming Widget Pod on top of the parted honeycomb
        if (bWidget != null && wVal > 0.001f) {
            drawBloomedWidgetPod(c, w, h, top, bWidget, wVal, cal, alpha)
            return
        }

        if (dragging && nearest != null && nearest.key != centredItemKey && centredItemKey.isNotEmpty()) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        if (nearest != null) centredItemKey = nearest.key

        drawGridHeader(c, w, top, nearest, ov, alpha)
    }

    /** 🎡 Draws the Live Center Chrono Watch HUD & rotating bezel tracks for [ClusterTopology.CHRONO_BEZEL]. */
    private fun drawChronoBezelHud(c: Canvas, cx: Float, cy: Float, scaledPitch: Float, cal: Calendar, alpha: Float) {
        val aInt = (alpha * 255).toInt().coerceIn(0, 255)
        val rInnerBezel = scaledPitch * 1.56f
        val rOuterBezel = scaledPitch * 2.40f
        val hudR = scaledPitch * 0.94f

        // Concentric bezel tracks
        stroke.strokeWidth = dp(1.6f)
        stroke.color = withAlpha(accent, (aInt * 0.32f).toInt())
        c.drawCircle(cx, cy, rInnerBezel, stroke)
        stroke.strokeWidth = dp(1.2f)
        stroke.color = withAlpha(accent, (aInt * 0.20f).toInt())
        c.drawCircle(cx, cy, rOuterBezel, stroke)

        // Center Chrono Watch Dial HUD platter
        chronoHudRect.set(cx - hudR, cy - hudR, cx + hudR, cy + hudR)
        fill.color = Color.argb((aInt * 0.95f).toInt(), 16, 20, 26)
        c.drawCircle(cx, cy, hudR, fill)
        stroke.strokeWidth = dp(2.2f)
        stroke.color = withAlpha(accent, (aInt * 0.78f).toInt())
        c.drawCircle(cx, cy, hudR, stroke)

        // 60 perimeter chronometer ticks around the center HUD
        val sec = cal.get(Calendar.SECOND)
        val tickOuter = hudR * 0.93f
        for (t in 0 until 60) {
            val ang = Math.toRadians(t * 6.0 - 90.0)
            val ca = cos(ang).toFloat()
            val sa = sin(ang).toFloat()
            val major = t % 5 == 0
            val tickInner = hudR * (if (major) 0.81f else 0.86f)
            stroke.strokeWidth = dp(if (major) 1.8f else 1.0f)
            stroke.color = if (t <= sec) withAlpha(accent, aInt) else Color.argb((aInt * 0.26f).toInt(), 150, 155, 165)
            c.drawLine(cx + ca * tickInner, cy + sa * tickInner, cx + ca * tickOuter, cy + sa * tickOuter, stroke)
        }

        // Date at top of HUD
        val dayStr = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(cal.time).uppercase()
        text.textSize = (hudR * 0.145f).coerceAtLeast(sp(9f))
        text.color = withAlpha(accent, aInt)
        c.drawText(dayStr, cx - text.measureText(dayStr) / 2, cy - hudR * 0.34f, text)

        // Large digital time in center of HUD
        val hm = DateFormat.getTimeInstance(DateFormat.SHORT).format(cal.time).substringBefore(' ')
        text.textSize = (hudR * 0.42f).coerceAtLeast(sp(18f))
        text.color = Color.argb(aInt, 255, 255, 255)
        c.drawText(hm, cx - text.measureText(hm) / 2, cy + text.textSize * 0.34f, text)

        // Battery / Timer status at bottom of HUD
        val remMs = currentTimerRemainingMs()
        val sub = if (timerRunning) {
            val remSec = ((remMs + 999L) / 1000L).toInt()
            "⏱ %d:%02d".format(remSec / 60, remSec % 60)
        } else if (info.battery >= 0) {
            "🔋 ${info.battery}% • SPIN BEZEL"
        } else {
            "SPIN BEZEL"
        }
        text.textSize = (hudR * 0.135f).coerceAtLeast(sp(8.5f))
        text.color = Color.argb((aInt * 0.85f).toInt(), 190, 205, 195)
        c.drawText(sub, cx - text.measureText(sub) / 2, cy + hudR * 0.54f, text)

        // Top 12 o'clock Launch Notch indicator pointing to the magnified bezel bubble
        text.textSize = sp(11f)
        text.color = withAlpha(accent, aInt)
        val notch = "▲"
        c.drawText(notch, cx - text.measureText(notch) / 2, cy - hudR - dp(4f), text)
    }

    /** 🌌 Draws the Expanded Category Galaxy Islands in space for [ClusterTopology.NEBULA]. */
    private fun drawNebulaIslands(
        c: Canvas,
        w: Float,
        h: Float,
        top: Float,
        cal: Calendar,
        alpha: Float,
        effZoom: Float,
        ov: Float,
        bFolder: Folder?,
        bWidget: WidgetBubble?,
        fVal: Float,
        wVal: Float,
        bVal: Float,
    ) {
        val u = unit
        val cx = w / 2
        val cy = h / 2
        val sx = max(1f, w / u)
        val sy = max(1f, (h / u) / Metrics.WATCH_ASPECT)
        val itemAlpha = alpha * (1f - 0.68f * bVal)
        val aInt = (itemAlpha * 255).toInt().coerceIn(0, 255)
        val basePitch = Metrics.GRID_PITCH * u * (1f + 0.25f * ov)
        val px = panX.value * (1f - ov)
        val py = panY.value * (1f - ov)

        var nearest: GridItem? = null
        var nearestD = Float.MAX_VALUE

        fun worldToScreen(wx: Float, wy: Float, scaleBoost: Float = 1f): Triple<Float, Float, Float> {
            val dx = (wx + px) / u
            val dy = (wy + py) / u
            val d = hypot(dx / sx, dy / sy)
            val fisheyeSize = Fisheye.size(d * 0.92f) * scaleBoost
            val size = fisheyeSize * (1f - ov) + 0.78f * ov
            if (size <= 0.02f) return Triple(-9999f, -9999f, 0f)
            val pulledK = if (d > 1e-4f) Fisheye.pulled(d) / d else 1f
            val k = pulledK * (1f - ov) + 1f * ov
            val sxPos = cx + dx * k * u * effZoom
            val syPos = cy + dy * k * u * effZoom
            val dia = Metrics.GRID_CENTER_D * u * size * effZoom
            return Triple(sxPos, syPos, dia)
        }

        val (hubSx, hubSy, _) = worldToScreen(0f, 0f)
        val groups = dev.watchreborn.apps.AppGroup.entries.filter { g -> alphabetical.any { it.defaultGroup == g } }
        val galaxyDist = basePitch * 2.95f

        // 1. Draw warp-lines from Center Core Hub to each Category Nebula Island + Nebula Halo discs
        for ((gIdx, grp) in groups.withIndex()) {
            val ang = gIdx * (2.0 * PI / groups.size.coerceAtLeast(1)) - PI / 2.0
            val gx = (cos(ang) * galaxyDist).toFloat()
            val gy = (sin(ang) * galaxyDist).toFloat()
            val grpCount = alphabetical.count { it.defaultGroup == grp }
            val (gsx, gsy, _) = worldToScreen(gx, gy)
            if (gsx > -1000f && hubSx > -1000f) {
                stroke.strokeWidth = dp(2.0f)
                stroke.color = withAlpha(grp.tint, (aInt * 0.42f).toInt())
                c.drawLine(hubSx, hubSy, gsx, gsy, stroke)
            }
            if (gsx > -1000f) {
                val haloFactor = when {
                    grpCount <= 1 -> 0.56f
                    grpCount <= 4 -> 0.92f
                    else -> 1.22f
                }
                val haloR = basePitch * haloFactor * effZoom * (1f - 0.25f * ov)
                fill.color = withAlpha(grp.tint, (aInt * 0.14f).toInt())
                c.drawCircle(gsx, gsy, haloR, fill)
                stroke.strokeWidth = dp(1.5f)
                stroke.color = withAlpha(grp.tint, (aInt * 0.58f).toInt())
                c.drawCircle(gsx, gsy, haloR, stroke)
            }
        }

        // 2. Draw Center Core Hub (Live Complication Widgets)
        val widgets = activeGridItems.filterIsInstance<WidgetBubble>()
        val wHexes = Hex.spiral(widgets.size)
        for (i in widgets.indices) {
            pointyCenter(wHexes[i], basePitch * 0.88f, tmp)
            val wx = tmp[0]; val wy = tmp[1]
            val d = hypot((wx + px) / (u * sx), (wy + py) / (u * sy))
            val wItem = widgets[i]
            if (d < nearestD) { nearestD = d; nearest = wItem }
            val (sxPos, syPos, dia) = worldToScreen(wx, wy, 0.94f)
            if (dia <= 2f) continue
            drawGridItem(c, wItem, sxPos, syPos, dia, itemAlpha, cal)
            if (bVal < 0.5f) hits += Hit(sxPos, syPos, dia / 2, wItem, i)
        }

        // 3. Draw each Category Nebula Island's expanded member apps + tappable Galaxy Header Pill
        for ((gIdx, grp) in groups.withIndex()) {
            val ang = gIdx * (2.0 * PI / groups.size.coerceAtLeast(1)) - PI / 2.0
            val gx = (cos(ang) * galaxyDist).toFloat()
            val gy = (sin(ang) * galaxyDist).toFloat()
            val grpApps = byUse.filter { it.defaultGroup == grp }
            val gHexes = Hex.spiral(grpApps.size)
            for (i in grpApps.indices) {
                pointyCenter(gHexes[i], basePitch * 0.76f, tmp)
                val wx = gx + tmp[0]
                val wy = gy + tmp[1]
                val d = hypot((wx + px) / (u * sx), (wy + py) / (u * sy))
                val app = grpApps[i]
                if (d < nearestD) { nearestD = d; nearest = app }
                val (sxPos, syPos, dia) = worldToScreen(wx, wy, 0.78f)
                if (dia <= 2f) continue
                drawGridItem(c, app, sxPos, syPos, dia, itemAlpha, cal)
                if (bVal < 0.5f) hits += Hit(sxPos, syPos, dia / 2, app)
            }

            // Galaxy Header Pill above the Nebula Island (tap to fly camera to this island!)
            val (gsx, gsy, _) = worldToScreen(gx, gy)
            if (gsx > -1000f) {
                val haloFactor = when {
                    grpApps.size <= 1 -> 0.56f
                    grpApps.size <= 4 -> 0.92f
                    else -> 1.22f
                }
                val pillY = gsy - basePitch * haloFactor * effZoom * (1f - 0.25f * ov) - dp(10f)
                text.textSize = sp(10f)
                val gLabel = "🌌 ${grp.title.substringBefore(' ')} · ${grpApps.size}"
                val gw = text.measureText(gLabel)
                val gRect = RectF(gsx - gw / 2 - dp(8f), pillY - dp(11f), gsx + gw / 2 + dp(8f), pillY + dp(11f))
                fill.color = Color.argb((aInt * 0.92f).toInt(), 20, 22, 28)
                c.drawRoundRect(gRect, dp(11f), dp(11f), fill)
                stroke.strokeWidth = dp(1.2f)
                stroke.color = withAlpha(grp.tint, aInt)
                c.drawRoundRect(gRect, dp(11f), dp(11f), stroke)
                text.color = Color.argb(aInt, 255, 255, 255)
                c.drawText(gLabel, gsx - gw / 2, gRect.centerY() + text.textSize * 0.35f, text)
                nebulaHeaderRects += Triple(gRect, gx, gy)
            }
        }

        if (bFolder != null && fVal > 0.001f) {
            drawBloomedIsland(c, w, h, top, bFolder, fVal, alpha)
            return
        }
        if (bWidget != null && wVal > 0.001f) {
            drawBloomedWidgetPod(c, w, h, top, bWidget, wVal, cal, alpha)
            return
        }

        if (dragging && nearest != null && nearest.key != centredItemKey && centredItemKey.isNotEmpty()) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        if (nearest != null) centredItemKey = nearest.key

        drawGridHeader(c, w, top, nearest, ov, alpha)
    }

    /**
     * #4: Unravels all folders into a live, pan-able Star Constellation on the screen by default
     * (with glowing constellation lines connecting category stars, 1-tap A–Z range filter chips,
     * and live keyboard filtering when the top search bar is tapped).
     */
    private fun drawConstellationSearch(c: Canvas, w: Float, h: Float, top: Float, alpha: Float, cVal: Float) {
        val u = unit
        val cx = w / 2
        val cy = if (searching) top + dp(248f) else h / 2 + dp(28f)
        val sx = max(1f, w / u)
        val sy = max(1f, (h / u) / Metrics.WATCH_ASPECT)
        val aInt = (alpha * 255).toInt().coerceIn(0, 255)
        val q = searchQuery.trim()
        val cz = constellationZoom.value

        // Build the unraveled star list:
        // - If typing a search query, rank prefix matches first, then substring matches.
        // - If a category constellation chip is active, filter to that category.
        // - If a 1-tap letter range chip (e.g. "A-D") is active, filter to that letter range.
        // - Otherwise ("ALL"), unravel EVERY app grouped by category compass neighborhood!
        val catFilter = constellationCategoryFilter
        val stars: List<App> = when {
            q.isNotEmpty() -> {
                val prefix = alphabetical.filter { it.label.startsWith(q, ignoreCase = true) }
                val sub = alphabetical.filter { !it.label.startsWith(q, ignoreCase = true) && it.label.contains(q, ignoreCase = true) }
                prefix + sub
            }
            catFilter != null -> {
                alphabetical.filter { it.defaultGroup == catFilter }
            }
            constellationFilter != "ALL" -> {
                val startCh = constellationFilter.first()
                val endCh = constellationFilter.last()
                alphabetical.filter {
                    val ch = it.label.firstOrNull()?.uppercaseChar() ?: ' '
                    ch in startCh..endCh
                }
            }
            else -> {
                alphabetical.sortedWith(compareBy<App> { it.defaultGroup.compassOrder }.thenBy { it.label })
            }
        }

        val cHexes = Hex.spiral(stars.size)
        val pitch = Metrics.GRID_PITCH * u * (if (stars.size <= 14) 1.18f else 1.08f) * cz
        val cpx = if (searching) 0f else constellationPanX.value * cVal
        val cpy = if (searching) 0f else constellationPanY.value * cVal

        // Map each app or category group to its parent folder's world position on the main grid so stars
        // physically unravel out of their folder bubbles when [cVal] animates 0 -> 1!
        val items = activeGridItems
        val groupOrigin = HashMap<dev.watchreborn.apps.AppGroup, Pair<Float, Float>>()
        for (i in items.indices) {
            if (i >= gridHexes.size) break
            val it = items[i]
            if (it is Folder) {
                pointyCenter(gridHexes[i], Metrics.GRID_PITCH * u, tmp)
                groupOrigin[it.group] = tmp[0] to tmp[1]
            }
        }

        // Compute screen coordinates for visible stars so we can draw glowing constellation lines first
        val starCoords = ArrayList<Triple<App, Float, Float>>(stars.size)
        val starDiameters = FloatArray(stars.size)
        val starCenterDist = FloatArray(stars.size)

        for (i in stars.indices) {
            val app = stars[i]
            pointyCenter(cHexes[i], pitch, tmp)
            val targetX = tmp[0]
            val targetY = tmp[1]
            val orig = groupOrigin[app.defaultGroup]
            val ox = orig?.first ?: 0f
            val oy = orig?.second ?: 0f
            val worldX = ox * (1f - cVal) + targetX * cVal
            val worldY = oy * (1f - cVal) + targetY * cVal

            val dx = (worldX + cpx) / u
            val dy = (worldY + cpy) / u
            val d = hypot(dx / sx, dy / sy)
            starCenterDist[i] = d
            val size = Fisheye.size(d * 0.88f)
            if (size <= 0.02f) {
                starCoords += Triple(app, -9999f, -9999f)
                starDiameters[i] = 0f
                continue
            }
            val k = if (d > 1e-4f) Fisheye.pulled(d) / d else 1f
            val x = cx + dx * k * u
            val y = cy + dy * k * u
            val dia = Metrics.GRID_CENTER_D * u * size * 0.72f * cz.coerceIn(0.8f, 1.25f)
            starCoords += Triple(app, x, y)
            starDiameters[i] = dia
        }

        // 1. Draw glowing category Constellation Lines linking stars in the same group (or adjacent matches)
        for (i in starCoords.indices) {
            val (curApp, x1, y1) = starCoords[i]
            if (x1 < -1000f) continue
            for (j in i + 1 until starCoords.size) {
                val (otherApp, x2, y2) = starCoords[j]
                if (x2 < -1000f) continue
                val sameCategory = curApp.defaultGroup == otherApp.defaultGroup
                if (sameCategory || (q.isNotEmpty() && j == i + 1)) {
                    val dist = hypot(x2 - x1, y2 - y1)
                    if (dist < u * 0.60f * cz) {
                        val lineTint = if (q.isNotEmpty()) accent else curApp.defaultGroup.tint
                        // Outer soft glow line
                        stroke.strokeWidth = dp(4.5f)
                        stroke.color = withAlpha(lineTint, (aInt * 0.22f * cVal).toInt())
                        c.drawLine(x1, y1, x2, y2, stroke)
                        // Bright inner constellation filament
                        stroke.strokeWidth = dp(1.8f)
                        stroke.color = withAlpha(lineTint, (aInt * 0.72f * cVal).toInt())
                        c.drawLine(x1, y1, x2, y2, stroke)
                    }
                }
            }
        }

        // 2. Draw each unraveled star bubble + category halo ring + labels on the central constellation stars
        for (i in starCoords.indices) {
            val (app, x, y) = starCoords[i]
            val dia = starDiameters[i]
            if (dia <= 2f || x < -1000f) continue

            // Dark star platter so constellation lines terminate cleanly at the star's rim
            fill.color = Color.argb((aInt * 0.90f).toInt(), 12, 14, 18)
            c.drawCircle(x, y, dia * 0.55f, fill)

            val ringColor = if (q.isNotEmpty()) accent else app.defaultGroup.tint
            stroke.strokeWidth = dp(2.2f)
            stroke.color = withAlpha(ringColor, (aInt * 0.90f).toInt())
            c.drawCircle(x, y, dia * 0.54f, stroke)

            drawIcon(c, app.icon, x, y, dia, alpha, hasNotification(app), app.key in pinnedItemKeys)
            hits += Hit(x, y, dia / 2, app)

            // Show name pill under the stars closest to the center (or top 9 when filtered)
            if (starCenterDist[i] < 0.42f || (i < 9 && (q.isNotEmpty() || constellationFilter != "ALL" || catFilter != null))) {
                text.textSize = sp(9.5f)
                val lbl = TextUtils.ellipsize(app.label, text, dia * 1.38f, TextUtils.TruncateAt.END).toString()
                val lw = text.measureText(lbl)
                val lRect = RectF(x - lw / 2 - dp(6f), y + dia * 0.48f, x + lw / 2 + dp(6f), y + dia * 0.48f + dp(16f))
                fill.color = Color.argb((aInt * 0.92f).toInt(), 18, 20, 26)
                c.drawRoundRect(lRect, dp(8f), dp(8f), fill)
                stroke.strokeWidth = dp(1f)
                stroke.color = withAlpha(ringColor, (aInt * 0.55f).toInt())
                c.drawRoundRect(lRect, dp(8f), dp(8f), stroke)
                text.color = Color.argb(aInt, 255, 255, 255)
                c.drawText(lbl, x - lw / 2, lRect.centerY() + text.textSize * 0.35f, text)
            }
        }

        // 3. Top Constellation Search Bar (tap to open keyboard, or tap X to collapse)
        val barH = dp(42f)
        val padX = dp(16f)
        searchBarRect.set(padX, top + dp(6f), w - padX, top + dp(6f) + barH)
        fill.color = Color.argb((aInt * 0.94f).toInt(), 32, 34, 40)
        c.drawRoundRect(searchBarRect, barH / 2, barH / 2, fill)
        stroke.strokeWidth = dp(1.6f)
        stroke.color = withAlpha(catFilter?.tint ?: accent, aInt)
        c.drawRoundRect(searchBarRect, barH / 2, barH / 2, stroke)

        text.textSize = sp(13.5f)
        val displayStr = when {
            q.isNotEmpty() -> "🔍 $searchQuery|  (${stars.size} stars)"
            searching -> "🔍 Type app name...  (${stars.size})"
            catFilter != null -> "✨ ${catFilter.title} (${stars.size} apps) • Tap to type"
            else -> "✨ Unraveled (${stars.size} apps) • Tap to type"
        }
        text.color = if (q.isEmpty() && !searching) Color.argb((aInt * 0.85f).toInt(), 215, 220, 230) else Color.argb(aInt, 255, 255, 255)
        c.drawText(displayStr, searchBarRect.left + dp(16f), searchBarRect.centerY() + text.textSize * 0.35f, text)

        searchClearRect.set(searchBarRect.right - barH, searchBarRect.top, searchBarRect.right, searchBarRect.bottom)
        text.color = withAlpha(accent, aInt)
        val xStr = "✕"
        c.drawText(xStr, searchClearRect.centerX() - text.measureText(xStr) / 2, searchClearRect.centerY() + text.textSize * 0.35f, text)

        // 4. Row 1: 1-Tap Quick Letter-Cluster Filter Chips (no keyboard needed!)
        constellationChipRects.clear()
        val chips = listOf("ALL", "A-D", "E-I", "J-N", "O-S", "T-Z")
        val chipY = searchBarRect.bottom + dp(7f)
        val chipH = dp(26f)
        val chipGap = dp(5f)
        val totalChipW = w - 2 * padX
        val eachW = (totalChipW - chipGap * (chips.size - 1)) / chips.size
        text.textSize = sp(10.5f)
        for (i in chips.indices) {
            val chip = chips[i]
            val active = q.isEmpty() && catFilter == null && constellationFilter == chip
            val cl = padX + i * (eachW + chipGap)
            val cr = RectF(cl, chipY, cl + eachW, chipY + chipH)
            fill.color = if (active) withAlpha(accent, aInt) else Color.argb((aInt * 0.88f).toInt(), 40, 42, 48)
            c.drawRoundRect(cr, chipH / 2, chipH / 2, fill)
            text.color = if (active) Color.argb(aInt, 0, 0, 0) else Color.argb(aInt, 225, 228, 235)
            c.drawText(chip, cr.centerX() - text.measureText(chip) / 2, cr.centerY() + text.textSize * 0.35f, text)
            constellationChipRects += cr to chip
        }

        // 5. Row 2: 1-Tap Category Constellation Filter Chips (color-coded by category tint!)
        constellationCategoryChipRects.clear()
        val catGroups = dev.watchreborn.apps.AppGroup.entries.filter { g -> alphabetical.any { it.defaultGroup == g } }
        if (catGroups.isNotEmpty()) {
            val catY = chipY + chipH + dp(5f)
            val catEachW = (totalChipW - chipGap * (catGroups.size - 1)) / catGroups.size
            text.textSize = sp(9.5f)
            for (i in catGroups.indices) {
                val g = catGroups[i]
                val active = q.isEmpty() && catFilter == g
                val cl = padX + i * (catEachW + chipGap)
                val cr = RectF(cl, catY, cl + catEachW, catY + chipH)
                fill.color = if (active) withAlpha(g.tint, aInt) else Color.argb((aInt * 0.86f).toInt(), 30, 32, 38)
                c.drawRoundRect(cr, chipH / 2, chipH / 2, fill)
                if (!active) {
                    stroke.strokeWidth = dp(1.2f)
                    stroke.color = withAlpha(g.tint, (aInt * 0.72f).toInt())
                    c.drawRoundRect(cr, chipH / 2, chipH / 2, stroke)
                }
                text.color = if (active) Color.BLACK else withAlpha(g.tint, aInt)
                val shortName = g.title.substringBefore(' ').substringBefore('&').trim()
                c.drawText(shortName, cr.centerX() - text.measureText(shortName) / 2, cr.centerY() + text.textSize * 0.35f, text)
                constellationCategoryChipRects += cr to g
            }
        }
    }

    /** #2: Draws the in-place blooming cluster island at the center of the parted honeycomb. */
    private fun drawBloomedIsland(c: Canvas, w: Float, h: Float, top: Float, folder: Folder, bVal: Float, alpha: Float) {
        val u = unit
        val cx = w / 2
        val cy = h / 2
        val islandR = min(w * 0.45f, u * 0.56f) * bVal
        val aInt = (alpha * bVal * 255).toInt().coerceIn(0, 255)

        fill.color = Color.argb((aInt * 0.90f).toInt(), 20, 22, 26)
        c.drawCircle(cx, cy, islandR, fill)
        stroke.strokeWidth = dp(2.5f)
        stroke.color = withAlpha(folder.group.tint, (aInt * 0.85f).toInt())
        c.drawCircle(cx, cy, islandR, stroke)

        c.save()
        clipPath.reset()
        clipPath.addCircle(cx, cy, islandR - dp(2f), Path.Direction.CW)
        c.clipPath(clipPath)

        for (i in folder.apps.indices) {
            if (i >= folderHexes.size) break
            pointyCenter(folderHexes[i], Metrics.GRID_PITCH * u * 0.92f * bVal, tmp)
            val dx = (tmp[0] + folderPanX.value * bVal) / u
            val dy = (tmp[1] + folderPanY.value * bVal) / u
            val d = hypot(dx, dy)
            val size = Fisheye.size(d * 1.15f)
            if (size <= 0.02f) continue
            val k = if (d > 1e-4f) Fisheye.pulled(d) / d else 1f
            val x = cx + dx * k * u
            val y = cy + dy * k * u
            val dia = Metrics.GRID_CENTER_D * u * size * 0.92f * bVal
            val app = folder.apps[i]
            drawIcon(c, app.icon, x, y, dia, alpha * bVal, hasNotification(app), app.key in pinnedItemKeys)
            if (hypot(x - cx, y - cy) <= islandR) {
                hits += Hit(x, y, dia / 2, app)
            }
        }
        c.restore()

        val ph = dp(38f)
        val label = "🌊 ${folder.group.title} (${folder.apps.size})   ✕"
        text.textSize = sp(14.5f)
        val tw = text.measureText(label)
        val pw = tw + dp(32f)
        folderHeaderRect.set(w / 2 - pw / 2, top + dp(8f), w / 2 + pw / 2, top + dp(8f) + ph)
        fill.color = Color.argb((aInt * 0.92f).toInt(), 38, 40, 44)
        c.drawRoundRect(folderHeaderRect, ph / 2, ph / 2, fill)
        stroke.strokeWidth = dp(1.5f)
        stroke.color = withAlpha(folder.group.tint, aInt)
        c.drawRoundRect(folderHeaderRect, ph / 2, ph / 2, stroke)
        text.color = Color.argb(aInt, 255, 255, 255)
        c.drawText(label, w / 2 - tw / 2, folderHeaderRect.centerY() + text.textSize * 0.35f, text)
    }

    /** #5: Draws an interactive Blooming Widget Pod at the center of the parted honeycomb! */
    private fun drawBloomedWidgetPod(
        c: Canvas,
        w: Float,
        h: Float,
        top: Float,
        widget: WidgetBubble,
        wVal: Float,
        cal: Calendar,
        alpha: Float,
    ) {
        val u = unit
        val cx = w / 2
        val cy = h / 2
        val islandR = min(w * 0.45f, u * 0.56f) * wVal
        val aInt = (alpha * wVal * 255).toInt().coerceIn(0, 255)
        val tint = widget.kind.tint

        widgetActionRects.clear()

        // Outer glass pod platter + glowing perimeter ring
        fill.color = Color.argb((aInt * 0.94f).toInt(), 18, 20, 26)
        c.drawCircle(cx, cy, islandR, fill)
        stroke.strokeWidth = dp(2.8f)
        stroke.color = withAlpha(tint, (aInt * 0.90f).toInt())
        c.drawCircle(cx, cy, islandR, stroke)

        c.save()
        clipPath.reset()
        clipPath.addCircle(cx, cy, islandR - dp(3f), Path.Direction.CW)
        c.clipPath(clipPath)

        fun drawPodBtn(bx: Float, by: Float, bw: Float, bh: Float, label: String, actionId: String, active: Boolean = false, btnTint: Int = tint) {
            val r = RectF(bx - bw / 2, by - bh / 2, bx + bw / 2, by + bh / 2)
            fill.color = if (active) withAlpha(btnTint, aInt) else Color.argb((aInt * 0.92f).toInt(), 38, 42, 50)
            c.drawRoundRect(r, bh / 2, bh / 2, fill)
            if (!active) {
                stroke.strokeWidth = dp(1.2f)
                stroke.color = withAlpha(btnTint, (aInt * 0.65f).toInt())
                c.drawRoundRect(r, bh / 2, bh / 2, stroke)
            }
            text.textSize = sp(11.5f)
            text.color = if (active) Color.BLACK else Color.argb(aInt, 245, 247, 250)
            val ell = TextUtils.ellipsize(label, text, bw - dp(12f), TextUtils.TruncateAt.END).toString()
            c.drawText(ell, r.centerX() - text.measureText(ell) / 2, r.centerY() + text.textSize * 0.35f, text)
            widgetActionRects += r to actionId
        }

        when (widget.kind) {
            WidgetKind.CORE_CLOCK -> {
                val dayStr = SimpleDateFormat("EEEE • MMM d", Locale.getDefault()).format(cal.time).uppercase()
                text.textSize = sp(11f)
                text.color = withAlpha(accent, aInt)
                c.drawText(dayStr, cx - text.measureText(dayStr) / 2, cy - islandR * 0.56f, text)

                val hm = DateFormat.getTimeInstance(DateFormat.SHORT).format(cal.time)
                text.textSize = sp(32f)
                text.color = Color.argb(aInt, 255, 255, 255)
                c.drawText(hm, cx - text.measureText(hm) / 2, cy - islandR * 0.22f, text)

                val sub = if (info.battery >= 0) "🔋 ${info.battery}% ${if (info.charging) "⚡ Charging" else ""} • Tap action below" else "Core Watch Hub"
                text.textSize = sp(10.5f)
                text.color = Color.argb((aInt * 0.8f).toInt(), 190, 195, 205)
                c.drawText(sub, cx - text.measureText(sub) / 2, cy - islandR * 0.04f, text)

                val bw = islandR * 0.78f
                val bh = dp(34f)
                drawPodBtn(cx - bw * 0.52f, cy + islandR * 0.20f, bw, bh, "⌚ Watch Face", "CLOCK_FACE", true)
                drawPodBtn(cx + bw * 0.52f, cy + islandR * 0.20f, bw, bh, "📊 Smart Stack", "CLOCK_STACK")
                drawPodBtn(cx - bw * 0.52f, cy + islandR * 0.48f, bw, bh, "🎛️ Controls", "CLOCK_CONTROLS")
                drawPodBtn(cx + bw * 0.52f, cy + islandR * 0.48f, bw, bh, "🧩 App Widgets", "SYS_WIDGETS")
            }

            WidgetKind.NOW_PLAYING -> {
                val tr = nowPlayingTrack
                if (tr?.art != null) {
                    bitmapPaint.alpha = (aInt * 0.30f).toInt()
                    rect.set(cx - islandR, cy - islandR, cx + islandR, cy + islandR)
                    c.drawBitmap(tr.art, null, rect, bitmapPaint)
                }
                val status = if (tr?.playing == true) "🎵 NOW PLAYING" else "🎵 MEDIA POD"
                text.textSize = sp(10.5f)
                text.color = withAlpha(tint, aInt)
                c.drawText(status, cx - text.measureText(status) / 2, cy - islandR * 0.56f, text)

                val titleStr = tr?.title?.takeIf { it.isNotBlank() } ?: "Nothing Playing"
                text.textSize = sp(16f)
                text.color = Color.argb(aInt, 255, 255, 255)
                val ellTitle = TextUtils.ellipsize(titleStr, text, islandR * 1.55f, TextUtils.TruncateAt.END).toString()
                c.drawText(ellTitle, cx - text.measureText(ellTitle) / 2, cy - islandR * 0.30f, text)

                val artistStr = tr?.artist?.takeIf { it.isNotBlank() } ?: "Tap Play or Open Media App"
                text.textSize = sp(11.5f)
                text.color = Color.argb((aInt * 0.8f).toInt(), 210, 215, 225)
                val ellArtist = TextUtils.ellipsize(artistStr, text, islandR * 1.45f, TextUtils.TruncateAt.END).toString()
                c.drawText(ellArtist, cx - text.measureText(ellArtist) / 2, cy - islandR * 0.12f, text)

                val bh = dp(38f)
                drawPodBtn(cx - islandR * 0.52f, cy + islandR * 0.16f, dp(54f), bh, "⏮", "MEDIA_PREV")
                drawPodBtn(cx, cy + islandR * 0.16f, dp(88f), bh, if (tr?.playing == true) "⏸ Pause" else "▶ Play", "MEDIA_PLAY", true)
                drawPodBtn(cx + islandR * 0.52f, cy + islandR * 0.16f, dp(54f), bh, "⏭", "MEDIA_NEXT")
                drawPodBtn(cx, cy + islandR * 0.48f, islandR * 1.18f, dp(34f), "🎵 Open Media Player", "MEDIA_OPEN")
            }

            WidgetKind.CONTROLS -> {
                val st = controlCenter.state
                text.textSize = sp(12f)
                text.color = withAlpha(tint, aInt)
                val hdr = "🎛️ QUICK CONTROL POD"
                c.drawText(hdr, cx - text.measureText(hdr) / 2, cy - islandR * 0.58f, text)

                val bw = islandR * 0.76f
                val bh = dp(34f)
                drawPodBtn(cx - bw * 0.52f, cy - islandR * 0.28f, bw, bh, "📶 Wi-Fi", "CTRL_WIFI", st.wifiOn, Color.rgb(10, 132, 255))
                drawPodBtn(cx + bw * 0.52f, cy - islandR * 0.28f, bw, bh, "🔹 Bluetooth", "CTRL_BT", st.bluetoothOn, Color.rgb(94, 92, 230))
                drawPodBtn(cx - bw * 0.52f, cy + islandR * 0.04f, bw, bh, "🔦 Flashlight", "CTRL_TORCH", st.torchOn || st.screenFlashlight, Color.rgb(255, 214, 10))
                val ringerLbl = if (st.ringerMode == AudioManager.RINGER_MODE_NORMAL) "🔔 Sound" else "📳 Vibrate"
                drawPodBtn(cx + bw * 0.52f, cy + islandR * 0.04f, bw, bh, ringerLbl, "CTRL_RINGER", st.ringerMode == AudioManager.RINGER_MODE_NORMAL, Color.rgb(48, 209, 88))
                drawPodBtn(cx - bw * 0.52f, cy + islandR * 0.36f, bw, bh, "🧩 App Widgets", "SYS_WIDGETS")
                drawPodBtn(cx + bw * 0.52f, cy + islandR * 0.36f, bw, bh, "🎛️ Full Panel", "CLOCK_CONTROLS")
            }

            WidgetKind.VITALS -> {
                val batPct = if (info.battery >= 0) "${info.battery}%" else "--"
                val ramFreePct = ((1f - ramUsedRatio) * 100).toInt().coerceIn(0, 100)
                val storFreePct = ((1f - storageUsedRatio) * 100).toInt().coerceIn(0, 100)

                // Draw 3 telemetry arcs in top half of pod
                val ratios = floatArrayOf(
                    (info.battery.coerceAtLeast(10)) / 100f,
                    (1f - ramUsedRatio).coerceIn(0.12f, 1f),
                    (1f - storageUsedRatio).coerceIn(0.12f, 1f),
                )
                val ringColors = intArrayOf(Color.rgb(48, 209, 88), Color.rgb(100, 210, 255), Color.rgb(255, 55, 95))
                val ringCy = cy - islandR * 0.22f
                stroke.strokeWidth = dp(7f)
                for (i in 0..2) {
                    val rr = islandR * (0.42f - i * 0.095f)
                    arcRect.set(cx - rr, ringCy - rr, cx + rr, ringCy + rr)
                    stroke.color = withAlpha(ringColors[i], (aInt * 0.25f).toInt())
                    c.drawOval(arcRect, stroke)
                    stroke.color = withAlpha(ringColors[i], aInt)
                    c.drawArc(arcRect, -90f, 360f * ratios[i], false, stroke)
                }
                text.textSize = sp(15f)
                text.color = Color.argb(aInt, 255, 255, 255)
                c.drawText(batPct, cx - text.measureText(batPct) / 2, ringCy + text.textSize * 0.35f, text)

                val stats = "🔋 Bat $batPct • 🧠 RAM $ramFreePct% free • 💾 $storFreePct% free"
                text.textSize = sp(9.5f)
                text.color = Color.argb((aInt * 0.85f).toInt(), 210, 220, 215)
                c.drawText(stats, cx - text.measureText(stats) / 2, cy + islandR * 0.28f, text)

                val bw = islandR * 0.74f
                val bh = dp(32f)
                drawPodBtn(cx - bw * 0.52f, cy + islandR * 0.50f, bw, bh, "🔋 Battery Info", "VITALS_BATTERY", true)
                drawPodBtn(cx + bw * 0.52f, cy + islandR * 0.50f, bw, bh, "📊 Smart Stack", "CLOCK_STACK")
            }

            WidgetKind.TIMER -> {
                val remMs = currentTimerRemainingMs()
                val totalMs = (timerTotalSec * 1000L).coerceAtLeast(1000L)
                val progress = (remMs.toFloat() / totalMs).coerceIn(0f, 1f)
                val remSec = ((remMs + 999L) / 1000L).toInt()
                val mm = remSec / 60
                val ss = remSec % 60
                val timeStr = "%02d:%02d".format(mm, ss)

                // Outer countdown progress ring inside the pod
                val arcR = islandR * 0.86f
                arcRect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
                stroke.strokeWidth = dp(5f)
                stroke.color = withAlpha(tint, (aInt * 0.22f).toInt())
                c.drawOval(arcRect, stroke)
                stroke.color = withAlpha(tint, aInt)
                c.drawArc(arcRect, -90f, 360f * progress, false, stroke)

                text.textSize = sp(11f)
                text.color = withAlpha(tint, aInt)
                val status = if (timerRunning) "⏱ COUNTING DOWN" else if (remMs == 0L) "🔔 TIME'S UP!" else "⏱ QUICK TIMER"
                c.drawText(status, cx - text.measureText(status) / 2, cy - islandR * 0.52f, text)

                text.textSize = sp(34f)
                text.color = Color.argb(aInt, 255, 255, 255)
                c.drawText(timeStr, cx - text.measureText(timeStr) / 2, cy - islandR * 0.16f, text)

                // 4 preset chips: 1m, 3m, 5m, 10m
                val presets = listOf(60 to "1m", 180 to "3m", 300 to "5m", 600 to "10m")
                val chipW = dp(44f)
                val chipH = dp(28f)
                val gap = dp(6f)
                val startX = cx - (presets.size * chipW + (presets.size - 1) * gap) / 2f + chipW / 2f
                for ((idx, pair) in presets.withIndex()) {
                    val (sec, lbl) = pair
                    drawPodBtn(startX + idx * (chipW + gap), cy + islandR * 0.12f, chipW, chipH, lbl, "TIMER_$sec", timerTotalSec == sec && !timerRunning)
                }

                // Start/Pause + Reset buttons
                val bw = islandR * 0.72f
                val bh = dp(34f)
                drawPodBtn(cx - bw * 0.52f, cy + islandR * 0.44f, bw, bh, if (timerRunning) "⏸ Pause" else "▶ Start", "TIMER_TOGGLE", true)
                drawPodBtn(cx + bw * 0.52f, cy + islandR * 0.44f, bw, bh, "↺ Reset", "TIMER_RESET")
            }

            WidgetKind.CALENDAR -> {
                val monthStr = SimpleDateFormat("EEEE, MMMM", Locale.getDefault()).format(cal.time).uppercase()
                text.textSize = sp(11f)
                text.color = withAlpha(tint, aInt)
                c.drawText(monthStr, cx - text.measureText(monthStr) / 2, cy - islandR * 0.52f, text)

                val dayNum = cal.get(Calendar.DAY_OF_MONTH).toString()
                text.textSize = sp(40f)
                text.color = Color.argb(aInt, 255, 255, 255)
                c.drawText(dayNum, cx - text.measureText(dayNum) / 2, cy - islandR * 0.14f, text)

                val nextStr = info.nextAlarm?.let { "⏰ Next Alarm: ${timeText(it)}" } ?: "📅 No upcoming alarms set"
                text.textSize = sp(11f)
                text.color = Color.argb((aInt * 0.85f).toInt(), 215, 220, 230)
                c.drawText(nextStr, cx - text.measureText(nextStr) / 2, cy + islandR * 0.08f, text)

                val bw = islandR * 0.76f
                val bh = dp(33f)
                drawPodBtn(cx - bw * 0.52f, cy + islandR * 0.34f, bw, bh, "📅 Calendar", "CAL_OPEN", true)
                drawPodBtn(cx + bw * 0.52f, cy + islandR * 0.34f, bw, bh, "⏰ Alarms", "CAL_ALARMS")
                drawPodBtn(cx, cy + islandR * 0.62f, islandR * 1.15f, dp(31f), "🧩 Android System Widgets", "SYS_WIDGETS")
            }
        }

        c.restore()

        val ph = dp(38f)
        val label = "⚡ ${widget.label}   ✕"
        text.textSize = sp(14.5f)
        val tw = text.measureText(label)
        val pw = tw + dp(32f)
        folderHeaderRect.set(w / 2 - pw / 2, top + dp(8f), w / 2 + pw / 2, top + dp(8f) + ph)
        fill.color = Color.argb((aInt * 0.92f).toInt(), 38, 40, 44)
        c.drawRoundRect(folderHeaderRect, ph / 2, ph / 2, fill)
        stroke.strokeWidth = dp(1.5f)
        stroke.color = withAlpha(tint, aInt)
        c.drawRoundRect(folderHeaderRect, ph / 2, ph / 2, stroke)
        text.color = Color.argb(aInt, 255, 255, 255)
        c.drawText(label, w / 2 - tw / 2, folderHeaderRect.centerY() + text.textSize * 0.35f, text)
    }

    private fun notificationSnippetFor(item: GridItem): String? {
        val snippets = NowPlayingListener.activeSnippets
        if (snippets.isEmpty()) return null
        return when (item) {
            is App -> snippets[item.component.packageName]
            is Folder -> item.apps.firstNotNullOfOrNull { snippets[it.component.packageName] }
            is WidgetBubble -> null
        }
    }

    private fun drawGridHeader(c: Canvas, w: Float, top: Float, centred: GridItem?, ov: Float, alpha: Float) {
        val pillH = dp(34f)
        val y = top + dp(8f)
        val sideW = dp(86f)

        // Top-left is intentionally empty: Overview is reached by pinching out (and a
        // double-tap on empty space resets zoom), so no permanent Overview pill competes
        // with the bubbles. Overview itself is now signalled by the crown-track thumb.

        // Top-right: Cluster Mode selector button
        clusterPillRect.set(w - dp(14f) - sideW, y, w - dp(14f), y + pillH)
        fill.color = Color.argb((alpha * 210).toInt(), 44, 44, 48)
        c.drawRoundRect(clusterPillRect, pillH / 2, pillH / 2, fill)
        text.textSize = sp(12f)
        text.color = withAlpha(accent, (alpha * 255).toInt())
        val clLabel = "⚙ Cluster"
        c.drawText(clLabel, clusterPillRect.centerX() - text.measureText(clLabel) / 2, clusterPillRect.centerY() + text.textSize * 0.35f, text)

        // Center: Active centered app/folder/widget pill + tap target for #4 Constellation Search
        if (centred != null && ov < 0.5f) {
            val pinPrefix = if (centred.key in pinnedItemKeys) "📌 " else "🔍 "
            val label = if (centred is Folder) "$pinPrefix${centred.label} (${centred.apps.size})" else "$pinPrefix${centred.label}"
            text.textSize = sp(13f)
            val tw = text.measureText(label)
            val maxPw = (w - sideW * 2 - dp(44f)).coerceAtLeast(dp(80f))
            val pw = (tw + dp(24f)).coerceAtMost(maxPw)
            centerSearchPillRect.set(w / 2 - pw / 2, y, w / 2 + pw / 2, y + pillH)
            fill.color = Color.argb((alpha * 215).toInt(), 32, 32, 36)
            c.drawRoundRect(centerSearchPillRect, pillH / 2, pillH / 2, fill)
            text.color = when (centred) {
                is Folder -> withAlpha(centred.group.tint, (alpha * 255).toInt())
                is WidgetBubble -> withAlpha(centred.kind.tint, (alpha * 255).toInt())
                else -> Color.argb((alpha * 255).toInt(), 255, 255, 255)
            }
            val ellipsized = TextUtils.ellipsize(label, text, pw - dp(14f), TextUtils.TruncateAt.END).toString()
            c.drawText(ellipsized, w / 2 - text.measureText(ellipsized) / 2, centerSearchPillRect.centerY() + text.textSize * 0.35f, text)

            // Live Notification Snippet Peek when centered bubble has an active notification!
            notificationSnippetFor(centred)?.let { snippet ->
                val peekY = centerSearchPillRect.bottom + dp(5f)
                val peekH = dp(24f)
                text.textSize = sp(10.5f)
                val rawPeek = "💬 $snippet"
                val maxPeekW = w - dp(48f)
                val ellPeek = TextUtils.ellipsize(rawPeek, text, maxPeekW - dp(20f), TextUtils.TruncateAt.END).toString()
                val ptw = text.measureText(ellPeek)
                val peekRect = RectF(w / 2 - ptw / 2 - dp(10f), peekY, w / 2 + ptw / 2 + dp(10f), peekY + peekH)
                fill.color = Color.argb((alpha * 225).toInt(), 24, 28, 34)
                c.drawRoundRect(peekRect, peekH / 2, peekH / 2, fill)
                stroke.strokeWidth = dp(1f)
                stroke.color = withAlpha(accent, (alpha * 180).toInt())
                c.drawRoundRect(peekRect, peekH / 2, peekH / 2, stroke)
                text.color = Color.argb((alpha * 245).toInt(), 230, 235, 242)
                c.drawText(ellPeek, w / 2 - ptw / 2, peekRect.centerY() + text.textSize * 0.35f, text)
            }
        } else {
            centerSearchPillRect.setEmpty()
        }
    }

    /** Interactive watchOS platter sheet for toggling #1, #2, #3, #4, #5, and #6. */
    private fun drawClusterSheet(c: Canvas, w: Float, h: Float, top: Float, bottom: Float, t: Float) {
        val a = (t * 255).toInt().coerceIn(0, 255)
        sheetRowRects.clear()

        fill.color = Color.argb((t * 195).toInt(), 0, 0, 0)
        c.drawRect(0f, 0f, w, h, fill)

        if (topologyPickerOpen) {
            drawTopologyPicker(c, w, h, t)
            return
        }

        val padX = dp(18f)
        val cardW = w - 2 * padX
        var rowH = dp(38f)
        val gap = dp(4f)
        // Sectioned rows: a null option marks a section header (not tappable).
        // Toggles live under LAYOUT / BEHAVIOR, one-shot actions under ACTIONS.
        val rows = buildList<Triple<ClusterOption?, String, String>> {
            add(Triple(null, "LAYOUT", ""))
            add(Triple(ClusterOption.CLUSTER_TOPOLOGY, "Cluster Topology", "${clusterTopology.fullTitle} • Tap to browse all ${ClusterTopology.entries.size} designs"))
            add(Triple(ClusterOption.FIXED_NEIGHBORHOODS, "Grid Layout", if (fixedNeighborhoods) "🧭 Fixed Compass Neighborhoods" else "🔥 Dynamic Usage Spiral"))
            add(Triple(ClusterOption.HONEYCOMB_HOME, "Home Screen", if (honeycombHome) "🏠 Honeycomb Core Hub (0,0 Clock)" else "⌚ Watch Face First"))
            add(Triple(null, "BEHAVIOR", ""))
            add(Triple(ClusterOption.BUBBLE_PHYSICS, "Bubble Physics", if (bubblePhysics) "🫧 Jello, Wake Wave, Spin & Billiards On" else "⚪ Off (Bubbles Sit Still)"))
            add(Triple(ClusterOption.BLOOM_MODE, "Folder Open Style", if (bloomFolders) "🌊 Bloom In-Place (Shockwave)" else "📂 Full-Screen Folder"))
            add(Triple(ClusterOption.QUICK_SNIPE, "Mini-Icon Tap", if (quickSnipe) "🎯 Quick-Snipe (Outer 6 Launch)" else "👆 Tap Anywhere Opens Folder"))
            add(Triple(ClusterOption.LIVE_WIDGETS, "Live Grid Widgets", if (liveWidgets) "⚡ Media, Timer, Calendar & Vitals On" else "⚪ Off (Apps & Folders Only)"))
            add(Triple(ClusterOption.AUTO_FOLDERS, "Category Clustering", if (autoFolders) "🟢 Auto Folders On" else "⚪ Loose Apps Only"))
            add(Triple(null, "ACTIONS", ""))
            add(Triple(ClusterOption.CONSTELLATION_SEARCH, "App Search", "🔍 Search all apps in the constellation"))
            add(Triple(ClusterOption.SYSTEM_WIDGETS, "Android System Widgets", "🧩 Tap to View / Add App Widgets"))
            add(Triple(ClusterOption.SET_DEFAULT_HOME, "Default Phone Launcher", "📲 Tap to Set Watch Reborn as Home App"))
            if (pinnedItemKeys.isNotEmpty()) {
                add(Triple(ClusterOption.CLEAR_PINS, "Pinned Hex Slots (${pinnedItemKeys.size})", "📌 Tap to unpin all bubbles"))
            }
        }

        val headerH = dp(19f)
        val optionCount = rows.count { it.first != null }
        val headerCount = rows.size - optionCount
        var sheetH = dp(68f) + optionCount * (rowH + gap) + headerCount * headerH + dp(52f)
        if (sheetH > h - dp(36f) && optionCount > 0) {
            // Short screen (or the extra Clear Pins row): compress rows so the
            // sheet — including its Done button — always fits.
            rowH = ((h - dp(36f) - dp(68f) - headerCount * headerH - dp(52f)) / optionCount - gap).coerceAtLeast(dp(24f))
            sheetH = dp(68f) + optionCount * (rowH + gap) + headerCount * headerH + dp(52f)
        }

        val sheetTop = (h - sheetH) / 2f + (1f - t) * dp(36f)
        sheetBoundsRect.set(padX, sheetTop, padX + cardW, sheetTop + sheetH)

        fill.color = Color.argb(a, 24, 26, 30)
        c.drawRoundRect(sheetBoundsRect, dp(24f), dp(24f), fill)
        stroke.strokeWidth = dp(1.5f)
        stroke.color = withAlpha(accent, (a * 0.6f).toInt())
        c.drawRoundRect(sheetBoundsRect, dp(24f), dp(24f), stroke)

        text.textSize = sp(16.5f)
        text.color = Color.argb(a, 255, 255, 255)
        val title = "Honeycomb Cluster Options"
        c.drawText(title, w / 2 - text.measureText(title) / 2, sheetTop + dp(30f), text)

        text.textSize = sp(11f)
        text.color = Color.argb((a * 0.7f).toInt(), 188, 190, 198)
        val hint = "Layout • Behavior • Actions — long-press a bubble to pin it"
        c.drawText(hint, w / 2 - text.measureText(hint) / 2, sheetTop + dp(49f), text)

        var ry = sheetTop + dp(62f)
        for ((opt, label, valueText) in rows) {
            if (opt == null) {
                text.textSize = sp(10.5f)
                text.color = Color.argb((a * 0.65f).toInt(), 168, 170, 178)
                c.drawText(label, padX + dp(16f), ry + dp(13f), text)
                ry += headerH
                continue
            }
            val r = RectF(padX + dp(12f), ry, padX + cardW - dp(12f), ry + rowH)
            fill.color = Color.argb(a, 40, 42, 48)
            c.drawRoundRect(r, dp(14f), dp(14f), fill)

            text.textSize = sp(10.5f)
            text.color = Color.argb((a * 0.75f).toInt(), 178, 180, 188)
            c.drawText(label, r.left + dp(14f), r.top + dp(16f), text)

            text.textSize = sp(13f)
            text.color = withAlpha(accent, a)
            c.drawText(valueText, r.left + dp(14f), r.top + dp(34f), text)

            // Right-side affordance: an ON/OFF chip for toggles, a chevron for actions,
            // so the two kinds of row no longer look identical.
            val toggleOn: Boolean? = when (opt) {
                ClusterOption.FIXED_NEIGHBORHOODS -> fixedNeighborhoods
                ClusterOption.HONEYCOMB_HOME -> honeycombHome
                ClusterOption.BUBBLE_PHYSICS -> bubblePhysics
                ClusterOption.BLOOM_MODE -> bloomFolders
                ClusterOption.QUICK_SNIPE -> quickSnipe
                ClusterOption.LIVE_WIDGETS -> liveWidgets
                ClusterOption.AUTO_FOLDERS -> autoFolders
                else -> null
            }
            if (toggleOn != null) {
                val chipW = dp(38f)
                val chipH = dp(17f)
                val chip = RectF(r.right - dp(12f) - chipW, r.centerY() - chipH / 2, r.right - dp(12f), r.centerY() + chipH / 2)
                fill.color = if (toggleOn) withAlpha(accent, a) else Color.argb(a, 84, 84, 90)
                c.drawRoundRect(chip, chipH / 2, chipH / 2, fill)
                text.textSize = sp(9f)
                text.color = if (toggleOn) Color.argb(a, 0, 0, 0) else Color.argb(a, 235, 235, 240)
                val s = if (toggleOn) "ON" else "OFF"
                c.drawText(s, chip.centerX() - text.measureText(s) / 2, chip.centerY() + text.textSize * 0.35f, text)
            } else {
                text.textSize = sp(17f)
                text.color = Color.argb((a * 0.6f).toInt(), 200, 202, 208)
                c.drawText("›", r.right - dp(22f), r.centerY() + text.textSize * 0.35f, text)
            }

            sheetRowRects += r to opt
            ry += rowH + gap
        }

        val doneW = dp(130f)
        val doneH = dp(38f)
        val doneRect = RectF(w / 2 - doneW / 2, ry + dp(4f), w / 2 + doneW / 2, ry + dp(4f) + doneH)
        fill.color = withAlpha(accent, a)
        c.drawRoundRect(doneRect, doneH / 2, doneH / 2, fill)
        text.textSize = sp(14f)
        text.color = Color.argb(a, 0, 0, 0)
        val doneStr = "Done"
        c.drawText(doneStr, w / 2 - text.measureText(doneStr) / 2, doneRect.centerY() + text.textSize * 0.35f, text)
        sheetRowRects += doneRect to ClusterOption.CLOSE_SHEET
    }

    /** Full list of cluster layouts: tap one to apply it directly (replaces tap-to-cycle). */
    private fun drawTopologyPicker(c: Canvas, w: Float, h: Float, t: Float) {
        val a = (t * 255).toInt().coerceIn(0, 255)
        topologyRowRects.clear()

        val padX = dp(18f)
        val cardW = w - 2 * padX
        var rowH = dp(38f)
        val gap = dp(4f)
        val all = ClusterTopology.entries
        var sheetH = dp(68f) + all.size * (rowH + gap) + dp(52f)
        if (sheetH > h - dp(36f)) {
            // Short screen: compress rows so the picker always fits.
            rowH = ((h - dp(36f) - dp(68f) - dp(52f)) / all.size - gap).coerceAtLeast(dp(24f))
            sheetH = dp(68f) + all.size * (rowH + gap) + dp(52f)
        }
        val sheetTop = (h - sheetH) / 2f + (1f - t) * dp(36f)
        sheetBoundsRect.set(padX, sheetTop, padX + cardW, sheetTop + sheetH)

        fill.color = Color.argb(a, 24, 26, 30)
        c.drawRoundRect(sheetBoundsRect, dp(24f), dp(24f), fill)
        stroke.strokeWidth = dp(1.5f)
        stroke.color = withAlpha(accent, (a * 0.6f).toInt())
        c.drawRoundRect(sheetBoundsRect, dp(24f), dp(24f), stroke)

        text.textSize = sp(16.5f)
        text.color = Color.argb(a, 255, 255, 255)
        val title = "Cluster Layout"
        c.drawText(title, w / 2 - text.measureText(title) / 2, sheetTop + dp(30f), text)

        text.textSize = sp(11f)
        text.color = Color.argb((a * 0.7f).toInt(), 188, 190, 198)
        val hint = "Tap a design to apply it instantly"
        c.drawText(hint, w / 2 - text.measureText(hint) / 2, sheetTop + dp(49f), text)

        var ry = sheetTop + dp(62f)
        for (topo in all) {
            val r = RectF(padX + dp(12f), ry, padX + cardW - dp(12f), ry + rowH)
            fill.color = Color.argb(a, 40, 42, 48)
            c.drawRoundRect(r, dp(14f), dp(14f), fill)
            if (topo == clusterTopology) {
                stroke.strokeWidth = dp(1.5f)
                stroke.color = withAlpha(accent, (a * 0.8f).toInt())
                c.drawRoundRect(r, dp(14f), dp(14f), stroke)
            }

            text.textSize = sp(13f)
            text.color = Color.argb(a, 255, 255, 255)
            c.drawText(topo.fullTitle, r.left + dp(14f), r.top + dp(16f), text)

            text.textSize = sp(10.5f)
            text.color = Color.argb((a * 0.75f).toInt(), 178, 180, 188)
            c.drawText(topo.subtitle, r.left + dp(14f), r.top + dp(34f), text)

            // Miniature preview of the layout's geometry, so designs are recognisable
            // before applying. (Liquid Pool previews as its hex seed; the live pool
            // positions are stateful simulation data.)
            run {
                val n = 19
                val pts = Array(n) { FloatArray(2) }
                var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
                var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                for (i in 0 until n) {
                    if (topo == ClusterTopology.LIQUID_POOL) {
                        if (i in gridHexes.indices) pointyCenter(gridHexes[i], dp(6f), pts[i])
                        else { pts[i][0] = 0f; pts[i][1] = 0f }
                    } else {
                        computeTopologyWorldPos(topo, i, n, dp(6f), pts[i])
                    }
                    minX = min(minX, pts[i][0]); maxX = max(maxX, pts[i][0])
                    minY = min(minY, pts[i][1]); maxY = max(maxY, pts[i][1])
                }
                val spanX = (maxX - minX).coerceAtLeast(1f)
                val spanY = (maxY - minY).coerceAtLeast(1f)
                val scale = min(dp(26f) / spanX, dp(26f) / spanY)
                val pcx = r.right - dp(58f)
                val pcy = r.centerY()
                val ccx = (minX + maxX) / 2f
                val ccy = (minY + maxY) / 2f
                for (i in 0 until n) {
                    fill.color = withAlpha(accent, (a * (if (i == 0) 255 else 165)).toInt())
                    c.drawCircle(pcx + (pts[i][0] - ccx) * scale, pcy + (pts[i][1] - ccy) * scale, if (i == 0) dp(2.4f) else dp(1.6f), fill)
                }
            }

            if (topo == clusterTopology) {
                text.textSize = sp(15f)
                text.color = withAlpha(accent, a)
                c.drawText("✓", r.right - dp(26f), r.centerY() + text.textSize * 0.35f, text)
            }

            topologyRowRects += r to topo
            ry += rowH + gap
        }

        val backW = dp(130f)
        val backH = dp(38f)
        pickerBackRect.set(w / 2 - backW / 2, ry + dp(4f), w / 2 + backW / 2, ry + dp(4f) + backH)
        fill.color = Color.argb(a, 52, 54, 60)
        c.drawRoundRect(pickerBackRect, backH / 2, backH / 2, fill)
        text.textSize = sp(14f)
        text.color = Color.argb(a, 255, 255, 255)
        val backStr = "‹ Back"
        c.drawText(backStr, w / 2 - text.measureText(backStr) / 2, pickerBackRect.centerY() + text.textSize * 0.35f, text)
    }

    /** Classic full-screen Folder View (used when [bloomFolders] is false). */
    private fun drawFolderView(c: Canvas, w: Float, h: Float, top: Float, alpha: Float) {
        val folder = activeFolder ?: return
        val u = unit
        val cx = w / 2
        val cy = h / 2
        val sx = max(1f, w / u)
        val sy = max(1f, (h / u) / Metrics.WATCH_ASPECT)

        for (i in folder.apps.indices) {
            if (i >= folderHexes.size) break
            pointyCenter(folderHexes[i], Metrics.GRID_PITCH * u * 1.08f, tmp)
            val dx = (tmp[0] + folderPanX.value) / u
            val dy = (tmp[1] + folderPanY.value) / u
            val d = hypot(dx / sx, dy / sy)
            val size = Fisheye.size(d)
            if (size <= 0.02f) continue
            val k = if (d > 1e-4f) Fisheye.pulled(d) / d else 1f
            val x = cx + dx * k * u
            val y = cy + dy * k * u
            val dia = Metrics.GRID_CENTER_D * u * size * 1.08f
            val app = folder.apps[i]
            drawIcon(c, app.icon, x, y, dia, alpha, hasNotification(app), app.key in pinnedItemKeys)
            hits += Hit(x, y, dia / 2, app)
        }

        val ph = dp(40f)
        val label = "←  ${folder.group.title} (${folder.apps.size})"
        text.textSize = sp(15f)
        val tw = text.measureText(label)
        val pw = tw + dp(36f)
        folderHeaderRect.set(w / 2 - pw / 2, top + dp(8f), w / 2 + pw / 2, top + dp(8f) + ph)
        fill.color = Color.argb((alpha * 235).toInt(), 44, 44, 46)
        c.drawRoundRect(folderHeaderRect, ph / 2, ph / 2, fill)
        stroke.strokeWidth = dp(1.5f)
        stroke.color = withAlpha(folder.group.tint, (alpha * 220).toInt())
        c.drawRoundRect(folderHeaderRect, ph / 2, ph / 2, stroke)
        text.color = Color.argb((alpha * 255).toInt(), 255, 255, 255)
        c.drawText(label, w / 2 - tw / 2, folderHeaderRect.centerY() + text.textSize * 0.35f, text)
    }

    private fun drawCrownTrack(c: Canvas, w: Float, h: Float, top: Float, bottom: Float, alpha: Float) {
        if (list.value > 0.5f || folderIn.value > 0.5f || clusterSheet.value > 0.2f || gridSearching) return
        val trackH = dp(78f)
        val trackW = dp(4.5f)
        val right = w - dp(7f)
        val cy = top + dp(105f)
        val a = (alpha * 170).toInt()
        fill.color = Color.argb(a, 65, 65, 70)
        rect.set(right - trackW, cy - trackH / 2, right, cy + trackH / 2)
        c.drawRoundRect(rect, trackW / 2, trackW / 2, fill)

        val thumbH = dp(24f)
        val effZ = userZoom.value * (1f - overview.value) + 0.48f * overview.value
        val progress = ((1.30f - effZ) / (1.30f - 0.48f)).coerceIn(0f, 1f)
        val thumbTop = rect.top + progress * (trackH - thumbH)
        fill.color = withAlpha(accent, (alpha * 245).toInt())
        rect.set(right - trackW, thumbTop, right, thumbTop + thumbH)
        c.drawRoundRect(rect, trackW / 2, trackW / 2, fill)

        // Show live zoom badge when pinching or scrubbing the edge Digital Crown
        if (pinching || edgeCrownDragging) {
            val zStr = if (effZ <= 0.54f) "Overview" else "${"%.1f".format(effZ)}x"
            text.textSize = sp(11.5f)
            val zw = text.measureText(zStr)
            val bRect = RectF(right - trackW - zw - dp(24f), thumbTop - dp(2f), right - trackW - dp(8f), thumbTop + dp(24f))
            fill.color = Color.argb(235, 28, 30, 36)
            c.drawRoundRect(bRect, dp(12f), dp(12f), fill)
            stroke.strokeWidth = dp(1.2f)
            stroke.color = withAlpha(accent, 220)
            c.drawRoundRect(bRect, dp(12f), dp(12f), stroke)
            text.color = withAlpha(accent, 255)
            c.drawText(zStr, bRect.centerX() - zw / 2, bRect.centerY() + text.textSize * 0.35f, text)
        }
    }

    private val filteredAlphabetical: List<App>
        get() {
            val q = searchQuery.trim()
            if (q.isEmpty()) return alphabetical
            return alphabetical.filter { it.label.contains(q, ignoreCase = true) }
        }

    private fun drawList(c: Canvas, w: Float, h: Float, top: Float, bottom: Float, alpha: Float) {
        val pad = max(0.045f * w, (w - dp(440f)) / 2f)
        val rightEdge = w - pad - dp(24f)
        val rowH = dp(62f)
        val pitch = dp(Metrics.PHONE_LIST_PITCH_DP)
        val iconD = dp(Metrics.PHONE_LIST_ICON_DP)
        val r = rowH / 2

        val sTop = top + dp(6f)
        val sH = dp(42f)
        searchBarRect.set(pad, sTop, rightEdge, sTop + sH)
        fill.color = Color.argb((alpha * 225).toInt(), 38, 38, 42)
        c.drawRoundRect(searchBarRect, sH / 2, sH / 2, fill)
        if (searching) {
            stroke.strokeWidth = dp(1.5f)
            stroke.color = withAlpha(accent, (alpha * 220).toInt())
            c.drawRoundRect(searchBarRect, sH / 2, sH / 2, stroke)
        }
        text.textSize = sp(14.5f)
        val prompt = if (searchQuery.isEmpty()) {
            if (searching) "🔍 Type app name..." else "🔍 Search ${alphabetical.size} apps..."
        } else {
            "🔍 $searchQuery"
        }
        text.color = if (searchQuery.isEmpty() && !searching) {
            Color.argb((alpha * 150).toInt(), 180, 180, 185)
        } else {
            Color.argb((alpha * 255).toInt(), 255, 255, 255)
        }
        c.drawText(prompt, searchBarRect.left + dp(16f), searchBarRect.centerY() + text.textSize * 0.35f, text)

        if (searchQuery.isNotEmpty() || searching) {
            searchClearRect.set(searchBarRect.right - sH, searchBarRect.top, searchBarRect.right, searchBarRect.bottom)
            text.color = Color.argb((alpha * 200).toInt(), 190, 190, 195)
            val xStr = "✕"
            val xw = text.measureText(xStr)
            c.drawText(xStr, searchClearRect.centerX() - xw / 2, searchClearRect.centerY() + text.textSize * 0.35f, text)
        } else {
            searchClearRect.setEmpty()
        }

        val listTop = sTop + sH + dp(12f)
        val listApps = filteredAlphabetical
        text.textSize = sp(Metrics.PHONE_LIST_TEXT_SP)

        for (i in listApps.indices) {
            val cy = listTop + rowH / 2 + i * pitch - listScroll
            if (cy < listTop - rowH || cy > bottom - dp(50f)) continue
            val edge = min((cy - listTop) / rowH, (bottom - dp(55f) - cy) / rowH).coerceIn(0f, 1f)
            val s = 0.88f + 0.12f * edge
            val a = alpha * edge
            val rw = (rightEdge - pad) * s
            val rh = rowH * s
            val left = pad + (rightEdge - pad - rw) / 2
            rect.set(left, cy - rh / 2, left + rw, cy + rh / 2)
            fill.color = Color.argb((a * 255).toInt(), 36, 36, 38)
            c.drawRoundRect(rect, r * s, r * s, fill)

            val app = listApps[i]
            val id = iconD * s
            val ix = left + rh / 2
            drawIcon(c, app.icon, ix, cy, id, a, hasNotification(app), app.key in pinnedItemKeys)

            text.color = Color.argb((a * 255).toInt(), 255, 255, 255)
            val tx = ix + id / 2 + dp(12f) * s
            val avail = left + rw - tx - dp(12f)
            val label = TextUtils.ellipsize(app.label, text, avail, TextUtils.TruncateAt.END).toString()
            c.drawText(label, tx, cy + text.textSize * 0.35f, text)

            hits += Hit(w / 2, cy, rh / 2, app)
        }

        drawAlphabetScrubber(c, w, listTop, bottom - dp(76f), alpha)
    }

    private fun drawAlphabetScrubber(c: Canvas, w: Float, top: Float, bottom: Float, alpha: Float) {
        val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ#"
        val availH = bottom - top
        if (availH <= dp(120f)) return
        val step = availH / letters.length
        val x = w - dp(14f)
        text.textSize = sp(9.5f)
        for (i in letters.indices) {
            val ch = letters[i]
            val y = top + (i + 0.5f) * step
            val active = scrubbingLetter == ch
            text.color = if (active) withAlpha(accent, (alpha * 255).toInt()) else Color.argb((alpha * 155).toInt(), 180, 180, 185)
            val cw = text.measureText(ch.toString())
            c.drawText(ch.toString(), x - cw / 2, y + text.textSize * 0.35f, text)
        }

        scrubbingLetter?.let { ch ->
            val r = dp(28f)
            val bx = w / 2
            val by = (top + bottom) / 2
            fill.color = withAlpha(accent, 235)
            c.drawCircle(bx, by, r, fill)
            text.textSize = sp(22f)
            text.color = Color.BLACK
            val s = ch.toString()
            val sw = text.measureText(s)
            c.drawText(s, bx - sw / 2, by + text.textSize * 0.36f, text)
        }
    }

    private fun drawPill(c: Canvas, w: Float, bottom: Float, alpha: Float) {
        if (alpha <= 0.01f) {
            pill.setEmpty()
            return
        }
        val ph = dp(Metrics.PHONE_PILL_H_DP)
        val pw = dp(Metrics.PHONE_PILL_W_DP)
        val py = bottom - ph - dp(16f)

        pill.set(w / 2 - pw / 2, py, w / 2 + pw / 2, py + ph)
        fill.color = Color.argb((alpha * 230).toInt(), 44, 44, 46)
        c.drawRoundRect(pill, ph / 2, ph / 2, fill)
        text.textSize = sp(Metrics.PHONE_PILL_TEXT_SP)
        text.color = Color.argb((alpha * 255).toInt(), 255, 255, 255)
        val label = when {
            list.target >= 0.5f -> "Grid View"
            grid.target < 0.5f -> "All Apps"
            else -> "List View"
        }
        val tw = text.measureText(label)
        c.drawText(label, w / 2 - tw / 2, pill.centerY() + text.textSize * 0.35f, text)
    }

    private fun hasNotification(item: GridItem): Boolean {
        val pkgs = NowPlayingListener.activePackages
        if (pkgs.isEmpty()) return false
        return when (item) {
            is App -> item.component.packageName in pkgs
            is Folder -> item.apps.any { it.component.packageName in pkgs }
            is WidgetBubble -> item.kind == WidgetKind.NOW_PLAYING && nowPlayingTrack?.playing == true
        }
    }

    /** Draws either a standard App/Folder icon or a live vector [WidgetBubble] (#1, #5). */
    private fun drawGridItem(c: Canvas, item: GridItem, x: Float, y: Float, d: Float, alpha: Float, cal: Calendar) {
        if (item is WidgetBubble) {
            drawWidgetBubble(c, item, x, y, d, alpha, cal)
        } else {
            drawIcon(c, item.icon, x, y, d, alpha, hasNotification(item), item.key in pinnedItemKeys)
        }
    }

    /** #1 & #5: Renders live Clock/Status, Now Playing, Control Cluster, and Vitals Rings directly on a honeycomb bubble. */
    private fun drawWidgetBubble(c: Canvas, wItem: WidgetBubble, x: Float, y: Float, d: Float, alpha: Float, cal: Calendar) {
        if (alpha <= 0.004f || d < 4f) return
        val aInt = (alpha * 255).toInt().coerceIn(0, 255)
        val r = d * 0.48f

        when (wItem.kind) {
            WidgetKind.CORE_CLOCK -> {
                // Dark watch dial platter
                fill.color = Color.argb(aInt, 18, 22, 26)
                c.drawCircle(x, y, r, fill)

                // 24 perimeter tick marks lit up to the current second
                val sec = cal.get(Calendar.SECOND)
                val litTicks = (sec * 24) / 60
                stroke.strokeWidth = (d * 0.024f).coerceAtLeast(1.5f)
                val tickOuter = r * 0.92f
                val tickInner = r * 0.80f
                for (t in 0 until 24) {
                    val ang = Math.toRadians(t * 15.0 - 90.0)
                    val ca = cos(ang).toFloat()
                    val sa = sin(ang).toFloat()
                    stroke.color = if (t <= litTicks) withAlpha(accent, aInt) else Color.argb((aInt * 0.28f).toInt(), 140, 145, 155)
                    c.drawLine(x + ca * tickInner, y + sa * tickInner, x + ca * tickOuter, y + sa * tickOuter, stroke)
                }

                if (d > dp(28f)) {
                    // Date at top
                    val dayStr = SimpleDateFormat("EEE d", Locale.getDefault()).format(cal.time).uppercase()
                    text.textSize = d * 0.125f
                    text.color = withAlpha(accent, aInt)
                    c.drawText(dayStr, x - text.measureText(dayStr) / 2, y - r * 0.34f, text)

                    // Digital Time in center
                    val hm = DateFormat.getTimeInstance(DateFormat.SHORT).format(cal.time).substringBefore(' ')
                    text.textSize = d * 0.25f
                    text.color = Color.argb(aInt, 255, 255, 255)
                    c.drawText(hm, x - text.measureText(hm) / 2, y + text.textSize * 0.34f, text)

                    // Battery % at bottom
                    val batStr = if (info.battery >= 0) "${if (info.charging) "⚡" else ""}${info.battery}%" else "FACE"
                    text.textSize = d * 0.12f
                    text.color = Color.argb((aInt * 0.85f).toInt(), 48, 209, 88)
                    c.drawText(batStr, x - text.measureText(batStr) / 2, y + r * 0.58f, text)
                }
            }

            WidgetKind.NOW_PLAYING -> {
                fill.color = Color.argb(aInt, 36, 16, 24)
                c.drawCircle(x, y, r, fill)

                val tr = nowPlayingTrack
                if (tr?.art != null && d > dp(24f)) {
                    c.save()
                    clipPath.reset()
                    clipPath.addCircle(x, y, r * 0.92f, Path.Direction.CW)
                    c.clipPath(clipPath)
                    bitmapPaint.alpha = (aInt * 0.48f).toInt()
                    rect.set(x - r, y - r, x + r, y + r)
                    c.drawBitmap(tr.art, null, rect, bitmapPaint)
                    c.restore()
                }

                stroke.strokeWidth = (d * 0.036f).coerceAtLeast(1.5f)
                stroke.color = Color.argb(aInt, 255, 55, 95)
                c.drawCircle(x, y, r * 0.94f, stroke)

                if (d > dp(28f)) {
                    // Equalizer bars at top
                    val playing = tr?.playing == true
                    val sec = cal.get(Calendar.SECOND)
                    val barW = d * 0.045f
                    val gap = d * 0.03f
                    val startX = x - (4 * barW + 3 * gap) / 2f
                    fill.color = Color.argb(aInt, 255, 55, 95)
                    for (b in 0 until 4) {
                        val hFactor = if (playing) 0.08f + 0.08f * (((sec + b * 2) % 3) + 1) else 0.07f
                        val bh = d * hFactor
                        val bx = startX + b * (barW + gap)
                        rect.set(bx, y - r * 0.36f - bh, bx + barW, y - r * 0.36f)
                        c.drawRoundRect(rect, barW / 2, barW / 2, fill)
                    }

                    // Play / Pause glyph in center
                    text.textSize = d * 0.24f
                    text.color = Color.argb(aInt, 255, 255, 255)
                    val glyph = if (playing) "⏸" else "▶"
                    c.drawText(glyph, x - text.measureText(glyph) / 2, y + text.textSize * 0.35f, text)

                    // Track title at bottom
                    text.textSize = d * 0.115f
                    text.color = Color.argb((aInt * 0.9f).toInt(), 255, 210, 220)
                    val rawTitle = tr?.title?.takeIf { it.isNotBlank() } ?: "Music"
                    val shortTitle = TextUtils.ellipsize(rawTitle, text, d * 0.68f, TextUtils.TruncateAt.END).toString()
                    c.drawText(shortTitle, x - text.measureText(shortTitle) / 2, y + r * 0.56f, text)
                }
            }

            WidgetKind.CONTROLS -> {
                fill.color = Color.argb(aInt, 20, 26, 34)
                c.drawCircle(x, y, r, fill)
                stroke.strokeWidth = (d * 0.034f).coerceAtLeast(1.5f)
                stroke.color = Color.argb(aInt, 100, 210, 255)
                c.drawCircle(x, y, r * 0.94f, stroke)

                val st = controlCenter.state
                val orbit = d * 0.26f
                val miniR = d * 0.105f
                val colors = intArrayOf(
                    if (st.wifiOn) Color.rgb(10, 132, 255) else Color.rgb(65, 70, 78),
                    if (st.bluetoothOn) Color.rgb(94, 92, 230) else Color.rgb(65, 70, 78),
                    if (st.torchOn || st.screenFlashlight) Color.rgb(255, 214, 10) else Color.rgb(65, 70, 78),
                    if (st.ringerMode == AudioManager.RINGER_MODE_NORMAL) Color.rgb(48, 209, 88) else Color.rgb(255, 159, 10),
                    Color.rgb(166, 227, 110),
                    if (st.notifCount > 0) Color.rgb(255, 55, 95) else Color.rgb(65, 70, 78),
                )
                // Center hub
                fill.color = Color.argb(aInt, 100, 210, 255)
                c.drawCircle(x, y, d * 0.12f, fill)
                for (i in 0 until 6) {
                    val ang = Math.toRadians(i * 60.0 - 90.0)
                    val mx = x + (cos(ang) * orbit).toFloat()
                    val my = y + (sin(ang) * orbit).toFloat()
                    fill.color = withAlpha(colors[i], aInt)
                    c.drawCircle(mx, my, miniR, fill)
                }
            }

            WidgetKind.VITALS -> {
                fill.color = Color.argb(aInt, 18, 28, 22)
                c.drawCircle(x, y, r, fill)

                // 3 concentric Activity & System rings (Battery, RAM Free, Storage Free)
                val ratios = floatArrayOf(
                    (info.battery.coerceAtLeast(10)) / 100f,
                    (1f - ramUsedRatio).coerceIn(0.12f, 1f),
                    (1f - storageUsedRatio).coerceIn(0.12f, 1f),
                )
                val ringColors = intArrayOf(
                    Color.rgb(48, 209, 88),
                    Color.rgb(100, 210, 255),
                    Color.rgb(255, 55, 95),
                )
                val sw = (d * 0.068f).coerceAtLeast(2f)
                stroke.strokeWidth = sw
                for (i in 0..2) {
                    val rr = r * (0.86f - i * 0.18f)
                    arcRect.set(x - rr, y - rr, x + rr, y + rr)
                    stroke.color = withAlpha(ringColors[i], (aInt * 0.25f).toInt())
                    c.drawOval(arcRect, stroke)
                    stroke.color = withAlpha(ringColors[i], aInt)
                    c.drawArc(arcRect, -90f, 360f * ratios[i], false, stroke)
                }

                if (d > dp(28f)) {
                    text.textSize = d * 0.165f
                    text.color = Color.argb(aInt, 255, 255, 255)
                    val bat = if (info.battery >= 0) "${info.battery}%" else "Vitals"
                    c.drawText(bat, x - text.measureText(bat) / 2, y + text.textSize * 0.35f, text)
                }
            }

            WidgetKind.TIMER -> {
                fill.color = Color.argb(aInt, 32, 24, 14)
                c.drawCircle(x, y, r, fill)

                val remMs = currentTimerRemainingMs()
                val totalMs = (timerTotalSec * 1000L).coerceAtLeast(1000L)
                val progress = (remMs.toFloat() / totalMs).coerceIn(0f, 1f)
                val rr = r * 0.88f
                arcRect.set(x - rr, y - rr, x + rr, y + rr)
                stroke.strokeWidth = (d * 0.06f).coerceAtLeast(2f)
                stroke.color = Color.argb((aInt * 0.25f).toInt(), 255, 159, 10)
                c.drawOval(arcRect, stroke)
                stroke.color = Color.argb(aInt, 255, 159, 10)
                c.drawArc(arcRect, -90f, 360f * progress, false, stroke)

                if (d > dp(28f)) {
                    text.textSize = d * 0.135f
                    text.color = Color.argb(aInt, 255, 159, 10)
                    val topLbl = if (timerRunning) "⏱ LIVE" else "⏱ TIMER"
                    c.drawText(topLbl, x - text.measureText(topLbl) / 2, y - r * 0.24f, text)

                    val remSec = ((remMs + 999L) / 1000L).toInt()
                    val timeStr = "%d:%02d".format(remSec / 60, remSec % 60)
                    text.textSize = d * 0.23f
                    text.color = Color.argb(aInt, 255, 255, 255)
                    c.drawText(timeStr, x - text.measureText(timeStr) / 2, y + text.textSize * 0.55f, text)
                }
            }

            WidgetKind.CALENDAR -> {
                fill.color = Color.argb(aInt, 30, 18, 22)
                c.drawCircle(x, y, r, fill)
                stroke.strokeWidth = (d * 0.036f).coerceAtLeast(1.5f)
                stroke.color = Color.argb(aInt, 255, 69, 58)
                c.drawCircle(x, y, r * 0.94f, stroke)

                if (d > dp(28f)) {
                    val dow = SimpleDateFormat("EEE", Locale.getDefault()).format(cal.time).uppercase()
                    text.textSize = d * 0.145f
                    text.color = Color.argb(aInt, 255, 69, 58)
                    c.drawText(dow, x - text.measureText(dow) / 2, y - r * 0.30f, text)

                    val dayNum = cal.get(Calendar.DAY_OF_MONTH).toString()
                    text.textSize = d * 0.34f
                    text.color = Color.argb(aInt, 255, 255, 255)
                    c.drawText(dayNum, x - text.measureText(dayNum) / 2, y + text.textSize * 0.35f, text)

                    val sub = info.nextAlarm?.let { "⏰ ${timeText(it).substringBefore(' ')}" }
                        ?: SimpleDateFormat("MMM", Locale.getDefault()).format(cal.time).uppercase()
                    text.textSize = d * 0.115f
                    text.color = Color.argb((aInt * 0.85f).toInt(), 220, 225, 232)
                    c.drawText(sub, x - text.measureText(sub) / 2, y + r * 0.58f, text)
                }
            }
        }

        // Gold pin badge if pinned
        if (wItem.key in pinnedItemKeys && d > dp(20f)) {
            val pr = (d * 0.10f).coerceIn(dp(3.5f), dp(6.5f))
            val px = x - d * 0.34f
            val py = y - d * 0.34f
            fill.color = Color.argb(aInt, 0, 0, 0)
            c.drawCircle(px, py, pr * 1.35f, fill)
            fill.color = Color.argb(aInt, 255, 214, 10)
            c.drawCircle(px, py, pr, fill)
        }
    }

    private fun drawIcon(c: Canvas, b: android.graphics.Bitmap, x: Float, y: Float, d: Float, alpha: Float, badge: Boolean, pinned: Boolean) {
        if (alpha <= 0.004f || d < 2f) return
        val aInt = (alpha * 255).toInt().coerceIn(0, 255)
        bitmapPaint.alpha = aInt
        rect.set(x - d / 2, y - d / 2, x + d / 2, y + d / 2)
        c.drawBitmap(b, null, rect, bitmapPaint)
        if (badge && d > dp(22f)) {
            // Animated breathing neon Pulse Ring around any app/folder with an active notification!
            val pulse = 0.5f + 0.5f * sin(System.currentTimeMillis() / 280.0).toFloat()
            stroke.strokeWidth = dp(2.0f + 1.2f * pulse)
            stroke.color = withAlpha(accent, (aInt * (0.45f + 0.45f * pulse)).toInt())
            c.drawCircle(x, y, d * (0.49f + 0.04f * pulse), stroke)

            val br = (d * 0.13f).coerceIn(dp(4f), dp(8f))
            val bx = x + d * 0.34f
            val by = y - d * 0.34f
            fill.color = Color.argb(aInt, 0, 0, 0)
            c.drawCircle(bx, by, br * 1.28f, fill)
            fill.color = withAlpha(accent, aInt)
            c.drawCircle(bx, by, br, fill)
        }
        if (pinned && d > dp(20f)) {
            val pr = (d * 0.10f).coerceIn(dp(3.5f), dp(6.5f))
            val px = x - d * 0.34f
            val py = y - d * 0.34f
            fill.color = Color.argb(aInt, 0, 0, 0)
            c.drawCircle(px, py, pr * 1.35f, fill)
            fill.color = Color.argb(aInt, 255, 214, 10)
            c.drawCircle(px, py, pr, fill)
        }
    }

    private fun withAlpha(color: Int, a: Int) = Color.argb(
        Color.alpha(color) * a.coerceIn(0, 255) / 255,
        Color.red(color), Color.green(color), Color.blue(color),
    )

    // ---------------------------------------------------------------- keyboard / search input

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty()) {
                    searchQuery += text.toString().replace("\n", "")
                    listScroll = 0f
                    invalidate()
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && searchQuery.isNotEmpty()) {
                    searchQuery = searchQuery.dropLast(beforeLength.coerceAtMost(searchQuery.length))
                    listScroll = 0f
                    invalidate()
                }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                hideKeyboard()
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((searching || gridSearching) && apps.target == 1f) {
            when (keyCode) {
                KeyEvent.KEYCODE_DEL -> {
                    if (searchQuery.isNotEmpty()) {
                        searchQuery = searchQuery.dropLast(1)
                        listScroll = 0f
                        invalidate()
                    }
                    return true
                }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    hideKeyboard()
                    filteredAlphabetical.firstOrNull()?.let {
                        clearSearch()
                        onLaunch(it, RectF(width / 2f - 40f, height / 2f - 40f, width / 2f + 40f, height / 2f + 40f))
                    }
                    return true
                }
                else -> {
                    val ch = event.unicodeChar
                    if (ch != 0 && !Character.isISOControl(ch)) {
                        searchQuery += ch.toChar()
                        listScroll = 0f
                        invalidate()
                        return true
                    }
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startSearch() {
        searching = true
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        invalidate()
    }

    private fun clearSearch() {
        searchQuery = ""
        listScroll = 0f
        gridSearching = false
        constellationIn.target = 0f
        constellationFilter = "ALL"
        constellationCategoryFilter = null
        constellationZoom.target = 1f
        constellationPanX.target = 0f
        constellationPanY.target = 0f
        hideKeyboard()
        invalidate()
    }

    private fun hideKeyboard() {
        if (!searching) return
        searching = false
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
        invalidate()
    }

    // ---------------------------------------------------------------- touch & pinch

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var longPressed = false
    private var pinching = false
    private var modeAtDown = MODE_FACE
    private var velocity: VelocityTracker? = null
    private val longPress = Runnable {
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        when {
            clusterSheet.target == 1f -> {}
            stackIn.target == 1f -> {
                stack.cardRects.lastOrNull { it.first.contains(downX, downY) }?.let { (_, card) ->
                    onTogglePinCard(card.id)
                }
            }
            apps.target == 1f -> {
                hitEntryAt(downX, downY)?.let { h ->
                    val fallbackSlot = activeGridItems.indexOfFirst { it.key == h.item.key }
                    onLongPressItem(h.item, if (h.slotIndex >= 0) h.slotIndex else fallbackSlot)
                }
            }
            onFace -> onLongPressFace()
        }
    }

    private var pinchStartZoom = 1f
    private var pinchCumulative = 1f
    private var crownStartZoom = 1f
    private var lastEmptyTapTime = 0L

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (apps.target == 1f && list.target == 0f && clusterSheet.target == 0f) {
                markHintSeen(HINT_PINCH)
                pinching = true
                removeCallbacks(longPress)
                pinchCumulative = 1f
                pinchStartZoom = when {
                    gridSearching -> constellationZoom.value
                    overview.target >= 0.5f -> 0.48f
                    else -> userZoom.value
                }
                return true
            }
            return false
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pinchCumulative *= detector.scaleFactor
            if (gridSearching) {
                val cz = (pinchStartZoom * pinchCumulative).coerceIn(0.65f, 1.40f)
                if (cz < 0.72f) {
                    clearSearch()
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    return true
                }
                constellationZoom.snap(cz)
                invalidate()
                return true
            }
            if (folderIn.target == 1f) {
                if (pinchCumulative < 0.78f) {
                    closeFolder()
                }
                return true
            }
            if (widgetIn.target == 1f) {
                if (pinchCumulative < 0.78f) {
                    closeWidgetPod()
                }
                return true
            }
            if (grid.target == 0f && pinchCumulative < 0.90f) {
                grid.target = 1f
            }
            val newZ = (pinchStartZoom * pinchCumulative).coerceIn(0.45f, 1.45f)
            if (newZ > 1.36f) {
                // Pinch out wide -> burst folders open into the Unraveled Star Constellation!
                overview.snap(0f)
                userZoom.snap(1f)
                startConstellationSearch()
                return true
            }
            overview.snap(0f)
            userZoom.snap(newZ)
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (gridSearching) {
                constellationZoom.target = constellationZoom.value.coerceIn(0.80f, 1.30f)
                invalidate()
                return
            }
            val z = userZoom.value
            if (z < 0.62f) {
                userZoom.target = 0.48f
                overview.target = 1f
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            } else {
                overview.target = 0f
                userZoom.target = if (abs(z - 1f) < 0.09f) 1f else z.coerceIn(0.72f, 1.28f)
            }
            invalidate()
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                dragging = false; longPressed = false; pinching = false
                scrubbingLetter = null
                orbitalVelocity = 0f
                grabbedPoolIndex = if (clusterTopology == ClusterTopology.LIQUID_POOL && apps.target > 0.5f && list.target < 0.5f) {
                    hitEntryAt(e.x, e.y)?.slotIndex ?: -1
                } else -1
                modeAtDown = when {
                    controlIn.target > 0.5f -> MODE_CONTROL
                    stackIn.target > 0.5f -> MODE_STACK
                    apps.target > 0.5f -> MODE_APPS
                    else -> MODE_FACE
                }
                edgeCrownDragging = modeAtDown == MODE_APPS && clusterSheet.target == 0f && !gridSearching &&
                    e.x > width - dp(34f) && e.y > topInset + dp(60f) && e.y < height - bottomInset - dp(70f)
                if (edgeCrownDragging) {
                    crownStartZoom = if (overview.target >= 0.5f) 0.48f else userZoom.value
                }
                if (edgeCrownDragging && list.target == 1f) {
                    scrubAlphabetAt(e.y)
                    return true
                }
                velocity?.recycle(); velocity = VelocityTracker.obtain().apply { addMovement(e) }
                listVelocity = 0f
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinching) return true
                if (edgeCrownDragging && list.target == 1f) {
                    removeCallbacks(longPress)
                    scrubAlphabetAt(e.y)
                    return true
                }
                velocity?.addMovement(e)
                if (!dragging && hypot(e.x - downX, e.y - downY) > slop) {
                    dragging = true
                    removeCallbacks(longPress)
                    if (!edgeCrownDragging && apps.target == 1f && list.target == 0f && grid.target == 0f && clusterSheet.target == 0f) {
                        grid.target = 1f
                    }
                }
                if (dragging && !longPressed && clusterSheet.target == 0f) {
                    if (edgeCrownDragging && list.target == 0f) {
                        scrubCrownZoom(e.y - downY)
                    } else if (controlIn.target == 1f && controlCenter.volumeBarRect.contains(downX, downY)) {
                        val v = ((e.x - controlCenter.volumeBarRect.left) / controlCenter.volumeBarRect.width()).coerceIn(0f, 1f)
                        onControlAction(ControlAction.VOLUME_UP, v)
                    } else {
                        drag(e.x - lastX, e.y - lastY, e.y - downY)
                    }
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (scrubbingLetter != null || edgeCrownDragging) {
                    scrubbingLetter = null
                    edgeCrownDragging = false
                    dragging = false
                    invalidate()
                    return true
                }
                if (pinching) {
                    pinching = false
                    dragging = false
                    invalidate()
                    return true
                }
                velocity?.addMovement(e)
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                val vy = velocity?.yVelocity ?: 0f
                if (!longPressed) {
                    if (dragging && clusterSheet.target == 0f) release(vx, vy, e.y - downY)
                    else tap(e.x, e.y)
                }
                dragging = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                dragging = false
                pinching = false
                scrubbingLetter = null
                edgeCrownDragging = false
                invalidate()
            }
        }
        return true
    }

    private fun scrubAlphabetAt(y: Float) {
        val top = topInset + 0.02f * width + dp(68f)
        val bottom = height - bottomInset - 0.02f * width - dp(80f)
        val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ#"
        val idx = (((y - top) / (bottom - top)) * letters.length).toInt().coerceIn(0, letters.lastIndex)
        val ch = letters[idx]
        if (ch != scrubbingLetter) {
            scrubbingLetter = ch
            markHintSeen(HINT_SCRUB)
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            val listApps = filteredAlphabetical
            val targetIdx = if (ch == '#') {
                listApps.indexOfFirst { !it.label.firstOrNull()?.uppercaseChar()?.isLetter()!! }
            } else {
                listApps.indexOfFirst { (it.label.firstOrNull()?.uppercaseChar() ?: ' ') >= ch }
            }
            if (targetIdx >= 0) {
                val maxScroll = max(0f, listApps.size * dp(Metrics.PHONE_LIST_PITCH_DP) - (height - topInset - bottomInset) * 0.6f)
                listScroll = (targetIdx * dp(Metrics.PHONE_LIST_PITCH_DP)).coerceIn(0f, maxScroll)
            }
            invalidate()
        }
    }

    private fun scrubCrownZoom(totalDy: Float) {
        markHintSeen(HINT_CROWN)
        if (grid.target == 0f) grid.target = 1f
        val newZ = (crownStartZoom - totalDy / dp(170f)).coerceIn(0.46f, 1.32f)
        val wasOv = userZoom.value < 0.58f || overview.target >= 0.5f
        val nowOv = newZ < 0.58f
        if (wasOv != nowOv) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        overview.snap(0f)
        userZoom.snap(newZ)
        userZoom.target = if (newZ < 0.58f) 0.48f else newZ
        overview.target = if (newZ < 0.58f) 1f else 0f
        invalidate()
    }

    private fun drag(dx: Float, dy: Float, totalDy: Float) {
        val w = width.toFloat()
        when (modeAtDown) {
            MODE_CONTROL -> {
                if (totalDy < 0) controlIn.snap((1f + totalDy / (0.4f * height)).coerceIn(0f, 1f))
            }
            MODE_STACK -> {
                val pitch = (Metrics.CARD_H + Metrics.CARD_GAP) * w
                stackScroll.snap((stackScroll.value - dy / pitch).coerceIn(-0.6f, stack.maxScroll + 0.3f))
            }
            MODE_APPS -> {
                when {
                    list.target == 1f -> {
                        hideKeyboard()
                        listScroll -= dy
                    }
                    gridSearching -> {
                        hideKeyboard()
                        val bound = unit * 0.85f
                        constellationPanX.snap((constellationPanX.value + dx).coerceIn(-bound, bound))
                        constellationPanY.snap((constellationPanY.value + dy).coerceIn(-bound, bound))
                    }
                    folderIn.target == 1f -> {
                        folderPanX.snap(folderPanX.value + dx)
                        folderPanY.snap(folderPanY.value + dy)
                    }
                    else -> {
                        if (overview.target == 1f) overview.target = 0f
                        when (clusterTopology) {
                            ClusterTopology.CHRONO_BEZEL -> {
                                val cx = width / 2f
                                val cy = height / 2f
                                val rFromCenter = hypot(lastX - cx, lastY - cy)
                                val dAng = if (rFromCenter > dp(24f)) {
                                    val a1 = atan2(lastY - cy, lastX - cx)
                                    val a2 = atan2((lastY + dy) - cy, (lastX + dx) - cx)
                                    var delta = a2 - a1
                                    if (delta > PI.toFloat()) delta -= (2.0 * PI).toFloat()
                                    if (delta < -PI.toFloat()) delta += (2.0 * PI).toFloat()
                                    delta
                                } else {
                                    (dx - dy) / dp(140f)
                                }
                                val oldTick = (orbitalAngle / 0.30f).roundToInt()
                                orbitalAngle += dAng
                                val newTick = (orbitalAngle / 0.30f).roundToInt()
                                if (oldTick != newTick) {
                                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                                panX.snap(0f)
                                panY.snap(0f)
                            }
                            ClusterTopology.ORBITALS -> {
                                val cx = width / 2f
                                val cy = height / 2f
                                val rFromCenter = hypot(lastX - cx, lastY - cy)
                                if (rFromCenter > dp(36f) && abs(panX.value) < unit * 0.25f && abs(panY.value) < unit * 0.25f) {
                                    val a1 = atan2(lastY - cy, lastX - cx)
                                    val a2 = atan2((lastY + dy) - cy, (lastX + dx) - cx)
                                    var delta = a2 - a1
                                    if (delta > PI.toFloat()) delta -= (2.0 * PI).toFloat()
                                    if (delta < -PI.toFloat()) delta += (2.0 * PI).toFloat()
                                    val oldTick = (orbitalAngle / 0.32f).roundToInt()
                                    orbitalAngle += delta
                                    val newTick = (orbitalAngle / 0.32f).roundToInt()
                                    if (oldTick != newTick) {
                                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    }
                                } else {
                                    panX.snap(panX.value + dx)
                                    panY.snap(panY.value + dy)
                                }
                            }
                            ClusterTopology.RIVER, ClusterTopology.HOURGLASS -> {
                                panX.snap(0f)
                                panY.snap(panY.value + dy)
                            }
                            ClusterTopology.DNA_HELIX -> {
                                val oldTick = (orbitalAngle / 0.32f).roundToInt()
                                orbitalAngle += dx / dp(62f)
                                val newTick = (orbitalAngle / 0.32f).roundToInt()
                                if (oldTick != newTick) {
                                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                                panX.snap(0f)
                                panY.snap(panY.value + dy)
                            }
                            ClusterTopology.MAGNETIC_DOCK -> {
                                val oldTick = (orbitalAngle / 0.32f).roundToInt()
                                orbitalAngle += dx / dp(72f)
                                val newTick = (orbitalAngle / 0.32f).roundToInt()
                                if (oldTick != newTick) {
                                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                                panX.snap(0f)
                                panY.snap(0f)
                            }
                            ClusterTopology.LIQUID_POOL -> {
                                ensurePoolCapacity(activeGridItems.size, Metrics.GRID_PITCH * unit)
                                if (grabbedPoolIndex in poolPosX.indices && grabbedPoolIndex > 0) {
                                    poolPosX[grabbedPoolIndex] += dx
                                    poolPosY[grabbedPoolIndex] += dy
                                    poolVelX[grabbedPoolIndex] = dx * 38f
                                    poolVelY[grabbedPoolIndex] = dy * 38f
                                } else {
                                    // Stir the pool fluid around the finger!
                                    val cx = width / 2f
                                    val cy = height / 2f
                                    val fx = lastX - cx
                                    val fy = lastY - cy
                                    for (i in 1 until poolPosX.size) {
                                        val d = hypot(poolPosX[i] - fx, poolPosY[i] - fy)
                                        if (d < unit * 0.55f) {
                                            val wgt = (1f - d / (unit * 0.55f)).coerceIn(0.15f, 1f)
                                            poolVelX[i] += dx * 22f * wgt
                                            poolVelY[i] += dy * 22f * wgt
                                        }
                                    }
                                }
                                panX.snap(0f)
                                panY.snap(0f)
                            }
                            else -> {
                                panX.snap(panX.value + dx)
                                panY.snap(panY.value + dy)
                            }
                        }
                    }
                }
            }
            else -> {
                if (totalDy < 0) {
                    controlIn.snap(0f)
                    stackIn.snap((-totalDy / (0.6f * height)).coerceIn(0f, 1f))
                } else if (totalDy > 0) {
                    stackIn.snap(0f)
                    controlIn.snap((totalDy / (0.5f * height)).coerceIn(0f, 1f))
                }
            }
        }
        invalidate()
    }

    private fun release(vx: Float, vy: Float, totalDy: Float) {
        val w = width.toFloat()
        when (modeAtDown) {
            MODE_CONTROL -> {
                if (totalDy < -0.1f * height || vy < -1200f) {
                    if (honeycombHome) openFullHoneycomb() else toFace()
                } else controlIn.target = 1f
            }
            MODE_STACK -> {
                if (stackScroll.value < -0.3f) {
                    if (honeycombHome) openFullHoneycomb() else toFace()
                    return
                }
                val pitch = (Metrics.CARD_H + Metrics.CARD_GAP) * w
                stackScroll.target = (stackScroll.value - vy * 0.25f / pitch).roundToInt().toFloat().coerceIn(0f, stack.maxScroll)
            }
            MODE_APPS -> {
                when {
                    list.target == 1f -> listVelocity = -vy
                    gridSearching -> {
                        val bound = unit * 0.65f
                        constellationPanX.target = (constellationPanX.value + vx * 0.18f).coerceIn(-bound, bound)
                        constellationPanY.target = (constellationPanY.value + vy * 0.18f).coerceIn(-bound, bound)
                    }
                    folderIn.target == 1f -> snapFolderGrid(folderPanX.value + vx * 0.3f, folderPanY.value + vy * 0.3f)
                    else -> snapGrid(panX.value + vx * 0.3f, panY.value + vy * 0.3f)
                }
            }
            else -> {
                when {
                    totalDy < -0.12f * height || vy < -1500f -> openStack()
                    totalDy > 0.12f * height || vy > 1500f -> openControlCenter()
                    else -> { stackIn.target = 0f; controlIn.target = 0f }
                }
            }
        }
    }

    private fun snapGrid(px: Float, py: Float) {
        if (clusterTopology == ClusterTopology.CHRONO_BEZEL) {
            panX.target = 0f
            panY.target = 0f
            return
        }
        val w = unit
        if (clusterTopology == ClusterTopology.NEBULA) {
            val maxR = Metrics.GRID_PITCH * w * 3.1f
            panX.target = px.coerceIn(-maxR, maxR)
            panY.target = py.coerceIn(-maxR, maxR)
            return
        }
        val items = activeGridItems
        if (items.isEmpty()) return
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in items.indices) {
            if (i >= gridHexes.size) break
            computeTopologyWorldPos(i, items.size, Metrics.GRID_PITCH * w, tmp)
            val d = hypot(tmp[0] + px, tmp[1] + py)
            if (d < bestD) { bestD = d; best = i }
        }
        computeTopologyWorldPos(best, items.size, Metrics.GRID_PITCH * w, tmp)
        panX.target = if (clusterTopology == ClusterTopology.RIVER) 0f else -tmp[0]
        panY.target = -tmp[1]
    }

    private fun snapFolderGrid(px: Float, py: Float) {
        val folder = activeFolder ?: return
        if (folder.apps.isEmpty()) return
        val w = unit
        val pitchScale = if (bloomFolders) 0.92f else 1.08f
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in folder.apps.indices) {
            if (i >= folderHexes.size) break
            pointyCenter(folderHexes[i], Metrics.GRID_PITCH * w * pitchScale, tmp)
            val d = hypot(tmp[0] + px, tmp[1] + py)
            if (d < bestD) { bestD = d; best = i }
        }
        pointyCenter(folderHexes[best], Metrics.GRID_PITCH * w * pitchScale, tmp)
        folderPanX.target = -tmp[0]
        folderPanY.target = -tmp[1]
    }

    private fun stepListFling(dt: Float) {
        val listApps = filteredAlphabetical
        val maxScroll = max(0f, listApps.size * dp(Metrics.PHONE_LIST_PITCH_DP) - (height - topInset - bottomInset) * 0.55f)
        if (abs(listVelocity) > 1f) {
            listScroll += listVelocity * dt
            listVelocity *= (1f - 3.5f * dt).coerceAtLeast(0f)
        }
        if (!dragging) {
            if (listScroll < 0f) { listScroll *= (1f - 12f * dt).coerceAtLeast(0f); listVelocity = 0f }
            if (listScroll > maxScroll) { listScroll = maxScroll + (listScroll - maxScroll) * (1f - 12f * dt).coerceAtLeast(0f); listVelocity = 0f }
        }
    }

    private fun tap(x: Float, y: Float) {
        // Coach hint: tapping it dismisses it for good.
        if (!coachHintRect.isEmpty && coachHintRect.contains(x, y)) {
            coachHintId?.let { markHintSeen(it) }
            coachHintRect.setEmpty()
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            invalidate()
            return
        }

        // 0. Cluster Options Sheet taps
        if (clusterSheet.target == 1f) {
            if (topologyPickerOpen) {
                topologyRowRects.lastOrNull { it.first.contains(x, y) }?.let { (_, topo) ->
                    selectTopology(topo)
                    return
                }
                if (pickerBackRect.contains(x, y)) {
                    topologyPickerOpen = false
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    invalidate()
                    return
                }
                if (!sheetBoundsRect.contains(x, y)) {
                    topologyPickerOpen = false
                    clusterSheet.target = 0f
                    invalidate()
                }
                return
            }
            sheetRowRects.lastOrNull { it.first.contains(x, y) }?.let { (_, opt) ->
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                when (opt) {
                    ClusterOption.CLOSE_SHEET -> {
                        clusterSheet.target = 0f
                        invalidate()
                    }
                    ClusterOption.CONSTELLATION_SEARCH -> {
                        startConstellationSearch()
                    }
                    ClusterOption.CLUSTER_TOPOLOGY -> {
                        topologyPickerOpen = true
                        invalidate()
                    }
                    else -> {
                        onClusterOptionChanged(opt)
                    }
                }
                return
            }
            if (!sheetBoundsRect.contains(x, y)) {
                clusterSheet.target = 0f
                invalidate()
            }
            return
        }

        // 1. Control Center taps
        if (controlIn.target == 1f) {
            controlCenter.actionRects.lastOrNull { it.first.contains(x, y) }?.let { (_, act) ->
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                onControlAction(act, null)
                return
            }
            if (controlCenter.volumeBarRect.contains(x, y)) {
                val v = ((x - controlCenter.volumeBarRect.left) / controlCenter.volumeBarRect.width()).coerceIn(0f, 1f)
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onControlAction(ControlAction.VOLUME_UP, v)
                return
            }
            if (honeycombHome) openFullHoneycomb() else toFace()
            return
        }

        // 2. Smart Stack taps
        if (stackIn.target == 1f) {
            stack.appRects.lastOrNull { it.first.contains(x, y) }?.let { (r, app) ->
                onLaunch(app, RectF(r))
                return
            }
            stack.actionRects.lastOrNull { it.first.contains(x, y) }?.let { (_, act) ->
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                onCardAction(act)
                return
            }
            if (honeycombHome) openFullHoneycomb() else toFace()
            return
        }

        // 3. Apps View (Honeycomb / Constellation Search / Folder / List)
        if (apps.target == 1f) {
            if (!pill.isEmpty && pill.contains(x, y)) {
                clearSearch()
                if (folderIn.target == 1f) closeFolder()
                if (list.target < 0.5f && grid.target < 0.5f) {
                    grid.target = 1f
                } else {
                    list.target = if (list.target >= 0.5f) 0f else 1f
                    if (list.target == 0f) grid.target = 1f
                    preferListChanged(list.target == 1f)
                }
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                invalidate()
                return
            }
            if (gridSearching) {
                if (!searchClearRect.isEmpty && searchClearRect.contains(x, y)) {
                    if (searchQuery.isNotEmpty()) {
                        searchQuery = ""
                        hideKeyboard()
                        invalidate()
                    } else {
                        clearSearch()
                    }
                    return
                }
                if (!searchBarRect.isEmpty && searchBarRect.contains(x, y)) {
                    startSearch()
                    return
                }
                constellationChipRects.lastOrNull { it.first.contains(x, y) }?.let { (_, chip) ->
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    constellationFilter = chip
                    constellationCategoryFilter = null
                    searchQuery = ""
                    constellationPanX.target = 0f
                    constellationPanY.target = 0f
                    hideKeyboard()
                    invalidate()
                    return
                }
                constellationCategoryChipRects.lastOrNull { it.first.contains(x, y) }?.let { (_, grp) ->
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    if (constellationCategoryFilter == grp) {
                        constellationCategoryFilter = null
                    } else {
                        constellationCategoryFilter = grp
                        constellationFilter = "ALL"
                    }
                    searchQuery = ""
                    constellationPanX.target = 0f
                    constellationPanY.target = 0f
                    hideKeyboard()
                    invalidate()
                    return
                }
                val hit = hitEntryAt(x, y)
                if (hit != null && hit.item is App) {
                    val r = RectF(hit.x - hit.r, hit.y - hit.r, hit.x + hit.r, hit.y + hit.r)
                    onLaunch(hit.item, r)
                } else if (searching) {
                    hideKeyboard()
                } else {
                    constellationPanX.target = 0f
                    constellationPanY.target = 0f
                    constellationZoom.target = 1f
                    invalidate()
                }
                return
            }
            if (list.target >= 0.5f) {
                if (!searchClearRect.isEmpty && searchClearRect.contains(x, y)) {
                    clearSearch()
                    return
                }
                if (!searchBarRect.isEmpty && searchBarRect.contains(x, y)) {
                    startSearch()
                    return
                }
            }
            if (widgetIn.target >= 0.5f) {
                if (!folderHeaderRect.isEmpty && folderHeaderRect.contains(x, y)) {
                    closeWidgetPod()
                    return
                }
                widgetActionRects.lastOrNull { it.first.contains(x, y) }?.let { (_, actId) ->
                    handleWidgetPodAction(actId)
                    return
                }
                val islandR = min(width * 0.45f, unit * 0.56f)
                if (hypot(x - width / 2f, y - height / 2f) > islandR) {
                    closeWidgetPod()
                }
                return
            }
            if (folderIn.target >= 0.5f) {
                if (!folderHeaderRect.isEmpty && folderHeaderRect.contains(x, y)) {
                    closeFolder()
                    return
                }
                val hit = hitEntryAt(x, y)
                if (hit != null && hit.item is App) {
                    onLaunch(hit.item, RectF(hit.x - hit.r, hit.y - hit.r, hit.x + hit.r, hit.y + hit.r))
                } else {
                    closeFolder()
                }
                return
            }
            if (list.target < 0.5f) {
                if (!clusterPillRect.isEmpty && clusterPillRect.contains(x, y)) {
                    openClusterSheet()
                    return
                }
                if (!centerSearchPillRect.isEmpty && centerSearchPillRect.contains(x, y)) {
                    startConstellationSearch()
                    return
                }
                if (clusterTopology == ClusterTopology.NEBULA) {
                    nebulaHeaderRects.lastOrNull { it.first.contains(x, y) }?.let { (_, gx, gy) ->
                        panX.target = -gx
                        panY.target = -gy
                        overview.target = 0f
                        userZoom.target = 1f
                        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        invalidate()
                        return
                    }
                }
                if (clusterTopology == ClusterTopology.CHRONO_BEZEL && !chronoHudRect.isEmpty && chronoHudRect.contains(x, y)) {
                    val clockWidget = activeGridItems.filterIsInstance<WidgetBubble>().firstOrNull { it.kind == WidgetKind.CORE_CLOCK }
                    if (clockWidget != null) {
                        openWidgetPod(clockWidget)
                    } else {
                        toFace()
                    }
                    return
                }
            }

            val hit = hitEntryAt(x, y)
            if (hit == null) {
                val now = SystemClock.uptimeMillis()
                if (now - lastEmptyTapTime < 340L) {
                    // Double-tap empty space -> recenter & reset zoom to 1.0x!
                    panX.target = 0f
                    panY.target = 0f
                    userZoom.target = 1f
                    overview.target = 0f
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    invalidate()
                    lastEmptyTapTime = 0L
                    return
                }
                lastEmptyTapTime = now
                if (grid.target < 0.5f && list.target < 0.5f) {
                    grid.target = 1f
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    invalidate()
                }
                return
            }
            val item = hit.item

            if (overview.target >= 0.5f || userZoom.value < 0.65f) {
                if (clusterTopology == ClusterTopology.NEBULA) {
                    panX.target -= (hit.x - width / 2f) * 1.6f
                    panY.target -= (hit.y - height / 2f) * 1.6f
                } else if (clusterTopology != ClusterTopology.CHRONO_BEZEL) {
                    val idx = activeGridItems.indexOf(item)
                    if (idx >= 0 && idx < gridHexes.size) {
                        computeTopologyWorldPos(idx, activeGridItems.size, Metrics.GRID_PITCH * unit, tmp)
                        panX.target = if (clusterTopology == ClusterTopology.RIVER) 0f else -tmp[0]
                        panY.target = -tmp[1]
                    }
                }
                overview.target = 0f
                userZoom.target = 1f
                if (item is Folder) openFolder(item)
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                invalidate()
                return
            }

            when (item) {
                is App -> onLaunch(item, RectF(hit.x - hit.r, hit.y - hit.r, hit.x + hit.r, hit.y + hit.r))
                is WidgetBubble -> openWidgetPod(item)
                is Folder -> {
                    val dia = hit.r * 2f
                    val isCenteredFolder = hypot(hit.x - width / 2f, hit.y - height / 2f) <= unit * 0.22f &&
                        dia >= unit * Metrics.GRID_CENTER_D * 0.90f
                    if (quickSnipe && isCenteredFolder && item.apps.size > 1) {
                        val sniped = findSnipedMiniIcon(item, hit.x, hit.y, dia, x, y)
                        if (sniped != null) {
                            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            onLaunch(sniped.first, sniped.second)
                            return
                        }
                    }
                    openFolder(item)
                }
            }
            return
        }

        // 4. On the Watch Face
        if (face.topRects[0].contains(x, y)) {
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onCardAction(CardAction.OPEN_BATTERY)
            return
        }
        if (face.topRects[1].contains(x, y)) {
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onCardAction(CardAction.OPEN_CALENDAR)
            return
        }
        if (face.topRects[2].contains(x, y)) {
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onCardAction(CardAction.OPEN_ALARMS)
            return
        }
        for ((i, r) in face.favoriteRects.withIndex()) {
            val app = face.favorites.getOrNull(i) ?: continue
            if (!r.isEmpty && r.contains(x, y)) { onLaunch(app, RectF(r)); return }
        }
        if (honeycombHome) openFullHoneycomb() else openApps()
    }

    private fun handleWidgetPodAction(actionId: String) {
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        when (actionId) {
            "CLOCK_FACE" -> toFace()
            "CLOCK_STACK" -> openStack()
            "CLOCK_CONTROLS" -> openControlCenter()
            "SYS_WIDGETS" -> {
                closeWidgetPod()
                onClusterOptionChanged(ClusterOption.SYSTEM_WIDGETS)
            }
            "MEDIA_PREV" -> onCardAction(CardAction.MEDIA_PREV)
            "MEDIA_PLAY" -> onCardAction(CardAction.MEDIA_PLAY_PAUSE)
            "MEDIA_NEXT" -> onCardAction(CardAction.MEDIA_NEXT)
            "MEDIA_OPEN" -> onCardAction(CardAction.MEDIA_OPEN)
            "CTRL_WIFI" -> onControlAction(ControlAction.WIFI, null)
            "CTRL_BT" -> onControlAction(ControlAction.BLUETOOTH, null)
            "CTRL_TORCH" -> onControlAction(ControlAction.TORCH, null)
            "CTRL_RINGER" -> onControlAction(ControlAction.RINGER, null)
            "VITALS_BATTERY" -> onCardAction(CardAction.OPEN_BATTERY)
            "CAL_OPEN" -> onCardAction(CardAction.OPEN_CALENDAR)
            "CAL_ALARMS" -> onCardAction(CardAction.OPEN_ALARMS)
            "TIMER_60" -> setTimerPreset(60)
            "TIMER_180" -> setTimerPreset(180)
            "TIMER_300" -> setTimerPreset(300)
            "TIMER_600" -> setTimerPreset(600)
            "TIMER_TOGGLE" -> {
                if (timerRunning) {
                    timerRemainingMs = (timerEndUptimeMs - SystemClock.uptimeMillis()).coerceAtLeast(0L)
                    timerRunning = false
                } else {
                    if (timerRemainingMs <= 0L) timerRemainingMs = timerTotalSec * 1000L
                    timerEndUptimeMs = SystemClock.uptimeMillis() + timerRemainingMs
                    timerRunning = true
                }
                invalidate()
            }
            "TIMER_RESET" -> {
                timerRunning = false
                timerRemainingMs = timerTotalSec * 1000L
                invalidate()
            }
        }
    }

    private fun setTimerPreset(seconds: Int) {
        timerTotalSec = seconds
        timerRemainingMs = seconds * 1000L
        timerEndUptimeMs = SystemClock.uptimeMillis() + timerRemainingMs
        timerRunning = true
        invalidate()
    }

    /**
     * #3: Checks if a tap at ([tx], [ty]) inside a closed [folder] bubble centered at ([cx], [cy])
     * with diameter [dia] targeted one of the 6 outer mini-icons rather than the center open hub.
     */
    private fun findSnipedMiniIcon(folder: Folder, cx: Float, cy: Float, dia: Float, tx: Float, ty: Float): Pair<App, RectF>? {
        val centerDist = hypot(tx - cx, ty - cy)
        if (centerDist <= dia * 0.20f) return null
        val orbit = dia * 0.265f
        val miniR = dia * 0.115f
        var bestApp: App? = null
        var bestDist = Float.MAX_VALUE
        var bestX = cx
        var bestY = cy
        val count = minOf(7, folder.apps.size)
        for (i in 1 until count) {
            val ang = Math.toRadians((i - 1) * 60.0 - 90.0)
            val mx = cx + (cos(ang) * orbit).toFloat()
            val my = cy + (sin(ang) * orbit).toFloat()
            val d = hypot(tx - mx, ty - my)
            if (d < bestDist) {
                bestDist = d
                bestApp = folder.apps[i]
                bestX = mx
                bestY = my
            }
        }
        if (bestApp != null && bestDist <= miniR && bestDist < centerDist) {
            return bestApp to RectF(bestX - miniR, bestY - miniR, bestX + miniR, bestY + miniR)
        }
        return null
    }

    var preferListChanged: (Boolean) -> Unit = {}

    private fun hitEntryAt(x: Float, y: Float): Hit? = hits.lastOrNull { hypot(x - it.x, y - it.y) <= it.r }

    private companion object {
        const val HINT_PINCH = "pinch"
        const val HINT_CROWN = "crown"
        const val HINT_PIN = "pin"
        const val HINT_SCRUB = "scrub"
        const val MODE_FACE = 0
        const val MODE_APPS = 1
        const val MODE_STACK = 2
        const val MODE_CONTROL = 3
        val FOCUS_RING_ORDER = listOf(Hex(0, -1), Hex(1, -1), Hex(1, 0), Hex(0, 1), Hex(-1, 1), Hex(-1, 0))
    }
}
