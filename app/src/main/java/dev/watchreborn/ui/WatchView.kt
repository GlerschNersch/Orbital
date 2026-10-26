package dev.watchreborn.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
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
    PER_LAYOUT_PINS,
    USAGE_GROWTH,
    EDIT_LAYOUT,
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
    UNHIDE_FOLDERS,
    MORE_OPTIONS,
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
    private val onPlaceItem: (GridItem, Int) -> Unit,
    private val onFileIntoFolder: (App, Folder, Int) -> Unit,
    private val onCreateFolder: (App, App) -> Unit,
    private val onRenameFolder: (Folder) -> Unit,
    private val onDeleteFolder: (Folder) -> Unit,
    private val onRemoveFromFolder: (Folder, App) -> Unit,
    private val onHideFolder: (Folder) -> Unit,
    private val onUnpinItem: (GridItem) -> Unit,
    private val onReorderFolderMember: (Folder, Int, Int) -> Unit,
    private val onQuickInfo: (App) -> Unit,
    private val onQuickUninstall: (App) -> Unit,
    private val onTopoViewSaved: (ClusterTopology, Float, Float, Float) -> Unit = { _, _, _, _ -> },
) : View(context) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun getAccessibilityNodeProvider(host: View) = a11yProvider()
        }
    }

    // --- Accessibility (TalkBack) -------------------------------------------
    // The whole UI is one canvas View; WatchAccessibilityProvider exposes the
    // interactive elements as virtual views built from the same hit data the
    // touch handler uses. Created lazily: the system asks for the provider
    // only once the view is attached and accessibility is on.
    private var a11y: WatchAccessibilityProvider? = null

    override fun getAccessibilityNodeProvider(): WatchAccessibilityProvider = a11yProvider()

    private fun a11yProvider(): WatchAccessibilityProvider =
        a11y ?: WatchAccessibilityProvider(this, ::a11yNodes, ::a11yActivate).also { a11y = it }

    private fun a11yNodes(): List<WatchAccessibilityProvider.Node> {
        // The Context Card's rows (1.8.0): the same rects the card draws and
        // the same cardLabel() text the sighted UI shows.
        if (cardMenuOpen) {
            val item = cardItem ?: return emptyList()
            return cardRows.map { row ->
                WatchAccessibilityProvider.Node(
                    "card:" + row.kind, cardLabel(row.kind, item),
                    Rect(row.rect.left.toInt(), row.rect.top.toInt(), row.rect.right.toInt(), row.rect.bottom.toInt()),
                    row.rect.centerX(), row.rect.centerY(),
                )
            }
        }
        // The Cluster sheet's rows (1.8.0), from the rect/label lists the
        // sheet draw builds. The topology picker opens over the sheet: its
        // rows come from topologyRowRects, plus the Back pill (1.9.1).
        if (clusterSheet.target > 0f) {
            if (topologyPickerOpen) {
                val out = ArrayList<WatchAccessibilityProvider.Node>()
                for ((rect, topo) in topologyRowRects) {
                    if (rect.isEmpty) continue
                    val label = topo.fullTitle + if (topo == clusterTopology) ", current layout" else ""
                    out += WatchAccessibilityProvider.Node(
                        "topo:" + topo.name, label,
                        Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt()),
                        rect.centerX(), rect.centerY(),
                    )
                }
                if (!pickerBackRect.isEmpty) {
                    out += WatchAccessibilityProvider.Node(
                        "topo:back", "Back",
                        Rect(pickerBackRect.left.toInt(), pickerBackRect.top.toInt(), pickerBackRect.right.toInt(), pickerBackRect.bottom.toInt()),
                        pickerBackRect.centerX(), pickerBackRect.centerY(),
                    )
                }
                return out
            }
            return sheetRowLabels.mapIndexed { i, (rect, label) ->
                WatchAccessibilityProvider.Node(
                    "sheet:$i", label,
                    Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt()),
                    rect.centerX(), rect.centerY(),
                )
            }
        }
        // Control Center tiles (1.9.1): the same action rects the touch
        // handler uses, deduped per action (last rect wins, as in tap()).
        if (controlIn.value > 0.5f) {
            val byAction = LinkedHashMap<ControlAction, RectF>()
            for ((rect, act) in controlCenter.actionRects) {
                if (!rect.isEmpty) byAction[act] = rect
            }
            return byAction.map { (act, rect) ->
                WatchAccessibilityProvider.Node(
                    "control:" + act.name, controlCenter.spokenLabel(act),
                    Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt()),
                    rect.centerX(), rect.centerY(),
                )
            }
        }
        // Widget pod buttons (1.9.1): labels captured by the pod draw, plus
        // the header pill that closes the pod.
        if (widgetIn.target > 0f) {
            val out = ArrayList<WatchAccessibilityProvider.Node>()
            if (!folderHeaderRect.isEmpty) {
                out += WatchAccessibilityProvider.Node(
                    "pod:close", "Close",
                    Rect(folderHeaderRect.left.toInt(), folderHeaderRect.top.toInt(), folderHeaderRect.right.toInt(), folderHeaderRect.bottom.toInt()),
                    folderHeaderRect.centerX(), folderHeaderRect.centerY(),
                )
            }
            for ((i, pair) in widgetActionLabels.withIndex()) {
                val (rect, label) = pair
                if (rect.isEmpty || label.isBlank()) continue
                out += WatchAccessibilityProvider.Node(
                    "pod:$i", label,
                    Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt()),
                    rect.centerX(), rect.centerY(),
                )
            }
            return out
        }
        if (stackIn.value > 0.5f) {
            val out = ArrayList<WatchAccessibilityProvider.Node>()
            for ((rect, card) in stack.cardRects) {
                if (rect.isEmpty) continue
                val label = listOf(card.title, card.big, card.detail)
                    .filter { it.isNotBlank() }.joinToString(", ")
                out += WatchAccessibilityProvider.Node(
                    "stack:" + card.id, label,
                    Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt()),
                    rect.centerX(), rect.centerY(),
                )
            }
            return out
        }
        if (apps.target < 0.5f) {
            val out = ArrayList<WatchAccessibilityProvider.Node>()
            for ((i, r) in face.favoriteRects.withIndex()) {
                val app = face.favorites.getOrNull(i) ?: continue
                if (r.isEmpty) continue
                out += WatchAccessibilityProvider.Node(
                    "face:" + app.key, app.label,
                    Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()),
                    r.centerX(), r.centerY(),
                )
            }
            return out
        }
        // Grid / list / search / folder / overview bubbles from the touch hit
        // list; keep the last-drawn entry per item, like hitEntryAt does.
        val byKey = LinkedHashMap<String, Hit>()
        for (h in hits) byKey[h.item.key] = h
        return byKey.values.map { h ->
            val label = when (val item = h.item) {
                is Folder -> item.label + " folder"
                else -> item.label
            }
            WatchAccessibilityProvider.Node(
                h.item.key, label,
                Rect((h.x - h.r).toInt(), (h.y - h.r).toInt(), (h.x + h.r).toInt(), (h.y + h.r).toInt()),
                h.x, h.y,
            )
        }
    }

    private fun a11yActivate(node: WatchAccessibilityProvider.Node) {
        tap(node.centerX, node.centerY)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        a11y?.takeIf { it.touchExplorationEnabled }?.let { provider ->
            when (event.action) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE ->
                    provider.onHover(event.x, event.y, exit = false)
                MotionEvent.ACTION_HOVER_EXIT ->
                    provider.onHover(event.x, event.y, exit = true)
            }
        }
        return super.onHoverEvent(event)
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

    /** Drag-to-place state: a grid bubble picked up by long-press, carried by the finger. */
    private var placeItem: GridItem? = null
    private var placeFromSlot: Int = -1
    private var placeHoverSlot: Int = -1
    // Custom-folder creation (1.5.0): holding a carried app over another app
    // for a dwell arms folder creation; releasing then creates a folder
    // instead of placing. The arm is a posted runnable so a still finger
    // (no MOVE events) still arms.
    private var folderArmSlot: Int = -1
    private var folderArmRunnable: Runnable? = null
    private var folderArmed: Boolean = false
    private var placeX: Float = 0f
    private var placeY: Float = 0f
    private var placeMoved: Boolean = false
    private var placeInFolder: Boolean = false
    private var placeFolder: Folder? = null
    private val releasePinRect = RectF()
    /** Set during a frame when a notification-badged bubble is drawn on
     *  screen; gates the 75ms pulse loop (1.8.0 battery pass). */
    private var frameBadgeVisible = false
    /** "Remove from folder" drop zone shown while dragging a folder member. */
    private val folderRemoveZone = RectF()
    /** Header action buttons of the open folder (rebuilt by the header draws). */
    private val folderRenameRect = RectF()
    private val folderDeleteRect = RectF()
    private val folderHideRect = RectF()

    // --- Context Card (1.2.0 prototype): replaces the 1.1.0 Center Stage,
    // which Matt found too full-screen. Modelled on the iOS Haptic Touch
    // menu he sent: the grid stays visible under a light dim, the pressed
    // bubble lifts in place, and a compact card of action rows anchors next
    // to it (below when there's room, above when there isn't). Nothing
    // blooms while holding; rows are tapped as a normal second tap.
    private val cardMenu = Spring(0f, 0.28f)
    private var cardItem: GridItem? = null
    /** The custom folder whose member the card was opened for, if any. */
    private var cardFolder: Folder? = null
    private var cardSlot: Int = -1
    private var cardOpenedMs: Long = 0L

    /** Where the item sits in the grid — the card anchors to this spot. */
    private var cardOriginX: Float = 0f
    private var cardOriginY: Float = 0f
    private var cardOriginR: Float = 0f
    private val cardRect = RectF()

    /** One context-card row: its live rect (recomputed every frame) plus its action kind. */
    private class CardRow(val kind: Int, val rect: RectF)

    private val cardRows = ArrayList<CardRow>()

    private fun openCardMenu(item: GridItem, slot: Int, originX: Float, originY: Float, originR: Float) {
        cardItem = item
        cardSlot = slot
        cardOriginX = originX
        cardOriginY = originY
        cardOriginR = originR
        cardOpenedMs = SystemClock.uptimeMillis()
        cardMenu.target = 1f
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        invalidate()
    }

    private fun closeCardMenu() {
        cardFolder = null
        cardMenu.target = 0f
        invalidate()
    }

    private val cardMenuOpen: Boolean
        get() = cardItem != null && (cardMenu.target > 0f || cardMenu.value > 0.01f)

    /** Keys of apps that may be uninstalled (not system apps, main user). Set by the activity. */
    var uninstallableKeys: Set<String> = emptySet()

    /** When true, pins are stored per cluster layout instead of one global set. */
    var perLayoutPins: Boolean = false

    // --- Bubble motion (0.3.2): per-item position glide, carried-bubble spring, drop glide.
    /** Seconds since the previous frame, set at the top of onDraw. */
    private var frameDtSec: Float = 1f / 60f

    /** Set during a draw pass when any glide/spring is still travelling; keeps the frame loop alive. */
    private var motionAnimating: Boolean = false

    /** Animated world positions per item key (grid space and folder-view space are separate maps). */
    private val glidePosGrid = HashMap<String, FloatArray>()
    private val glidePosFolder = HashMap<String, FloatArray>()
    private val glideTmp = FloatArray(2)

    /** Carried-bubble spring state: the bubble lags the finger slightly and pops to 1.12x. */
    private var placeDrawX: Float = 0f
    private var placeDrawY: Float = 0f
    private var placeScale: Float = 1f

    /** A bubble gliding from the drop point into its slot after a placement. */
    private data class DropAnim(
        val item: GridItem,
        val fromX: Float,
        val fromY: Float,
        val fromDia: Float,
        val toKey: String,
        val shrink: Boolean,
        val startMs: Long,
        /** Swallow drops (1.5.1) accelerate into a folder and shrink to a
         *  speck instead of gliding to a stop at half size. */
        val swallow: Boolean = false,
        /** Fixed destination used when [toKey] vanishes mid-flight — folder
         *  creation replaces the target app's key with the new folder's. */
        val fixedX: Float = Float.NaN,
        val fixedY: Float = Float.NaN,
        val fixedDia: Float = 0f,
    )

    private var dropAnim: DropAnim? = null

    // Folder "gulp" pulse (1.5.1): a quick swell when a folder swallows an app.
    private var gulpKey: String? = null
    private var gulpStartMs: Long = 0L

    /** Starts the gulp pulse for the folder with [key]; [delayMs] lets a
     *  caller line the pulse up with the end of a swallow flight. */
    fun gulpFolder(key: String, delayMs: Long = 0L) {
        if (delayMs > 0L) {
            postDelayed({
                gulpKey = key
                gulpStartMs = SystemClock.uptimeMillis()
                invalidate()
            }, delayMs)
        } else {
            gulpKey = key
            gulpStartMs = SystemClock.uptimeMillis()
            invalidate()
        }
    }

    private fun gulpFactor(key: String): Float {
        if (key != gulpKey) return 1f
        val t = (SystemClock.uptimeMillis() - gulpStartMs) / 340f
        if (t >= 1f) {
            gulpKey = null
            return 1f
        }
        if (t < 0f) return 1f
        motionAnimating = true
        return 1f + 0.16f * sin(t * Math.PI.toFloat())
    }

    /** Damps [key]'s animated position toward (tx, ty); far jumps snap. Result lands in [glideTmp]. */
    private fun glideToward(map: HashMap<String, FloatArray>, key: String, tx: Float, ty: Float, snapDist: Float) {
        val g = map.getOrPut(key) { floatArrayOf(tx, ty) }
        val dist = hypot(tx - g[0], ty - g[1])
        when {
            dist > snapDist -> {
                g[0] = tx; g[1] = ty
            }
            dist > 0.4f -> {
                val f = Motion.dampFactor(frameDtSec, 0.11f)
                g[0] += (tx - g[0]) * f
                g[1] += (ty - g[1]) * f
                motionAnimating = true
            }
            else -> {
                g[0] = tx; g[1] = ty
            }
        }
        glideTmp[0] = g[0]
        glideTmp[1] = g[1]
    }

    /** Usage growth: bubbles scale with how much their app is used. */
    var usageGrowth: Boolean = true

    /** Per-item diameter multipliers from [UsageScale], keyed by GridItem.key. Set by the activity. */
    var usageFactors: Map<String, Float> = emptyMap()

    private fun growthFor(item: GridItem): Float =
        if (usageGrowth) usageFactors[item.key] ?: 1f else 1f

    /** Edit mode: bubbles can be dragged immediately (no long-press); Done pill or Back exits. */
    var editMode: Boolean = false
        private set
    private val editDoneRect = RectF()

    /** Placement undo bar: a transient pill with an Undo action, drawn over the grid. */
    private var undoLabel: String? = null
    private var undoAction: (() -> Unit)? = null
    private var undoUntilMs: Long = 0L
    private val undoRect = RectF()
    private val undoBtnRect = RectF()

    fun showPlaceUndo(label: String, onUndo: () -> Unit) {
        undoLabel = label
        undoAction = onUndo
        undoUntilMs = SystemClock.uptimeMillis() + 6000L
        postDelayed({
            if (SystemClock.uptimeMillis() >= undoUntilMs && undoLabel != null) {
                undoLabel = null
                undoAction = null
                invalidate()
            }
        }, 6100L)
        invalidate()
    }

    private val undoActive: Boolean
        get() = undoLabel != null && SystemClock.uptimeMillis() < undoUntilMs

    fun enterEditMode() {
        editMode = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    fun exitEditMode() {
        editMode = false
        invalidate()
    }

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

    /** Category folders currently hidden via the folder header (sheet count). */
    var hiddenFolderCount: Int = 0

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

    /** Per-layout view memory (1.11.0): where you left each design's
     *  pan/zoom, restored when you come back to it. The map lives in the
     *  view; MainActivity persists each save via onTopoViewSaved and
     *  reseeds it at startup (1.11.1), so it survives restarts too. */
    private data class TopoViewState(val panX: Float, val panY: Float, val zoom: Float)
    private val topoViewMemory = HashMap<ClusterTopology, TopoViewState>()
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
    /** Spoken labels parallel to [sheetRowRects], for TalkBack (1.8.0). */
    private val sheetRowLabels = ArrayList<Pair<RectF, String>>()
    private val sheetBoundsRect = RectF()
    private val topologyRowRects = ArrayList<Pair<RectF, ClusterTopology>>()
    private val previewTmp = FloatArray(2)
    private val pickerBackRect = RectF()
    private var topologyPickerOpen = false

    /** Cluster sheet starts compact (essentials only); "More Options"
     *  expands the set-and-forget rows. Session state, not a pref. */
    private var sheetExpanded = false
    private val widgetActionRects = ArrayList<Pair<RectF, String>>()
    private val widgetActionLabels = ArrayList<Pair<RectF, String>>()

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
    /** Per-segment hit rects of the bottom dock (rebuilt by drawDock). */
    private val dockSegs = Array(4) { RectF() }

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
        // Prune glide state for items that no longer exist.
        val liveKeys = HashSet<String>()
        folders.forEach { item ->
            liveKeys += item.key
            if (item is Folder) item.apps.forEach { liveKeys += it.key }
        }
        flatWithWidgets.forEach { liveKeys += it.key }
        use.forEach { liveKeys += it.key }
        glidePosGrid.keys.retainAll(liveKeys)
        glidePosFolder.keys.retainAll(liveKeys)
        rebuildGridHexes()
        activeFolder?.let { cur ->
            val updated = folders.filterIsInstance<Folder>().firstOrNull { it.group == cur.group }
            if (updated != null) {
                activeFolder = updated
                bloomAnchorIndex = activeGridItems.indexOf(updated)
                folderHexes = Hex.spiral(updated.apps.size)
            }
        }
        // Face favourites (1.10.0): the apps you pinned, in hex-slot
        // order; most-used apps fill any remaining spots.
        val pinnedFavs = use.filter { it.key in pinnedItemKeys }
        face.favorites = (pinnedFavs + use).distinctBy { it.key }.take(3)
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
        if (cardMenuOpen) {
            closeCardMenu()
            return
        }
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
            cardMenuOpen = cardMenuOpen,
            searching = searching || gridSearching || searchQuery.isNotEmpty(),
            topologyPickerOpen = topologyPickerOpen && clusterSheet.target > 0f,
            sheetOpen = clusterSheet.target > 0f,
            editMode = editMode,
            torchOffViaBack = controlIn.target > 0f && controlCenter.state.screenFlashlight,
            // A layer that is visibly open counts even if its target was already
            // reset mid-animation: Back from the Smart Stack once slipped past
            // a target-only check, returned "not consumed", and the activity
            // finished because WatchReborn is not the default home app.
            stackOrControlOpen = stackIn.target > 0f || controlIn.target > 0f ||
                stackIn.value > 0.5f || controlIn.value > 0.5f,
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
            NavLayer.CARD_MENU -> {
                closeCardMenu()
                true
            }
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
            NavLayer.EDIT_MODE -> {
                exitEditMode()
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
        markHintSeen(HINT_FACE_SWIPE)
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
        markHintSeen(HINT_FACE_SWIPE)
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
    /** Transient "placed — Undo" bar shown after a drag-to-place mutation. */
    private fun drawUndoBar(c: Canvas, w: Float, bottom: Float, alpha: Float) {
        if (!undoActive || apps.target == 0f) {
            undoRect.setEmpty()
            undoBtnRect.setEmpty()
            return
        }
        val label = undoLabel ?: return
        val listPillH = max(dp(44f), height * 0.055f)
        val pillH = dp(32f)
        var hy = bottom - dp(16f) - listPillH - dp(10f) - pillH
        if (editMode) hy -= pillH + dp(8f) // stack above the Edit-mode Done pill
        text.textSize = sp(12.5f)
        val labelW = text.measureText(label)
        text.isFakeBoldText = true
        val undoW = text.measureText("Undo")
        text.isFakeBoldText = false
        val pw = labelW + undoW + dp(48f)
        undoRect.set(w / 2 - pw / 2, hy, w / 2 + pw / 2, hy + pillH)
        undoBtnRect.set(undoRect.right - undoW - dp(30f), hy, undoRect.right - dp(2f), hy + pillH)
        fill.color = Color.argb((alpha * 238).toInt(), 30, 30, 34)
        c.drawRoundRect(undoRect, pillH / 2, pillH / 2, fill)
        stroke.strokeWidth = dp(1.2f)
        stroke.color = withAlpha(accent, (alpha * 200).toInt())
        c.drawRoundRect(undoRect, pillH / 2, pillH / 2, stroke)
        text.color = Color.argb((alpha * 255).toInt(), 245, 245, 248)
        c.drawText(label, undoRect.left + dp(15f), undoRect.centerY() + text.textSize * 0.35f, text)
        text.isFakeBoldText = true
        text.color = withAlpha(accent, (alpha * 255).toInt())
        c.drawText("Undo", undoRect.right - dp(15f) - undoW, undoRect.centerY() + text.textSize * 0.35f, text)
        text.isFakeBoldText = false
    }

    /** Edit-mode status pill with a Done affordance, parked where coach hints live. */
    private fun drawEditDone(c: Canvas, w: Float, bottom: Float, alpha: Float) {
        if (!editMode || placeItem != null || apps.target == 0f) {
            editDoneRect.setEmpty()
            return
        }
        val listPillH = max(dp(44f), height * 0.055f)
        val pillH = dp(30f)
        val hy = bottom - dp(16f) - listPillH - dp(10f) - pillH
        text.textSize = sp(12.5f)
        val s = "✥ Editing — drag any bubble"
        val tw = text.measureText(s)
        text.isFakeBoldText = true
        val doneW = text.measureText("✓ Done")
        text.isFakeBoldText = false
        val pw = tw + doneW + dp(48f)
        editDoneRect.set(w / 2 - pw / 2, hy, w / 2 + pw / 2, hy + pillH)
        fill.color = Color.argb((alpha * 235).toInt(), 30, 30, 34)
        c.drawRoundRect(editDoneRect, pillH / 2, pillH / 2, fill)
        stroke.strokeWidth = dp(1.2f)
        stroke.color = withAlpha(accent, (alpha * 220).toInt())
        c.drawRoundRect(editDoneRect, pillH / 2, pillH / 2, stroke)
        text.color = Color.argb((alpha * 255).toInt(), 245, 245, 248)
        c.drawText(s, editDoneRect.left + dp(15f), editDoneRect.centerY() + text.textSize * 0.35f, text)
        text.isFakeBoldText = true
        text.color = withAlpha(accent, (alpha * 255).toInt())
        c.drawText("✓ Done", editDoneRect.right - dp(15f) - doneW, editDoneRect.centerY() + text.textSize * 0.35f, text)
        text.isFakeBoldText = false
    }

    private fun currentCoachHint(): String? {
        // On the Face, the only undiscoverable moves are the two swipes.
        if (onFace) return HINT_FACE_SWIPE.takeIf { it !in hintsSeen }
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
        // The undo bar and the Edit-mode pill take this screen slot while active.
        val id = if (editMode || undoActive) null else currentCoachHint()
        coachHintId = id
        if (id == null) {
            coachHintRect.setEmpty()
            return
        }
        val label = when (id) {
            HINT_PINCH -> "🤏 Pinch out for an overview of everything"
            HINT_PIN -> "📌 Long-press a bubble, then drag to place it"
            HINT_CROWN -> "↕ Drag the right screen edge to zoom"
            HINT_SCRUB -> "🔤 Drag the A–Z edge to jump through the list"
            HINT_FACE_SWIPE -> "Swipe ↑ for the Stack, ↓ for Controls"
            else -> return
        }
        text.textSize = sp(12.5f)
        val tw = text.measureText(label)
        val hh = dp(30f)
        val hw = tw + dp(28f)
        // On the Face there is no dock to clear; the pill sits just above
        // the favourites row instead.
        val hy = if (id == HINT_FACE_SWIPE) bottom - 0.27f * w - hh - dp(10f)
        else bottom - dp(16f) - dp(Metrics.PHONE_PILL_H_DP) - dp(10f) - hh
        coachHintRect.set(w / 2 - hw / 2, hy, w / 2 + hw / 2, hy + hh)
        fill.color = Color.argb((alpha * 235).toInt(), 30, 34, 40)
        c.drawRoundRect(coachHintRect, hh / 2, hh / 2, fill)
        stroke.strokeWidth = dp(1.2f)
        stroke.color = withAlpha(accent, (alpha * 170).toInt())
        c.drawRoundRect(coachHintRect, hh / 2, hh / 2, stroke)
        text.color = Color.argb((alpha * 255).toInt(), 255, 255, 255)
        c.drawText(label, coachHintRect.centerX() - tw / 2, coachHintRect.centerY() + text.textSize * 0.35f, text)
    }

    /** Drag-to-place is a full-grid gesture: it must not fight the sheet, search, list or folders. */
    private fun canDragPlace(slot: Int): Boolean =
        slot >= 0 && grid.target == 1f && list.target == 0f && clusterSheet.target == 0f &&
            constellationIn.target == 0f && folderIn.target == 0f && widgetIn.target == 0f &&
            activeFolder == null && !gridSearching && !searching

    private fun cancelFolderArm() {
        folderArmRunnable?.let { removeCallbacks(it) }
        folderArmRunnable = null
        folderArmSlot = -1
        folderArmed = false
    }

    /** (Re)evaluate the folder-creation dwell for the current hover slot. */
    private fun updateFolderArm() {
        cancelFolderArm()
        val carried = placeItem as? App ?: return
        if (placeInFolder) return
        val hover = placeHoverSlot
        if (hover < 0 || hover == placeFromSlot) return
        val target = hits.firstOrNull { it.slotIndex == hover }?.item as? App ?: return
        if (target.key == carried.key) return
        folderArmSlot = hover
        val r = Runnable {
            if (placeItem === carried && placeHoverSlot == folderArmSlot && !placeInFolder) {
                folderArmed = true
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                invalidate()
            }
        }
        folderArmRunnable = r
        postDelayed(r, 650L)
    }

    private fun beginPlace(item: GridItem, slot: Int, inFolder: Boolean = false) {
        cancelFolderArm()
        placeItem = item
        placeFromSlot = slot
        placeHoverSlot = slot
        placeX = downX
        placeY = downY
        placeDrawX = downX
        placeDrawY = downY
        placeScale = 1f
        dropAnim = null
        placeMoved = false
        placeInFolder = inFolder
        placeFolder = if (inFolder) activeFolder else null
        markHintSeen(HINT_PIN)
        invalidate()
    }

    private fun finishPlace() {
        val item = placeItem ?: return
        val moved = placeMoved
        val from = placeFromSlot
        val hover = placeHoverSlot
        val inFolder = placeInFolder
        val folder = placeFolder
        val dropX = placeX
        val dropY = placeY
        val armed = folderArmed
        cancelFolderArm()
        placeItem = null
        placeHoverSlot = -1
        placeMoved = false
        placeInFolder = false
        placeFolder = null

        // Dropping a pinned bubble on the release zone unpins it.
        if (moved && !inFolder && !releasePinRect.isEmpty && releasePinRect.contains(dropX, dropY)) {
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onUnpinItem(item)
            return
        }
        // Dragging a custom-folder member onto the remove zone un-files it.
        if (moved && inFolder && folder != null && folder.isCustom && item is App &&
            !folderRemoveZone.isEmpty && folderRemoveZone.contains(dropX, dropY)) {
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onRemoveFromFolder(folder, item)
            return
        }
        when (PlaceSlots.resolveDrop(moved, from, hover)) {
            // Released without a drag in the full grid: the Context Card
            // takes over from the classic menu. Inside a folder or Edit
            // Layout the classic menu still opens (arranging flows keep it).
            PlaceSlots.DropAction.MENU ->
                if (!inFolder && !editMode) {
                    cardFolder = null
                    val origin = hits.firstOrNull { it.slotIndex == from && it.item.key == item.key }
                        ?: hits.firstOrNull { it.slotIndex == from }
                    openCardMenu(item, from, origin?.x ?: dropX, origin?.y ?: dropY, origin?.r ?: dp(31f))
                } else if (inFolder && folder != null && folder.isCustom && item is App) {
                    // Inside a custom folder the card takes over too, with a
                    // "Remove from …" row (the classic menu buried it).
                    cardFolder = folder
                    val origin = hits.firstOrNull { it.slotIndex == from && it.item.key == item.key }
                        ?: hits.firstOrNull { it.slotIndex == from }
                    openCardMenu(item, from, origin?.x ?: dropX, origin?.y ?: dropY, origin?.r ?: dp(31f))
                } else onLongPressItem(item, from)
            PlaceSlots.DropAction.PIN -> {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                // Dropping an app onto a folder bubble files it into that folder.
                val hoverItem = hits.firstOrNull { it.slotIndex == hover }?.item
                val carriedDia = (hits.firstOrNull { it.slotIndex == from }?.r ?: dp(31f)) * 2f * placeScale
                if (!inFolder && item is App && armed && hoverItem is App && hoverItem.key != item.key) {
                    // Dwell-armed drop on another app: create a folder (or
                    // join the target's folder — MainActivity decides). The
                    // target app becomes the folder, so its key vanishes in
                    // the rebuild — the swallow flies to a fixed endpoint.
                    val hoverHit = hits.firstOrNull { it.slotIndex == hover }
                    dropAnim = DropAnim(
                        item, placeDrawX, placeDrawY, carriedDia, hoverItem.key, true,
                        SystemClock.uptimeMillis(), swallow = true,
                        fixedX = hoverHit?.x ?: dropX, fixedY = hoverHit?.y ?: dropY,
                        fixedDia = (hoverHit?.r ?: dp(31f)) * 2f,
                    )
                    onCreateFolder(item, hoverItem)
                } else if (!inFolder && item is App && hoverItem is Folder) {
                    // Swallowed by the folder bubble: accelerate in, shrink
                    // to a speck, and the folder gulps on arrival.
                    dropAnim = DropAnim(item, placeDrawX, placeDrawY, carriedDia, hoverItem.key, true, SystemClock.uptimeMillis(), swallow = true)
                    onFileIntoFolder(item, hoverItem, hover)
                } else if (inFolder && folder != null) {
                    // Folder members glide via their own position springs.
                    onReorderFolderMember(folder, from, hover)
                } else {
                    // The overlay owns this bubble's flight; the grid copy snaps
                    // to the new slot underneath it (hidden until arrival).
                    glidePosGrid.remove(item.key)
                    dropAnim = DropAnim(item, placeDrawX, placeDrawY, carriedDia, item.key, false, SystemClock.uptimeMillis())
                    onPlaceItem(item, hover)
                }
            }
            PlaceSlots.DropAction.NO_CHANGE -> {}
        }
    }

    /** The rows the Context Card offers for [item], in iOS order: primary
     * actions first, Edit Home Screen near the end, Remove App last. */
    private fun cardKindsFor(item: GridItem): List<Int> {
        // A member of a custom folder gets its own short card: launch, info,
        // the classic menu, and the way out of the folder.
        if (cardFolder != null && item is App) {
            return listOf(CARD_LAUNCH, CARD_INFO, CARD_MORE, CARD_REMOVE_FROM_FOLDER)
        }
        val kinds = ArrayList<Int>()
        if (item.key in pinnedItemKeys) kinds += CARD_UNPIN else if (cardSlot >= 0) kinds += CARD_PIN
        when (item) {
            is App -> kinds += CARD_INFO
            is Folder -> {
                kinds += CARD_OPEN
                if (item.isCustom) kinds += CARD_RENAME
            }
            is WidgetBubble -> kinds += CARD_OPEN
        }
        kinds += CARD_MORE
        kinds += CARD_EDIT
        if (item is App && item.key in uninstallableKeys) kinds += CARD_UNINSTALL
        if (item is Folder && item.isCustom) kinds += CARD_DELETE_FOLDER
        return kinds
    }

    private fun cardLabel(kind: Int, item: GridItem): String = when (kind) {
        CARD_PIN -> "Pin to Hex #" + (cardSlot + 1)
        CARD_UNPIN -> "Unpin Hex Slot"
        CARD_INFO -> "App Info"
        CARD_OPEN -> if (item is Folder) "Open Folder" else "Open Pod"
        CARD_MORE -> "More Options"
        CARD_EDIT -> "Edit Home Screen"
        CARD_UNINSTALL -> "Remove App"
        CARD_RENAME -> "Rename Folder"
        CARD_DELETE_FOLDER -> "Delete Folder"
        CARD_LAUNCH -> "Open App"
        CARD_REMOVE_FROM_FOLDER -> "Remove from ${cardFolder?.label ?: "Folder"}"
        else -> ""
    }

    /** Row glyph, drawn right-aligned like the iOS menu's icons. */
    private fun cardGlyph(kind: Int, item: GridItem): String = when (kind) {
        CARD_PIN -> "\uD83D\uDCCC"
        CARD_UNPIN -> "\u2715"
        CARD_INFO -> "\u2139"
        CARD_OPEN -> if (item is Folder) "\uD83D\uDCC1" else "\u26A1"
        CARD_MORE -> "\u22EF"
        CARD_EDIT -> "\u2725"
        CARD_UNINSTALL -> "\u2296"
        CARD_RENAME -> "\u270E"
        CARD_DELETE_FOLDER -> "\uD83D\uDDD1"
        CARD_LAUNCH -> "\u25B6"
        CARD_REMOVE_FROM_FOLDER -> "\u21A9"
        else -> ""
    }

    /** Runs one Context Card row. The card is already closing. */
    private fun runCardAction(kind: Int, item: GridItem, slot: Int) {
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        when (kind) {
            CARD_PIN -> if (slot >= 0) onPlaceItem(item, slot)
            CARD_UNPIN -> onUnpinItem(item)
            CARD_INFO -> if (item is App) onQuickInfo(item)
            CARD_UNINSTALL -> if (item is App) onQuickUninstall(item)
            CARD_OPEN -> when (item) {
                is Folder -> openFolder(item)
                is WidgetBubble -> openWidgetPod(item)
                else -> {}
            }
            CARD_MORE -> onLongPressItem(item, slot)
            CARD_EDIT -> enterEditMode()
            CARD_RENAME -> if (item is Folder) onRenameFolder(item)
            CARD_DELETE_FOLDER -> if (item is Folder) onDeleteFolder(item)
            CARD_LAUNCH -> if (item is App) onLaunch(
                item,
                RectF(cardOriginX - cardOriginR, cardOriginY - cardOriginR, cardOriginX + cardOriginR, cardOriginY + cardOriginR),
            )
            CARD_REMOVE_FROM_FOLDER -> {
                val f = cardFolder
                if (f != null && item is App) onRemoveFromFolder(f, item)
            }
        }
    }

    /**
     * Context Card overlay: a light dim (the grid stays readable behind,
     * like the iOS menu's blurred backdrop), the pressed bubble lifted in
     * place with an accent ring, and one rounded card of action rows
     * anchored next to the bubble — below it when there's room, above when
     * there isn't, always clamped inside the screen. Row rects are rebuilt
     * every frame into [cardRows] for tap() to hit-test.
     */
    private fun drawCardMenu(c: Canvas, w: Float, h: Float, cal: Calendar, t: Float) {
        val item = cardItem
        if (item == null) {
            cardRows.clear()
            cardRect.setEmpty()
            return
        }
        if (t <= 0.001f && cardMenu.target == 0f) {
            cardItem = null
            cardRows.clear()
            cardRect.setEmpty()
            return
        }
        val a = t.coerceIn(0f, 1f)
        fill.color = Color.argb((a * 118).toInt(), 4, 5, 8)
        c.drawRect(0f, 0f, w, h, fill)

        val now = SystemClock.uptimeMillis()
        val popT = if (cardMenu.target > 0f)
            ((now - cardOpenedMs) / 220f).coerceIn(0f, 1f)
        else 1f
        if (popT < 1f) motionAnimating = true

        // The pressed bubble, lifted in place over the dim.
        val liftDia = cardOriginR * 2f * Motion.easeOutBack(popT).coerceAtLeast(0f) * (0.55f + 0.45f * a)
        if (liftDia > 2f) {
            fill.color = withAlpha(accent, (a * 34).toInt())
            c.drawCircle(cardOriginX, cardOriginY, liftDia * 0.72f, fill)
            stroke.strokeWidth = dp(1.6f)
            stroke.color = withAlpha(accent, (a * 180).toInt())
            c.drawCircle(cardOriginX, cardOriginY, cardOriginR * 1.14f, stroke)
            drawGridItem(c, item, cardOriginX, cardOriginY, liftDia, a, cal, jiggle = false)
        }

        // Card geometry: fixed width, one row per action.
        val kinds = cardKindsFor(item)
        val rowH = dp(52f)
        val cardW = minOf(w - dp(32f), dp(258f))
        val cardH = rowH * kinds.size
        val gap = dp(14f)
        var cardTop = if (cardOriginY + cardOriginR + gap + cardH <= h - bottomInset - dp(16f))
            cardOriginY + cardOriginR + gap
        else
            cardOriginY - cardOriginR - gap - cardH
        val maxTop = (h - bottomInset - dp(12f) - cardH).coerceAtLeast(topInset + dp(12f))
        cardTop = cardTop.coerceIn(topInset + dp(12f), maxTop)
        var cardLeft = if (cardOriginX <= w / 2f) cardOriginX - cardOriginR else cardOriginX + cardOriginR - cardW
        cardLeft = cardLeft.coerceIn(dp(16f), w - dp(16f) - cardW)
        cardRect.set(cardLeft, cardTop, cardLeft + cardW, cardTop + cardH)

        // Card body.
        fill.color = Color.argb((a * 246).toInt(), 34, 36, 44)
        c.drawRoundRect(cardRect, dp(24f), dp(24f), fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = Color.argb((a * 70).toInt(), 255, 255, 255)
        c.drawRoundRect(cardRect, dp(24f), dp(24f), stroke)

        // Rows: label left, glyph right (iOS style), hairline dividers,
        // staggered fade-in; Remove App is the red last row.
        cardRows.clear()
        for ((i, kind) in kinds.withIndex()) {
            val pt = if (cardMenu.target > 0f)
                ((now - cardOpenedMs - 40L - i * 35L) / 150f).coerceIn(0f, 1f)
            else 1f
            if (pt < 1f) motionAnimating = true
            val ra = a * Motion.easeOutCubic(pt)
            val rowTop = cardTop + i * rowH
            val rowRect = RectF(cardLeft, rowTop, cardLeft + cardW, rowTop + rowH)
            cardRows += CardRow(kind, rowRect)
            if (i > 0) {
                stroke.strokeWidth = dp(0.8f)
                stroke.color = Color.argb((ra * 38).toInt(), 255, 255, 255)
                c.drawLine(cardLeft + dp(16f), rowTop, cardLeft + cardW - dp(16f), rowTop, stroke)
            }
            val danger = kind == CARD_UNINSTALL || kind == CARD_DELETE_FOLDER
            val ink = if (danger) Color.argb((ra * 255).toInt(), 255, 138, 138)
                else Color.argb((ra * 255).toInt(), 245, 245, 248)
            text.textSize = sp(15f)
            text.color = ink
            val lab = cardLabel(kind, item)
            c.drawText(lab, cardLeft + dp(18f), rowRect.centerY() + text.textSize * 0.36f, text)
            val g = cardGlyph(kind, item)
            text.color = if (danger) ink else Color.argb((ra * 210).toInt(), 235, 235, 240)
            c.drawText(g, cardLeft + cardW - dp(18f) - text.measureText(g), rowRect.centerY() + text.textSize * 0.36f, text)
        }
    }

    /** The glide that flies a dropped bubble from the release point into
     *  its slot — 240ms ease-out for placements; swallow drops (into or
     *  creating a folder) run 300ms, accelerate in and shrink to a speck. */
    private fun drawDropAnim(c: Canvas, cal: Calendar, alpha: Float, bubbleX: FloatArray, bubbleY: FloatArray, bubbleDia: FloatArray) {
        val da = dropAnim ?: return
        val items = activeGridItems
        val idx = items.indexOfFirst { it.key == da.toKey }
        val hasLive = idx >= 0 && idx < bubbleDia.size && bubbleDia[idx] > 2f
        if (!hasLive && da.fixedDia <= 0f) {
            dropAnim = null
            return
        }
        val tx = if (hasLive) bubbleX[idx] else da.fixedX
        val ty = if (hasLive) bubbleY[idx] else da.fixedY
        val td = if (hasLive) bubbleDia[idx] else da.fixedDia
        val dur = if (da.swallow) 300f else 240f
        val p = ((SystemClock.uptimeMillis() - da.startMs) / dur).coerceIn(0f, 1f)
        if (da.swallow) {
            val e = p * p // accelerating suck-in
            val x = Motion.lerp(da.fromX, tx, e)
            val y = Motion.lerp(da.fromY, ty, e)
            val d = Motion.lerp(da.fromDia, td * 0.10f, e)
            drawGridItem(c, da.item, x, y, d, alpha, cal, jiggle = false)
        } else {
            val e = Motion.easeOutCubic(p)
            val endDia = td * if (da.shrink) 0.45f else 1f
            val x = Motion.lerp(da.fromX, tx, e)
            val y = Motion.lerp(da.fromY, ty, e)
            val d = Motion.lerp(da.fromDia, endDia, e)
            drawGridItem(c, da.item, x, y, d, alpha, cal, jiggle = false)
        }
        if (p >= 1f) {
            dropAnim = null
            // A swallowed app lands: the folder gulps. (Folder creation
            // replaces the target key mid-flight, so its gulp is triggered
            // by MainActivity once the new folder exists.)
            if (da.swallow && hasLive) gulpFolder(da.toKey)
        } else motionAnimating = true
    }

    /** Advances the carried-bubble spring one frame (finger trail + pickup pop). */
    private fun advancePlaceSpring() {
        val pf = Motion.dampFactor(frameDtSec, 0.055f)
        placeDrawX += (placeX - placeDrawX) * pf
        placeDrawY += (placeY - placeDrawY) * pf
        val sf = Motion.dampFactor(frameDtSec, 0.09f)
        placeScale += (1.12f - placeScale) * sf
        if (hypot(placeX - placeDrawX, placeY - placeDrawY) > 0.6f || abs(1.12f - placeScale) > 0.005f) {
            motionAnimating = true
        }
    }

    private fun drawPlaceOverlay(c: Canvas, cal: Calendar, alpha: Float) {
        val item = placeItem ?: return
        advancePlaceSpring()
        // Release zone for unpinning, offered while carrying a pinned bubble.
        // (Apps get their unpin as a Context Card row instead.)
        if (placeMoved && item.key in pinnedItemKeys && item !is App) {
            val zw = dp(210f)
            val zh = dp(40f)
            releasePinRect.set(width / 2f - zw / 2, topInset + dp(64f), width / 2f + zw / 2, topInset + dp(64f) + zh)
            val hot = releasePinRect.contains(placeX, placeY)
            fill.color = if (hot) Color.argb((alpha * 240).toInt(), 120, 34, 40) else Color.argb((alpha * 210).toInt(), 44, 40, 44)
            c.drawRoundRect(releasePinRect, zh / 2, zh / 2, fill)
            stroke.strokeWidth = dp(1.2f)
            stroke.color = Color.argb((alpha * 220).toInt(), 255, 110, 110)
            c.drawRoundRect(releasePinRect, zh / 2, zh / 2, stroke)
            text.textSize = sp(12.5f)
            val s = "✕ Drop here to unpin"
            text.color = Color.argb((alpha * 255).toInt(), 255, 190, 190)
            c.drawText(s, releasePinRect.centerX() - text.measureText(s) / 2, releasePinRect.centerY() + text.textSize * 0.35f, text)
        } else {
            releasePinRect.setEmpty()
        }
        // Remove zone while carrying a custom-folder member: drop here to
        // take the app back out of its folder.
        if (placeMoved && placeInFolder && placeFolder?.isCustom == true && item is App) {
            val zw = dp(210f)
            val zh = dp(40f)
            folderRemoveZone.set(width / 2f - zw / 2, topInset + dp(64f), width / 2f + zw / 2, topInset + dp(64f) + zh)
            val hot = folderRemoveZone.contains(placeX, placeY)
            fill.color = if (hot) Color.argb((alpha * 240).toInt(), 120, 34, 40) else Color.argb((alpha * 210).toInt(), 44, 40, 44)
            c.drawRoundRect(folderRemoveZone, zh / 2, zh / 2, fill)
            stroke.strokeWidth = dp(1.2f)
            stroke.color = Color.argb((alpha * 220).toInt(), 255, 110, 110)
            c.drawRoundRect(folderRemoveZone, zh / 2, zh / 2, stroke)
            text.textSize = sp(12.5f)
            val s2 = "✕ Remove from folder"
            text.color = Color.argb((alpha * 255).toInt(), 255, 190, 190)
            c.drawText(s2, folderRemoveZone.centerX() - text.measureText(s2) / 2, folderRemoveZone.centerY() + text.textSize * 0.35f, text)
        } else {
            folderRemoveZone.setEmpty()
        }
        // No menu while carrying (the Context Card replaced the bloom): a hold is
        // purely a pickup for drag-to-place now.
        val origin = hits.firstOrNull { it.slotIndex == placeFromSlot }
        val hover = hits.firstOrNull { it.slotIndex == placeHoverSlot }
        // The origin slot reads as "picked up": dimmed with an accent ring.
        if (origin != null) {
            fill.color = Color.argb((alpha * 150).toInt(), 0, 0, 0)
            c.drawCircle(origin.x, origin.y, origin.r, fill)
            stroke.strokeWidth = dp(1.5f)
            stroke.color = withAlpha(accent, (alpha * 160).toInt())
            c.drawCircle(origin.x, origin.y, origin.r, stroke)
        }
        // The slot that would receive the drop gets a bold ring.
        if (hover != null && placeHoverSlot != placeFromSlot) {
            stroke.strokeWidth = dp(3f)
            stroke.color = withAlpha(accent, (alpha * 255).toInt())
            c.drawCircle(hover.x, hover.y, hover.r + dp(3f), stroke)
        }
        // Armed folder creation: the target bubble gets a filled halo and a
        // caption, so what the release will do is unmistakable.
        if (folderArmed && hover != null && placeHoverSlot != placeFromSlot) {
            fill.color = withAlpha(accent, (alpha * 70).toInt())
            c.drawCircle(hover.x, hover.y, hover.r + dp(7f), fill)
            stroke.strokeWidth = dp(3.5f)
            stroke.color = withAlpha(accent, (alpha * 255).toInt())
            c.drawCircle(hover.x, hover.y, hover.r + dp(7f), stroke)
            val label = "Release to create folder"
            text.textSize = dp(13f)
            text.color = withAlpha(Color.WHITE, (alpha * 255).toInt())
            val tw = text.measureText(label)
            val capX = hover.x.coerceIn(tw / 2 + dp(10f), width - tw / 2 - dp(10f))
            val capY = (hover.y - hover.r - dp(24f)).coerceAtLeast(dp(96f))
            c.drawText(label, capX - tw / 2, capY, text)
        }
        // The carried bubble itself, slightly enlarged, under the finger.
        val d = (origin?.r ?: dp(31f)) * 2f * placeScale
        drawGridItem(c, item, placeDrawX, placeDrawY, d, alpha, cal, jiggle = false)
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
        // Save where the outgoing design was left; restore the incoming
        // design's remembered spot, or centre it on a first visit (each
        // design's world has different extents, so carrying one pan/zoom
        // across designs landed you in empty space).
        val outState = TopoViewState(panX.target, panY.target, userZoom.target)
        topoViewMemory[clusterTopology] = outState
        onTopoViewSaved(clusterTopology, outState.panX, outState.panY, outState.zoom)
        clusterTopology = topo
        overview.target = 0f
        val remembered = topoViewMemory[topo]
        if (remembered != null) {
            panX.target = remembered.panX
            panY.target = remembered.panY
            userZoom.target = remembered.zoom
        } else {
            panX.target = 0f
            panY.target = 0f
            userZoom.target = if (topo == ClusterTopology.NEBULA) 0.72f else 1f
        }
        topologyPickerOpen = false
        onTopologySelected(topo)
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        invalidate()
    }

    /** Seeds one design's remembered pan/zoom from the persisted copy
     *  (1.11.1); if it's the design already showing, apply it now. */
    fun seedTopoViewMemory(topo: ClusterTopology, x: Float, y: Float, zoom: Float) {
        topoViewMemory[topo] = TopoViewState(x, y, zoom)
        if (topo == clusterTopology) {
            panX.snap(x)
            panY.snap(y)
            userZoom.snap(zoom)
        }
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
                // Rings of 6, 10, 14, 18, … bubbles (see RingMath) instead of
                // dumping every overflow app onto one outer ring — at ~180 apps
                // that ring collapsed into an overlapping caterpillar. Outer
                // rings sit further out and are reached with the existing pan;
                // alternate rings counter-rotate with decaying speed, as before.
                val loc = RingMath.locate(index, 6, 4)
                val cnt = min(loc.ringSize, (total - loc.startIndex).coerceAtLeast(1))
                val speed = (if (loc.ring % 2 == 0) 1f else -1f) *
                    Math.pow(0.72, loc.ring.toDouble()).toFloat()
                val ang = loc.slot * (2.0 * PI / cnt) - PI / 2.0 + orbitalAngle * speed
                val r = pitch * (1.06f + loc.ring * 0.92f)
                out[0] = (cos(ang) * r).toFloat()
                out[1] = (sin(ang) * r).toFloat()
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
                // Start a few rows above center so the opening band fills the
                // upper screen instead of sitting in the lower half with the
                // top ~40% empty (the ribbon only grows downward from here).
                out[1] = (row - 3.2f) * (pitch * 0.90f)
            }
            ClusterTopology.FIBONACCI -> {
                if (index == 0) {
                    out[0] = 0f; out[1] = 0f
                    return
                }
                val goldenAngle = 2.3999632f // 137.50776 degrees in radians
                val ang = index * goldenAngle + orbitalAngle * 0.6f
                // 0.78 (was 0.68): the first spiral step used to land close
                // enough that a usage-grown bubble swallowed the center hub.
                val r = pitch * 0.78f * sqrt(index.toFloat())
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
                    // The outer ring is a carousel: only BEZEL_OUTER_CAP bubbles
                    // ride it at once, and spinning the bezel scrolls the window
                    // through the rest of the catalog (clamped at both ends).
                    // All ~170 overflow apps used to share this one ring and
                    // overlap into a solid band around the screen.
                    val outerTotal = (total - 11).coerceAtLeast(1)
                    val slot = RingMath.carouselSlot(index - 11, outerTotal, BEZEL_OUTER_CAP, orbitalAngle * 0.65f)
                    if (slot == null) {
                        // Scrolled out of the window: park far off-screen (the
                        // fisheye shrinks it to nothing; it takes no hits).
                        out[0] = pitch * 40f
                        out[1] = pitch * 40f
                    } else {
                        val ang = slot * (2.0 * PI / BEZEL_OUTER_CAP) - PI / 2.0
                        val r = pitch * 2.40f
                        out[0] = (cos(ang) * r).toFloat()
                        out[1] = (sin(ang) * r).toFloat()
                    }
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
                // Start higher so the opening rungs fill the upper screen too
                // (the ribbon only grows downward; it used to sit in the
                // lower two-thirds at rest).
                out[1] = (rung - 4.5f) * (pitch * 0.72f)
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
                // 🧲 Ergonomic One-Handed Thumb Arc Dock radiating upward from
                // bottom-center. The home bubble sits AT the pivot (bottom
                // center, under the thumb) — it used to float at the arcs'
                // midpoint where the first arc's top bubble landed on it.
                val pivotY = pitch * 2.30f
                if (index == 0) {
                    out[0] = 0f
                    out[1] = pivotY
                    return
                }
                // Arcs of 4, 6, 8, 10, … bubbles (see RingMath) instead of one
                // giant fallback arc for every overflow app — that arc rendered
                // as a solid caterpillar band across the top of the screen.
                // Higher arcs are reached by dragging the dock up (pan).
                val loc = RingMath.locate(index, 4, 2)
                val cnt = min(loc.ringSize, (total - loc.startIndex).coerceAtLeast(1))
                val slot = loc.slot.coerceIn(0, (cnt - 1).coerceAtLeast(0))
                val radius = pitch * (1.28f + loc.ring * 0.88f)
                val span = (0.72f + loc.ring * 0.08f).coerceAtMost(1.05f) * PI.toFloat()
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
            var maxSeedR = 0f
            for (i in 0 until count) {
                if (i in gridHexes.indices) {
                    pointyCenter(gridHexes[i], pitch * 0.88f, anchorTmp)
                    poolPosX[i] = anchorTmp[0]
                    poolPosY[i] = anchorTmp[1]
                    val r = hypot(anchorTmp[0], anchorTmp[1])
                    if (r > maxSeedR) maxSeedR = r
                }
            }
            // The hex seeds reach ~3x the tank radius at real catalog sizes;
            // scale them inside the tank so the sim starts contained instead
            // of slamming every outer bubble onto the rim (PoolMath, 1.4.0).
            val ss = PoolMath.seedScale(maxSeedR, count, pitch)
            if (ss < 1f) {
                for (i in 0 until count) {
                    poolPosX[i] *= ss
                    poolPosY[i] *= ss
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
            val tankR = PoolMath.tankRadius(pitch)
            val bubbleR = PoolMath.bubbleRadius(count, pitch)
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

            // Final containment pass (1.4.0): the collision relaxation runs
            // AFTER the integration wall clamp, so at high density it used to
            // end every frame with bubbles pushed outside the wall — the tank
            // never actually contained anything. Re-clamp now that collisions
            // have settled, so the wall is real.
            for (i in 1 until count) {
                if (i == grabbedPoolIndex && dragging) continue
                val distFromCenter = hypot(poolPosX[i], poolPosY[i])
                val maxCenterDist = tankR - bubbleR
                if (distFromCenter > maxCenterDist && distFromCenter > 1e-3f) {
                    poolPosX[i] = poolPosX[i] / distFromCenter * maxCenterDist
                    poolPosY[i] = poolPosY[i] / distFromCenter * maxCenterDist
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

    /** The custom folder open in Folder view right now, if any (1.5.0). */
    val openCustomFolder: Folder? get() = activeFolder?.takeIf { it.isCustom }

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

    fun closeFolder() {
        folderRenameRect.setEmpty()
        folderDeleteRect.setEmpty()
        folderHideRect.setEmpty()
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
        frameDtSec = dt
        motionAnimating = false
        frameBadgeVisible = false

        val springs = arrayOf(apps, grid, overview, userZoom, constellationZoom, folderIn, widgetIn, clusterSheet, constellationIn, list, stackIn, controlIn, panX, panY, folderPanX, folderPanY, constellationPanX, constellationPanY, stackScroll, cardMenu)
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

        // The Face's coach hint (swipe gestures) draws unscaled, only while
        // the Face is fully in front.
        if (apps.value == 0f && stackIn.value == 0f && controlIn.value == 0f) {
            drawCoachHint(c, w, bottom, faceAlpha)
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
            drawDock(c, w, bottom, a)
            drawCoachHint(c, w, bottom, a)
            drawUndoBar(c, w, bottom, a)
            drawEditDone(c, w, bottom, a)
            drawCrownTrack(c, w, h, top, bottom, a)
            if (clusterSheet.value > 0.001f) drawClusterSheet(c, w, h, top, bottom, clusterSheet.value)
            if (cardItem != null) drawCardMenu(c, w, h, cal, cardMenu.value)
        }

        val animating = springs.any { !it.settled } || abs(listVelocity) > 1f || dragging || physicsAnimating || motionAnimating
        if (animating) postInvalidateOnAnimation()
        else {
            lastFrame = 0L
            // The fast pulse loop only earns its battery while a badged
            // bubble is actually on screen (1.8.0); an unread notification
            // on an off-screen bubble falls through to the 1s tick.
            val hasPulseOrTimer = a > 0f && (timerRunning || frameBadgeVisible)
            val hasLiveGridBubbles = a > 0f && (honeycombHome || liveWidgets)
            if (hasPulseOrTimer) {
                postInvalidateDelayed(75L)
            } else if (faceAlpha > 0f || stackIn.value > 0f || controlIn.value > 0f || hasLiveGridBubbles) {
                postInvalidateDelayed(1000L - System.currentTimeMillis() % 1000L)
            }
        }
        a11y?.contentChanged()
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
            val d = (if (i == 0) Metrics.FOCUS_CENTER_D else Metrics.FOCUS_RING_D) * u * zoom * growthFor(items[i])
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

            // Position glide: when an item's slot changes (placement, filing, a
            // layout switch), its world position springs to the new slot instead
            // of teleporting. Pan/zoom/fisheye still apply on top. Liquid Pool
            // has its own simulated motion and opts out.
            if (!isPool) {
                glideToward(glidePosGrid, item.key, worldX, worldY, basePitch * 4.5f)
                worldX = glideTmp[0]
                worldY = glideTmp[1]
            }

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
            // The Dock pans vertically (its higher arcs reach up off-screen);
            // it is the one arc layout whose pan used to be pinned to zero.
            val py = if (isBezel || isPool) 0f else panY.value * (1f - ov)
            val dx = (worldX + px) / u
            val dy = (worldY + py) / u
            val d = hypot(dx / sx, dy / sy)
            bubbleDist[i] = d

            if (isBezel) {
                // In Chrono Bezel mode, the active item is the one closest to the 12 o'clock launch notch!
                // Only the inner ring competes for the notch: outer-carousel items
                // parked off-screen (or passing the top of their larger ring)
                // must never steal the highlight from the ring that owns it.
                if (i in 1..10) {
                    val notchDist = hypot(worldX, worldY + basePitch * 1.56f)
                    if (notchDist < nearestD) { nearestD = notchDist; nearest = item }
                }
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
                    // Rendered diameter tracks the fitted physics radius
                    // (PoolMath) so what you see is what collides: at a full
                    // catalog the bubbles shrink to fit the tank (1.4.0).
                    val fitK = 2f * PoolMath.bubbleRadius(items.size, Metrics.GRID_PITCH * u) /
                        (Metrics.GRID_CENTER_D * u)
                    (if (i == 0) 1.08f else 1f) * fitK * Fisheye.size(d * 0.65f)
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
            // In the pool, Usage Growth is damped (1.4.0): at full size the
            // most-used bubbles render ~2x their collision radius and bury
            // the clock hub at the centre of the tank.
            val growth = if (isPool) 1f + (growthFor(item) - 1f) * 0.35f else growthFor(item)
            bubbleDia[i] = Metrics.GRID_CENTER_D * u * size * effZoom * scaleMod * growth * gulpFactor(item.key)
        }

        val itemAlpha = alpha * (1f - 0.68f * bVal) * (1f - cVal)
        if (itemAlpha > 0.01f) {
            val aInt = (itemAlpha * 255).toInt().coerceIn(0, 255)

            // Topology-specific backdrop matrices, orbital rings, river tracks, or chrono bezel HUD
            when (clusterTopology) {
                ClusterTopology.ORBITALS -> {
                    val sunX = if (items.isNotEmpty() && bubbleDia[0] > 0f) bubbleX[0] else cx + panX.value * (1f - ov) * effZoom
                    val sunY = if (items.isNotEmpty() && bubbleDia[0] > 0f) bubbleY[0] else cy + panY.value * (1f - ov) * effZoom
                    // One guide circle per ring the catalog actually fills.
                    val ringTotal = RingMath.ringCount(items.size, 6, 4)
                    for (rIdx in 0 until ringTotal) {
                        val rad = basePitch * (1.06f + rIdx * 0.92f) * effZoom
                        stroke.strokeWidth = dp(if (rIdx == 0) 1.8f else 1.3f)
                        stroke.color = withAlpha(accent, (aInt * (0.36f - rIdx * 0.07f).coerceAtLeast(0.08f)).toInt())
                        c.drawCircle(sunX, sunY, rad, stroke)
                        // Astrolabe degree tick marks around each orbital ring
                        stroke.strokeWidth = dp(1.2f)
                        val tickSpeed = (if (rIdx % 2 == 0) 1f else -1f) * Math.pow(0.72, rIdx.toDouble()).toFloat()
                        for (t in 0 until 24) {
                            val ang = t * (PI / 12.0) + orbitalAngle * tickSpeed
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
                    // 🧲 Draw concentric thumb-reach arc guides radiating from the
                    // bottom-center pivot; the guides follow the vertical pan so
                    // they stay under their bubbles.
                    val pivotY = cy + (basePitch * 2.30f + panY.value * (1f - ov)) * effZoom
                    val arcTotal = RingMath.ringCount(items.size, 4, 2)
                    for (aIdx in 0 until arcTotal) {
                        val rArc = basePitch * (1.28f + aIdx * 0.88f) * effZoom
                        arcRect.set(cx - rArc, pivotY - rArc, cx + rArc, pivotY + rArc)
                        stroke.strokeWidth = dp(if (aIdx == 0) 1.8f else 1.2f)
                        stroke.color = withAlpha(accent, (aInt * (0.34f - aIdx * 0.06f).coerceAtLeast(0.07f)).toInt())
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
            // For FIBONACCI, the center hub draws last so a grown neighbour
            // can't bury the clock at the middle of the spiral.
            // Zero-G Billiards: draw the tank itself (1.4.0) — a softly
            // filled disc with an accent rim at exactly the physics wall
            // radius, so the containment reads as a tank instead of the
            // bubbles scattering across the whole screen.
            if (isPool) {
                val tankScreenR = PoolMath.tankRadius(Metrics.GRID_PITCH * u) * effZoom
                fill.color = withAlpha(accent, (aInt * 0.05f).toInt())
                c.drawCircle(cx, cy, tankScreenR, fill)
                stroke.strokeWidth = dp(2.2f)
                stroke.color = withAlpha(accent, (aInt * 0.55f).toInt())
                c.drawCircle(cx, cy, tankScreenR, stroke)
                stroke.strokeWidth = dp(1f)
                stroke.color = withAlpha(accent, (aInt * 0.20f).toInt())
                c.drawCircle(cx, cy, tankScreenR * 0.965f, stroke)
            }

            val drawOrder = when {
                isHelix -> items.indices.sortedBy { dnaHelixDepth(it) }
                clusterTopology == ClusterTopology.FIBONACCI -> items.indices.sortedBy { it == 0 }
                else -> items.indices.toList()
            }
            for (i in drawOrder) {
                val dia = bubbleDia[i]
                if (dia <= 2f) continue
                val item = items[i]
                // While a drop glide is flying this bubble to its slot, the
                // overlay draws it; the grid copy stays hidden.
                if (dropAnim?.item?.key == item.key) continue
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
                    val rawLbl = "${item.label.substringBefore(' ')} · ${item.apps.size}"
                    val lbl = TextUtils.ellipsize(rawLbl, text, dia * 1.55f, TextUtils.TruncateAt.END).toString()
                    val lw = text.measureText(lbl)
                    val lRect = RectF(x - lw / 2 - dp(5f), y + dia * 0.46f, x + lw / 2 + dp(5f), y + dia * 0.46f + dp(14f))
                    // Keep the whole label pill on-screen (edge folders used to
                    // clip theirs: "opping · 11", "Socia…").
                    val shiftX = when {
                        lRect.left < dp(6f) -> dp(6f) - lRect.left
                        lRect.right > w - dp(6f) -> (w - dp(6f)) - lRect.right
                        else -> 0f
                    }
                    if (shiftX != 0f) lRect.offset(shiftX, 0f)
                    fill.color = Color.argb((ovInt * 0.90f).toInt(), 18, 20, 26)
                    c.drawRoundRect(lRect, dp(7f), dp(7f), fill)
                    val tint = item.tint
                    stroke.strokeWidth = dp(1f)
                    stroke.color = withAlpha(tint, (ovInt * 0.7f).toInt())
                    c.drawRoundRect(lRect, dp(7f), dp(7f), stroke)
                    text.color = Color.argb(ovInt, 255, 255, 255)
                    c.drawText(lbl, x + shiftX - lw / 2, lRect.centerY() + text.textSize * 0.35f, text)
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

        if (placeItem != null) drawPlaceOverlay(c, cal, alpha)
        drawDropAnim(c, cal, alpha, bubbleX, bubbleY, bubbleDia)
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
        // When the catalog overflows the outer carousel ring, the HUD names the
        // visible window so spinning reads as browsing, not losing apps.
        val totalItems = activeGridItems.size
        val outerTotal = totalItems - 11
        val windowText = if (outerTotal > BEZEL_OUTER_CAP) {
            val scroll = RingMath.carouselScroll(outerTotal, BEZEL_OUTER_CAP, orbitalAngle * 0.65f)
            val first = 12 + scroll.toInt()
            val last = min(totalItems, first + BEZEL_OUTER_CAP - 1)
            "RING $first–$last OF $totalItems"
        } else {
            null
        }
        val sub = if (timerRunning) {
            val remSec = ((remMs + 999L) / 1000L).toInt()
            "⏱ %d:%02d".format(remSec / 60, remSec % 60)
        } else if (info.battery >= 0) {
            if (windowText != null) "🔋 ${info.battery}% • $windowText" else "🔋 ${info.battery}% • SPIN BEZEL"
        } else {
            windowText ?: "SPIN BEZEL"
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
                // Clamp the pill inside the screen: islands near an edge used
                // to push their header pill halfway off it.
                val pillCx = gsx.coerceIn(gw / 2 + dp(16f), w - gw / 2 - dp(16f))
                val gRect = RectF(pillCx - gw / 2 - dp(8f), pillY - dp(11f), pillCx + gw / 2 + dp(8f), pillY + dp(11f))
                fill.color = Color.argb((aInt * 0.92f).toInt(), 20, 22, 28)
                c.drawRoundRect(gRect, dp(11f), dp(11f), fill)
                stroke.strokeWidth = dp(1.2f)
                stroke.color = withAlpha(grp.tint, aInt)
                c.drawRoundRect(gRect, dp(11f), dp(11f), stroke)
                text.color = Color.argb(aInt, 255, 255, 255)
                c.drawText(gLabel, pillCx - gw / 2, gRect.centerY() + text.textSize * 0.35f, text)
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

        // Header layout (search bar + letter chips + category chips), computed
        // up front: the star field is clipped to below the header and the
        // category chips flow-wrap at their natural widths. They used to be
        // squeezed into one equal-width row — at ~17 categories each chip was
        // narrower than its label, so the row rendered as one overlapping
        // ribbon of gibberish with stars shining through it.
        val padX = dp(16f)
        val barH = dp(42f)
        val chipH = dp(26f)
        val chipGap = dp(5f)
        val chipY = top + dp(6f) + barH + dp(7f)
        val catGroups = dev.watchreborn.apps.AppGroup.entries.filter { g -> alphabetical.any { it.defaultGroup == g } }
        text.textSize = sp(9.5f)
        val catChipW = catGroups.map { g ->
            text.measureText(g.title.substringBefore(' ').substringBefore('&').trim()) + dp(16f)
        }
        val catRows = ArrayList<List<Int>>()
        run {
            var row = ArrayList<Int>()
            var rowW = 0f
            val maxRowW = w - 2 * padX
            for (i in catGroups.indices) {
                val cw = catChipW[i]
                val added = if (row.isEmpty()) cw else rowW + chipGap + cw
                if (row.isNotEmpty() && added > maxRowW) {
                    catRows += row
                    row = ArrayList()
                    rowW = cw
                } else {
                    rowW = added
                }
                row += i
            }
            if (row.isNotEmpty()) catRows += row
        }
        val catY = chipY + chipH + dp(5f)
        val headerBottom = if (catRows.isEmpty()) chipY + chipH else catY + catRows.size * chipH + (catRows.size - 1) * chipGap

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

        // The star field never draws over the header: clip it to below the
        // last chip row (the header itself gets a solid backdrop later).
        c.save()
        c.clipRect(0f, headerBottom + dp(4f), w, h)

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
            // Stars clipped by the header take no hits, so taps on the header
            // can only land on the bar or the chips.
            if (y > headerBottom + dia * 0.25f) hits += Hit(x, y, dia / 2, app)

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

        c.restore()

        // Solid backdrop behind the whole header so nothing shows through the
        // gaps between chips.
        fill.color = Color.argb((aInt * 0.97f).toInt(), 10, 11, 14)
        c.drawRect(0f, 0f, w, headerBottom + dp(6f), fill)

        // 3. Top Constellation Search Bar (tap to open keyboard, or tap X to collapse)
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

        // 5. Category Constellation Filter Chips (color-coded by category
        // tint), flow-wrapped at natural label widths over as many rows as
        // they need — see the header-layout note at the top of this function.
        constellationCategoryChipRects.clear()
        text.textSize = sp(9.5f)
        for ((rowIdx, row) in catRows.withIndex()) {
            var cl = padX
            val ry = catY + rowIdx * (chipH + chipGap)
            for (gi in row) {
                val g = catGroups[gi]
                val active = q.isEmpty() && catFilter == g
                val cw = catChipW[gi]
                val cr = RectF(cl, ry, cl + cw, ry + chipH)
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
                cl += cw + chipGap
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
        stroke.color = withAlpha(folder.tint, (aInt * 0.85f).toInt())
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
            val dia = Metrics.GRID_CENTER_D * u * size * 0.92f * bVal * growthFor(folder.apps[i])
            val app = folder.apps[i]
            drawIcon(c, app.icon, x, y, dia, alpha * bVal, hasNotification(app), app.key in pinnedItemKeys)
            if (hypot(x - cx, y - cy) <= islandR) {
                hits += Hit(x, y, dia / 2, app, i)
            }
        }
        c.restore()

        // Carried member while drag-arranging / dragging out of the bloom:
        // the same overlay the full Folder view draws. The bloom used to
        // skip it (and registered member hits without slot indices), so a
        // long-press on a bloom member never picked it up and the member
        // Context Card / drag-out were unreachable in Bloom mode.
        if (placeInFolder && placeItem != null && placeFolder?.key == folder.key) {
            val carriedItem = placeItem!!
            val origin = hits.firstOrNull { it.slotIndex == placeFromSlot && it.item.key == carriedItem.key }
            if (origin != null) {
                fill.color = Color.argb((alpha * 150).toInt(), 0, 0, 0)
                c.drawCircle(origin.x, origin.y, origin.r, fill)
                stroke.strokeWidth = dp(1.5f)
                stroke.color = withAlpha(accent, (alpha * 160).toInt())
                c.drawCircle(origin.x, origin.y, origin.r, stroke)
            }
            val hoverHit = hits.firstOrNull { it.slotIndex == placeHoverSlot && it.item.key != carriedItem.key }
            if (hoverHit != null && placeHoverSlot != placeFromSlot) {
                stroke.strokeWidth = dp(3f)
                stroke.color = withAlpha(accent, (alpha * 255).toInt())
                c.drawCircle(hoverHit.x, hoverHit.y, hoverHit.r + dp(3f), stroke)
            }
            val carriedApp = carriedItem as? App
            if (carriedApp != null) {
                advancePlaceSpring()
                val cd = (origin?.r ?: dp(31f)) * 2f * placeScale
                drawIcon(c, carriedApp.icon, placeDrawX, placeDrawY, cd, alpha * bVal, hasNotification(carriedApp), carriedApp.key in pinnedItemKeys, jiggle = false)
            }
        }

        val ph = dp(38f)
        val label = "🌊 ${folder.label} (${folder.apps.size})   ✕"
        text.textSize = sp(14.5f)
        val tw = text.measureText(label)
        val btnSpace = folderHeaderBtnSpace(folder)
        val pw = tw + dp(32f) + btnSpace
        folderHeaderRect.set(w / 2 - pw / 2, top + dp(8f), w / 2 + pw / 2, top + dp(8f) + ph)
        fill.color = Color.argb((aInt * 0.92f).toInt(), 38, 40, 44)
        c.drawRoundRect(folderHeaderRect, ph / 2, ph / 2, fill)
        stroke.strokeWidth = dp(1.5f)
        stroke.color = withAlpha(folder.tint, aInt)
        c.drawRoundRect(folderHeaderRect, ph / 2, ph / 2, stroke)
        text.color = Color.argb(aInt, 255, 255, 255)
        val labelCx = (folderHeaderRect.left + folderHeaderRect.right - btnSpace) / 2
        c.drawText(label, labelCx - tw / 2, folderHeaderRect.centerY() + text.textSize * 0.35f, text)
        drawFolderHeaderButtons(c, folder, aInt)
    }

    /** TalkBack label for a widget-pod button: the drawn label minus its
     *  leading emoji, with spoken forms for the glyph-only buttons. */
    private fun podSpokenLabel(actionId: String, drawn: String): String {
        when (actionId) {
            "MEDIA_PREV" -> return "Previous track"
            "MEDIA_NEXT" -> return "Next track"
        }
        val presetSec = actionId.removePrefix("TIMER_").toIntOrNull()
        if (actionId.startsWith("TIMER_") && presetSec != null) {
            val mins = presetSec / 60
            return if (mins == 1) "1 minute timer preset" else "$mins minutes timer preset"
        }
        val cleaned = drawn.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }
            .replace(Regex(" +"), " ").trim()
        return cleaned.ifEmpty {
            actionId.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
        }
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
        widgetActionLabels.clear()

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
            widgetActionLabels += r to podSpokenLabel(actionId, label)
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
                is Folder -> withAlpha(centred.tint, (alpha * 255).toInt())
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
        sheetRowLabels.clear()

        fill.color = Color.argb((t * 195).toInt(), 0, 0, 0)
        c.drawRect(0f, 0f, w, h, fill)

        if (topologyPickerOpen) {
            drawTopologyPicker(c, w, h, t)
            return
        }

        val padX = dp(18f)
        val cardW = w - 2 * padX
        var rowH = dp(50f)
        val gap = dp(4f)
        // Sectioned rows: a null option marks a section header (not tappable).
        // Simplified in 1.9.0, slimmed again in 1.11.2 (Matt's call): the
        // front page is just Topology, Bubble Physics and Edit Layout —
        // everything set-and-forget lives behind "More Options". The old
        // App Search row is gone entirely: the pill at the top of the
        // cluster already opens search. Hidden Folders stays visible
        // while collapsed when folders are hidden: it's an active state,
        // not a setting.
        val rows = buildList<Triple<ClusterOption?, String, String>> {
            add(Triple(null, "LAYOUT", ""))
            add(Triple(ClusterOption.CLUSTER_TOPOLOGY, "Cluster Topology", "${clusterTopology.fullTitle} • Tap for all ${ClusterTopology.entries.size} designs"))
            if (sheetExpanded) {
                add(Triple(ClusterOption.FIXED_NEIGHBORHOODS, "Grid Layout", if (fixedNeighborhoods) "Fixed Compass Neighborhoods" else "Dynamic Usage Spiral"))
                add(Triple(ClusterOption.HONEYCOMB_HOME, "Home Screen", if (honeycombHome) "Honeycomb Core Hub" else "Watch Face First"))
            }
            add(Triple(null, "BEHAVIOR", ""))
            add(Triple(ClusterOption.BUBBLE_PHYSICS, "Bubble Physics", if (bubblePhysics) "Jello, waves & billiards" else "Off — bubbles sit still"))
            if (sheetExpanded) {
                add(Triple(ClusterOption.BLOOM_MODE, "Folder Open Style", if (bloomFolders) "Bloom in place" else "Full-screen folder"))
                add(Triple(ClusterOption.AUTO_FOLDERS, "Category Clustering", if (autoFolders) "Apps grouped by category" else "All apps loose"))
                add(Triple(ClusterOption.PER_LAYOUT_PINS, "Per-Layout Pins", if (perLayoutPins) "Separate pins per layout" else "One pin set everywhere"))
                add(Triple(ClusterOption.USAGE_GROWTH, "Usage Growth", if (usageGrowth) "Bubbles grow with use" else "Uniform bubble sizes"))
                add(Triple(ClusterOption.QUICK_SNIPE, "Mini-Icon Tap", if (quickSnipe) "Outer 6 icons launch" else "Tap opens the folder"))
                add(Triple(ClusterOption.LIVE_WIDGETS, "Live Grid Widgets", if (liveWidgets) "Media, timer, calendar, vitals" else "Apps & folders only"))
            }
            add(Triple(null, "ACTIONS", ""))
            add(Triple(ClusterOption.EDIT_LAYOUT, "Edit Layout", "Drag bubbles to place them"))
            if (sheetExpanded) {
                add(Triple(ClusterOption.SYSTEM_WIDGETS, "Android System Widgets", "View / add app widgets"))
                add(Triple(ClusterOption.SET_DEFAULT_HOME, "Default Phone Launcher", "Set Watch Reborn as Home app"))
                if (pinnedItemKeys.isNotEmpty()) {
                    add(Triple(ClusterOption.CLEAR_PINS, "Pinned Hex Slots (${pinnedItemKeys.size})", "Tap to unpin all bubbles"))
                }
            }
            if (hiddenFolderCount > 0) {
                add(Triple(ClusterOption.UNHIDE_FOLDERS, "Hidden Folders ($hiddenFolderCount)", "Tap to bring them back"))
            }
            add(Triple(ClusterOption.MORE_OPTIONS, if (sheetExpanded) "Show Fewer" else "More Options", if (sheetExpanded) "Collapse to the essentials" else "All the other settings"))
        }

        val headerH = dp(22f)
        val optionCount = rows.count { it.first != null }
        val headerCount = rows.size - optionCount
        // Reserve the Clear Pins row's slot even when there are no pins, so
        // the row height (and therefore every row's position) is identical
        // whether or not that conditional row is present. It used to change
        // the compression and shift every row by up to ~40px between opens.
        val layoutCount = optionCount + if (sheetExpanded && pinnedItemKeys.isEmpty()) 1 else 0
        var sheetH = dp(74f) + layoutCount * (rowH + gap) + headerCount * headerH + dp(52f)
        if (sheetH > h - dp(36f) && layoutCount > 0) {
            // Short screen: compress rows so the sheet — including its Done
            // button — always fits.
            rowH = ((h - dp(36f) - dp(74f) - headerCount * headerH - dp(52f)) / layoutCount - gap).coerceAtLeast(dp(34f))
            sheetH = dp(74f) + layoutCount * (rowH + gap) + headerCount * headerH + dp(52f)
        }

        // No slide-in offset: the sheet fades in at its final position, so a
        // row is in the same place on the first frame as the last. (The old
        // (1-t) rise made rows drift upward while the sheet animated open.)
        val sheetTop = (h - sheetH) / 2f
        sheetBoundsRect.set(padX, sheetTop, padX + cardW, sheetTop + sheetH)

        fill.color = Color.argb(a, 24, 26, 30)
        c.drawRoundRect(sheetBoundsRect, dp(24f), dp(24f), fill)
        stroke.strokeWidth = dp(1.5f)
        stroke.color = withAlpha(accent, (a * 0.6f).toInt())
        c.drawRoundRect(sheetBoundsRect, dp(24f), dp(24f), stroke)

        text.textSize = sp(18.5f)
        text.color = Color.argb(a, 255, 255, 255)
        val title = "Cluster Options"
        c.drawText(title, w / 2 - text.measureText(title) / 2, sheetTop + dp(32f), text)

        text.textSize = sp(12.5f)
        text.color = Color.argb((a * 0.7f).toInt(), 188, 190, 198)
        val hint = if (sheetExpanded) "Layout • Behavior • Actions" else "Essentials up front — More Options has the rest"
        c.drawText(hint, w / 2 - text.measureText(hint) / 2, sheetTop + dp(55f), text)

        var ry = sheetTop + dp(68f)
        for ((opt, label, valueText) in rows) {
            if (opt == null) {
                text.textSize = sp(12f)
                text.color = Color.argb((a * 0.65f).toInt(), 168, 170, 178)
                c.drawText(label, padX + dp(16f), ry + dp(15f), text)
                ry += headerH
                continue
            }
            val r = RectF(padX + dp(12f), ry, padX + cardW - dp(12f), ry + rowH)
            fill.color = Color.argb(a, 40, 42, 48)
            c.drawRoundRect(r, dp(14f), dp(14f), fill)

            // Baselines scale with the row height so the bigger text
            // (1.11.2) still fits when a short screen compresses rows.
            text.textSize = sp(12.5f)
            text.color = Color.argb((a * 0.75f).toInt(), 178, 180, 188)
            c.drawText(label, r.left + dp(14f), r.top + rowH * 0.40f, text)

            text.textSize = sp(15.5f)
            text.color = withAlpha(accent, a)
            c.drawText(valueText, r.left + dp(14f), r.top + rowH * 0.86f, text)

            // Right-side affordance: an ON/OFF chip for toggles, a chevron for actions,
            // so the two kinds of row no longer look identical.
            val toggleOn: Boolean? = when (opt) {
                ClusterOption.FIXED_NEIGHBORHOODS -> fixedNeighborhoods
                ClusterOption.HONEYCOMB_HOME -> honeycombHome
                ClusterOption.BUBBLE_PHYSICS -> bubblePhysics
                ClusterOption.PER_LAYOUT_PINS -> perLayoutPins
                ClusterOption.USAGE_GROWTH -> usageGrowth
                ClusterOption.BLOOM_MODE -> bloomFolders
                ClusterOption.QUICK_SNIPE -> quickSnipe
                ClusterOption.LIVE_WIDGETS -> liveWidgets
                ClusterOption.AUTO_FOLDERS -> autoFolders
                else -> null
            }
            if (toggleOn != null) {
                val chipW = dp(44f)
                val chipH = dp(20f)
                val chip = RectF(r.right - dp(12f) - chipW, r.centerY() - chipH / 2, r.right - dp(12f), r.centerY() + chipH / 2)
                fill.color = if (toggleOn) withAlpha(accent, a) else Color.argb(a, 84, 84, 90)
                c.drawRoundRect(chip, chipH / 2, chipH / 2, fill)
                text.textSize = sp(10.5f)
                text.color = if (toggleOn) Color.argb(a, 0, 0, 0) else Color.argb(a, 235, 235, 240)
                val s = if (toggleOn) "ON" else "OFF"
                c.drawText(s, chip.centerX() - text.measureText(s) / 2, chip.centerY() + text.textSize * 0.35f, text)
            } else {
                text.textSize = sp(20f)
                text.color = Color.argb((a * 0.6f).toInt(), 200, 202, 208)
                c.drawText("›", r.right - dp(22f), r.centerY() + text.textSize * 0.35f, text)
            }

            sheetRowRects += r to opt
            sheetRowLabels += r to when {
                toggleOn != null -> "$label, ${if (toggleOn) "on" else "off"}"
                opt == ClusterOption.CLUSTER_TOPOLOGY -> "$label, ${clusterTopology.fullTitle}"
                opt == ClusterOption.MORE_OPTIONS -> if (sheetExpanded) "Show fewer options" else "More options"
                else -> label
            }
            ry += rowH + gap
        }

        val doneW = dp(140f)
        val doneH = dp(42f)
        val doneRect = RectF(w / 2 - doneW / 2, ry + dp(4f), w / 2 + doneW / 2, ry + dp(4f) + doneH)
        fill.color = withAlpha(accent, a)
        c.drawRoundRect(doneRect, doneH / 2, doneH / 2, fill)
        text.textSize = sp(15.5f)
        text.color = Color.argb(a, 0, 0, 0)
        val doneStr = "Done"
        c.drawText(doneStr, w / 2 - text.measureText(doneStr) / 2, doneRect.centerY() + text.textSize * 0.35f, text)
        sheetRowRects += doneRect to ClusterOption.CLOSE_SHEET
        sheetRowLabels += doneRect to "Done"
    }

    /** Full list of cluster layouts: tap one to apply it directly (replaces tap-to-cycle). */
    private fun drawTopologyPicker(c: Canvas, w: Float, h: Float, t: Float) {
        val a = (t * 255).toInt().coerceIn(0, 255)
        topologyRowRects.clear()

        val padX = dp(18f)
        val cardW = w - 2 * padX
        var rowH = dp(50f)
        val gap = dp(4f)
        val all = ClusterTopology.entries
        var sheetH = dp(74f) + all.size * (rowH + gap) + dp(52f)
        if (sheetH > h - dp(36f)) {
            // Short screen: compress rows so the picker always fits.
            rowH = ((h - dp(36f) - dp(74f) - dp(52f)) / all.size - gap).coerceAtLeast(dp(34f))
            sheetH = dp(74f) + all.size * (rowH + gap) + dp(52f)
        }
        // Same no-slide rule as the options sheet: rows never drift while opening.
        val sheetTop = (h - sheetH) / 2f
        sheetBoundsRect.set(padX, sheetTop, padX + cardW, sheetTop + sheetH)

        fill.color = Color.argb(a, 24, 26, 30)
        c.drawRoundRect(sheetBoundsRect, dp(24f), dp(24f), fill)
        stroke.strokeWidth = dp(1.5f)
        stroke.color = withAlpha(accent, (a * 0.6f).toInt())
        c.drawRoundRect(sheetBoundsRect, dp(24f), dp(24f), stroke)

        text.textSize = sp(18.5f)
        text.color = Color.argb(a, 255, 255, 255)
        val title = "Cluster Layout"
        c.drawText(title, w / 2 - text.measureText(title) / 2, sheetTop + dp(32f), text)

        text.textSize = sp(12.5f)
        text.color = Color.argb((a * 0.7f).toInt(), 188, 190, 198)
        val hint = "Tap a design to apply it instantly"
        c.drawText(hint, w / 2 - text.measureText(hint) / 2, sheetTop + dp(55f), text)

        var ry = sheetTop + dp(68f)
        for (topo in all) {
            val r = RectF(padX + dp(12f), ry, padX + cardW - dp(12f), ry + rowH)
            fill.color = Color.argb(a, 40, 42, 48)
            c.drawRoundRect(r, dp(14f), dp(14f), fill)
            if (topo == clusterTopology) {
                stroke.strokeWidth = dp(1.5f)
                stroke.color = withAlpha(accent, (a * 0.8f).toInt())
                c.drawRoundRect(r, dp(14f), dp(14f), stroke)
            }

            text.textSize = sp(15.5f)
            text.color = Color.argb(a, 255, 255, 255)
            c.drawText(topo.fullTitle, r.left + dp(14f), r.top + rowH * 0.40f, text)

            text.textSize = sp(12.5f)
            text.color = Color.argb((a * 0.75f).toInt(), 178, 180, 188)
            c.drawText(topo.subtitle, r.left + dp(14f), r.top + rowH * 0.86f, text)

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
                text.textSize = sp(17f)
                text.color = withAlpha(accent, a)
                c.drawText("✓", r.right - dp(26f), r.centerY() + text.textSize * 0.35f, text)
            }

            topologyRowRects += r to topo
            ry += rowH + gap
        }

        val backW = dp(140f)
        val backH = dp(42f)
        pickerBackRect.set(w / 2 - backW / 2, ry + dp(4f), w / 2 + backW / 2, ry + dp(4f) + backH)
        fill.color = Color.argb(a, 52, 54, 60)
        c.drawRoundRect(pickerBackRect, backH / 2, backH / 2, fill)
        text.textSize = sp(15.5f)
        text.color = Color.argb(a, 255, 255, 255)
        val backStr = "‹ Back"
        c.drawText(backStr, w / 2 - text.measureText(backStr) / 2, pickerBackRect.centerY() + text.textSize * 0.35f, text)
    }

    /** Classic full-screen Folder View (used when [bloomFolders] is false). */
    /** Extra pill width the open folder's header buttons need. */
    private fun folderHeaderBtnSpace(folder: Folder): Float =
        if (folder.isCustom) dp(78f) else dp(58f)

    /**
     * Header action buttons for the open folder, drawn inside its pill:
     * Rename + Delete for custom folders, Hide for automatic category
     * folders. Rects are recorded for tap(); the rest of the pill closes.
     */
    private fun drawFolderHeaderButtons(c: Canvas, folder: Folder, aInt: Int) {
        val cy = folderHeaderRect.centerY()
        if (folder.isCustom) {
            val r = dp(15f)
            val delCx = folderHeaderRect.right - dp(24f)
            val renCx = delCx - dp(38f)
            folderDeleteRect.set(delCx - r, cy - r, delCx + r, cy + r)
            folderRenameRect.set(renCx - r, cy - r, renCx + r, cy + r)
            folderHideRect.setEmpty()
            fill.color = Color.argb((aInt * 0.16f).toInt(), 255, 255, 255)
            c.drawCircle(renCx, cy, r, fill)
            c.drawCircle(delCx, cy, r, fill)
            text.textAlign = Paint.Align.CENTER
            text.textSize = sp(13f)
            text.color = Color.argb(aInt, 255, 255, 255)
            c.drawText("✎", renCx, cy + text.textSize * 0.36f, text)
            text.textSize = sp(12f)
            c.drawText("🗑", delCx, cy + text.textSize * 0.36f, text)
            text.textAlign = Paint.Align.LEFT
        } else {
            folderRenameRect.setEmpty()
            folderDeleteRect.setEmpty()
            val bw = dp(48f)
            val bh = dp(26f)
            folderHideRect.set(folderHeaderRect.right - dp(8f) - bw, cy - bh / 2, folderHeaderRect.right - dp(8f), cy + bh / 2)
            fill.color = Color.argb((aInt * 0.16f).toInt(), 255, 255, 255)
            c.drawRoundRect(folderHideRect, bh / 2, bh / 2, fill)
            text.textAlign = Paint.Align.CENTER
            text.textSize = sp(11f)
            text.color = Color.argb(aInt, 255, 255, 255)
            c.drawText("Hide", folderHideRect.centerX(), cy + text.textSize * 0.36f, text)
            text.textAlign = Paint.Align.LEFT
        }
    }

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
            // Same position glide as the grid, in folder-view space: reordered
            // members slide to their new spots instead of snapping.
            glideToward(glidePosFolder, folder.apps[i].key, tmp[0], tmp[1], Metrics.GRID_PITCH * u * 1.08f * 4.5f)
            tmp[0] = glideTmp[0]
            tmp[1] = glideTmp[1]
            val dx = (tmp[0] + folderPanX.value) / u
            val dy = (tmp[1] + folderPanY.value) / u
            val d = hypot(dx / sx, dy / sy)
            val size = Fisheye.size(d)
            if (size <= 0.02f) continue
            val k = if (d > 1e-4f) Fisheye.pulled(d) / d else 1f
            val x = cx + dx * k * u
            val y = cy + dy * k * u
            val dia = Metrics.GRID_CENTER_D * u * size * 1.08f * growthFor(folder.apps[i])
            val app = folder.apps[i]
            drawIcon(c, app.icon, x, y, dia, alpha, hasNotification(app), app.key in pinnedItemKeys)
            hits += Hit(x, y, dia / 2, app, i)
        }

        if (placeInFolder && placeItem != null) {
            val item = placeItem!!
            val origin = hits.firstOrNull { it.slotIndex == placeFromSlot }
            val hover = hits.firstOrNull { it.slotIndex == placeHoverSlot }
            if (origin != null) {
                fill.color = Color.argb((alpha * 150).toInt(), 0, 0, 0)
                c.drawCircle(origin.x, origin.y, origin.r, fill)
                stroke.strokeWidth = dp(1.5f)
                stroke.color = withAlpha(accent, (alpha * 160).toInt())
                c.drawCircle(origin.x, origin.y, origin.r, stroke)
            }
            if (hover != null && placeHoverSlot != placeFromSlot) {
                stroke.strokeWidth = dp(3f)
                stroke.color = withAlpha(accent, (alpha * 255).toInt())
                c.drawCircle(hover.x, hover.y, hover.r + dp(3f), stroke)
            }
            val app = item as? App
            if (app != null) {
                advancePlaceSpring()
                val d = (origin?.r ?: dp(31f)) * 2f * placeScale
                drawIcon(c, app.icon, placeDrawX, placeDrawY, d, alpha, hasNotification(app), app.key in pinnedItemKeys, jiggle = false)
            }
        }

        val ph = dp(40f)
        val label = "←  ${folder.label} (${folder.apps.size})"
        text.textSize = sp(15f)
        val tw = text.measureText(label)
        val btnSpace = folderHeaderBtnSpace(folder)
        val pw = tw + dp(36f) + btnSpace
        folderHeaderRect.set(w / 2 - pw / 2, top + dp(8f), w / 2 + pw / 2, top + dp(8f) + ph)
        fill.color = Color.argb((alpha * 235).toInt(), 44, 44, 46)
        c.drawRoundRect(folderHeaderRect, ph / 2, ph / 2, fill)
        stroke.strokeWidth = dp(1.5f)
        stroke.color = withAlpha(folder.tint, (alpha * 220).toInt())
        c.drawRoundRect(folderHeaderRect, ph / 2, ph / 2, stroke)
        text.color = Color.argb((alpha * 255).toInt(), 255, 255, 255)
        val labelCx = (folderHeaderRect.left + folderHeaderRect.right - btnSpace) / 2
        c.drawText(label, labelCx - tw / 2, folderHeaderRect.centerY() + text.textSize * 0.35f, text)
        drawFolderHeaderButtons(c, folder, (alpha * 255).toInt().coerceIn(0, 255))
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

    /**
     * Bottom navigation dock (1.6.0): one floating bar, four segments —
     * Face / Cluster / List / Stack — replacing the single List/Grid pill.
     * The current mode's segment is lit in the accent; tapping Cluster
     * while already in the cluster recentres the field.
     */
    private fun drawDock(c: Canvas, w: Float, bottom: Float, alpha: Float) {
        if (alpha <= 0.01f) {
            pill.setEmpty()
            dockSegs.forEach { it.setEmpty() }
            return
        }
        val ph = dp(Metrics.PHONE_PILL_H_DP)
        val segW = dp(68f)
        val pw = segW * 4f
        val py = bottom - ph - dp(16f)

        pill.set(w / 2 - pw / 2, py, w / 2 + pw / 2, py + ph)
        fill.color = Color.argb((alpha * 230).toInt(), 44, 44, 46)
        c.drawRoundRect(pill, ph / 2, ph / 2, fill)
        val activeSeg = when {
            list.target >= 0.5f -> DOCK_LIST
            grid.target >= 0.5f -> DOCK_CLUSTER
            else -> DOCK_FACE
        }
        val labels = arrayOf("Face", "Cluster", "List", "Stack")
        for (i in 0 until 4) {
            val seg = RectF(pill.left + i * segW, py, pill.left + (i + 1) * segW, py + ph)
            dockSegs[i].set(seg)
            val active = i == activeSeg
            if (active) {
                fill.color = withAlpha(accent, (alpha * 40).toInt())
                val hl = RectF(seg.left + dp(6f), seg.top + dp(6f), seg.right - dp(6f), seg.bottom - dp(6f))
                c.drawRoundRect(hl, dp(16f), dp(16f), fill)
            }
            val iconColor = if (active) withAlpha(accent, (alpha * 255).toInt())
                else Color.argb((alpha * 205).toInt(), 235, 235, 240)
            drawDockIcon(c, i, seg.centerX(), py + dp(19f), dp(17f), iconColor)
            text.textAlign = Paint.Align.CENTER
            text.textSize = sp(9f)
            text.color = if (active) withAlpha(accent, (alpha * 255).toInt())
                else Color.argb((alpha * 165).toInt(), 195, 195, 200)
            c.drawText(labels[i], seg.centerX(), py + ph - dp(6f), text)
            text.textAlign = Paint.Align.LEFT
        }
    }

    /** One dock icon, drawn rather than a font glyph so all four segments
     *  share one line style (1.7.0 polish — the glyph set looked ragged). */
    private fun drawDockIcon(c: Canvas, kind: Int, cx: Float, cy: Float, s: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = dp(1.7f)
        stroke.strokeCap = Paint.Cap.ROUND
        fill.color = color
        when (kind) {
            DOCK_FACE -> {
                c.drawCircle(cx, cy, s * 0.48f, stroke)
                c.drawLine(cx, cy, cx, cy - s * 0.28f, stroke)
                c.drawLine(cx, cy, cx + s * 0.22f, cy + s * 0.12f, stroke)
            }
            DOCK_CLUSTER -> {
                val ring = s * 0.31f
                val dot = s * 0.13f
                c.drawCircle(cx, cy, dot, fill)
                for (k in 0 until 6) {
                    val a = Math.toRadians(k * 60.0 - 90.0)
                    c.drawCircle(cx + (cos(a) * ring).toFloat(), cy + (sin(a) * ring).toFloat(), dot, fill)
                }
            }
            DOCK_LIST -> {
                for (row in -1..1) {
                    val y = cy + row * s * 0.32f
                    c.drawLine(cx - s * 0.24f, y, cx + s * 0.46f, y, stroke)
                    c.drawCircle(cx - s * 0.40f, y, s * 0.075f, fill)
                }
            }
            DOCK_STACK -> {
                val side = s * 0.54f
                for (k in 2 downTo 0) {
                    val ox = cx - side / 2 + k * s * 0.17f
                    val oy = cy - side / 2 - k * s * 0.17f + s * 0.17f
                    val rr = RectF(ox, oy, ox + side, oy + side)
                    if (k == 0) {
                        fill.color = withAlpha(color, 70)
                        c.drawRoundRect(rr, dp(3f), dp(3f), fill)
                        fill.color = color
                    }
                    c.drawRoundRect(rr, dp(3f), dp(3f), stroke)
                }
            }
        }
        stroke.strokeCap = Paint.Cap.BUTT
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
    private fun drawGridItem(c: Canvas, item: GridItem, x: Float, y: Float, d: Float, alpha: Float, cal: Calendar, jiggle: Boolean = true) {
        if (item is WidgetBubble) {
            drawWidgetBubble(c, item, x, y, d, alpha, cal)
        } else {
            drawIcon(c, item.icon, x, y, d, alpha, hasNotification(item), item.key in pinnedItemKeys, jiggle)
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

    private fun drawIcon(c: Canvas, b: android.graphics.Bitmap, x: Float, y: Float, d: Float, alpha: Float, badge: Boolean, pinned: Boolean, jiggle: Boolean = true) {
        if (alpha <= 0.004f || d < 2f) return
        if (badge && d > dp(22f) && x + d / 2 > 0f && x - d / 2 < width && y + d / 2 > 0f && y - d / 2 < height) {
            frameBadgeVisible = true
        }
        val aInt = (alpha * 255).toInt().coerceIn(0, 255)
        // Notification jiggle (1.3.0): a bubble with a pending notification
        // wobbles like the iOS edit-mode jiggle — a small rotation oscillation
        // (±1.7°, ~0.47s period), phase-offset per icon so notified bubbles
        // don't move in lockstep. The whole icon (bitmap, pulse ring, dots)
        // rotates together around the bubble centre. Transient copies (the
        // carried bubble, the drop glide, the Context Card lift) pass
        // jiggle = false and stay steady.
        val jiggling = jiggle && badge && d > dp(22f)
        if (jiggling) {
            val phase = (System.identityHashCode(b) and 0xFFFF) / 65535f * 6.2832f
            val ang = sin(System.currentTimeMillis() / 75.0 + phase).toFloat() * 1.7f
            c.save()
            c.rotate(ang, x, y)
        }
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
        if (jiggling) c.restore()
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
            cardMenuOpen -> {}
            clusterSheet.target == 1f -> {}
            stackIn.target == 1f -> {
                stack.cardRects.lastOrNull { it.first.contains(downX, downY) }?.let { (_, card) ->
                    onTogglePinCard(card.id)
                }
            }
            apps.target == 1f -> {
                hitEntryAt(downX, downY)?.let { h ->
                    val fallbackSlot = activeGridItems.indexOfFirst { it.key == h.item.key }
                    val slot = if (h.slotIndex >= 0) h.slotIndex else fallbackSlot
                    when {
                        // Inside the full-screen Folder view, a long-press picks
                        // the member up to drag-arrange the folder's order.
                        folderIn.target == 1f && activeFolder != null && h.slotIndex >= 0 ->
                            beginPlace(h.item, h.slotIndex, inFolder = true)
                        // In the full grid, a long-press picks the bubble up for
                        // drag-to-place; releasing without a drag opens the menu.
                        canDragPlace(slot) -> beginPlace(h.item, slot)
                        else -> onLongPressItem(h.item, slot)
                    }
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
                // Edit mode: touching a bubble picks it up immediately — the
                // whole point is arranging without long-pressing each bubble.
                if (editMode && modeAtDown == MODE_APPS && !edgeCrownDragging) {
                    hitEntryAt(e.x, e.y)?.let { h ->
                        val slot = if (h.slotIndex >= 0) h.slotIndex else activeGridItems.indexOfFirst { it.key == h.item.key }
                        if (canDragPlace(slot)) {
                            beginPlace(h.item, slot)
                            return true
                        }
                    }
                }
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (cardMenuOpen) return true
                if (pinching) return true
                if (placeItem != null) {
                    placeX = e.x; placeY = e.y
                    if (hypot(e.x - downX, e.y - downY) > slop) placeMoved = true
                    val centers = hits.filter { it.slotIndex >= 0 }
                        .filter { !placeInFolder || placeFolder?.apps?.any { a -> a.key == it.item.key } == true }
                        .map { Triple(it.x, it.y, it.slotIndex) }
                    val newHover = PlaceSlots.nearestSlot(e.x, e.y, centers, dp(90f))
                    if (newHover != placeHoverSlot) {
                        placeHoverSlot = newHover
                        if (newHover >= 0 && newHover != placeFromSlot) {
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        updateFolderArm()
                    }
                    invalidate()
                    return true
                }
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
                if (placeItem != null) {
                    finishPlace()
                    dragging = false
                    invalidate()
                    return true
                }
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
                placeItem = null
                placeHoverSlot = -1
                placeMoved = false
                placeInFolder = false
                placeFolder = null
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
                                // Vertical drag pans the arc field so the higher
                                // arcs can be pulled down into reach; the range
                                // scales with how many arcs the catalog fills.
                                val dockPitch = Metrics.GRID_PITCH * unit
                                val maxPan = dockPitch * (2.2f + 0.88f * RingMath.ringCount(activeGridItems.size, 4, 2))
                                panY.snap((panY.value + dy).coerceIn(-dockPitch * 1.2f, maxPan))
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
        panX.target = if (clusterTopology == ClusterTopology.RIVER || clusterTopology == ClusterTopology.MAGNETIC_DOCK) 0f else -tmp[0]
        panY.target = if (clusterTopology == ClusterTopology.MAGNETIC_DOCK) {
            // Same clamp as the drag handler, so a release can't fling the
            // dock's pivot off the top or bottom of the screen.
            val dockPitch = Metrics.GRID_PITCH * w
            (-tmp[1]).coerceIn(-dockPitch * 1.2f, dockPitch * (2.2f + 0.88f * RingMath.ringCount(items.size, 4, 2)))
        } else {
            -tmp[1]
        }
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
        // The Context Card is the topmost layer: a row tap runs its action
        // (the card closes either way); a tap anywhere else dismisses it.
        if (cardMenuOpen) {
            val menuItem = cardItem
            val rowHit = cardRows.lastOrNull { it.rect.contains(x, y) }
            closeCardMenu()
            if (rowHit != null && menuItem != null) runCardAction(rowHit.kind, menuItem, cardSlot)
            return
        }

        // Placement undo bar: the Undo button restores the pre-drag pin state;
        // tapping elsewhere on the bar just dismisses it.
        if (undoActive && !undoRect.isEmpty && undoRect.contains(x, y)) {
            val action = if (undoBtnRect.contains(x, y)) undoAction else null
            undoLabel = null
            undoAction = null
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            action?.invoke() ?: invalidate()
            return
        }

        // Edit mode: the Done pill exits.
        if (editMode && placeItem == null && !editDoneRect.isEmpty && editDoneRect.contains(x, y)) {
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            exitEditMode()
            return
        }

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
                    ClusterOption.EDIT_LAYOUT -> {
                        clusterSheet.target = 0f
                        enterEditMode()
                    }
                    ClusterOption.CLUSTER_TOPOLOGY -> {
                        topologyPickerOpen = true
                        invalidate()
                    }
                    ClusterOption.MORE_OPTIONS -> {
                        sheetExpanded = !sheetExpanded
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
                // Bottom dock (1.6.0): four destinations, one tap each.
                val seg = dockSegs.indexOfFirst { it.contains(x, y) }
                clearSearch()
                when (seg) {
                    DOCK_FACE -> {
                        if (folderIn.target == 1f) closeFolder()
                        toFace()
                    }
                    DOCK_CLUSTER -> {
                        if (folderIn.target == 1f) closeFolder()
                        when {
                            list.target >= 0.5f -> {
                                list.target = 0f
                                grid.target = 1f
                                preferListChanged(false)
                            }
                            grid.target < 0.5f -> grid.target = 1f
                            else -> {
                                // Already in the cluster: recentre the field.
                                overview.target = 0f
                                panX.target = 0f
                                panY.target = 0f
                            }
                        }
                    }
                    DOCK_LIST -> {
                        if (folderIn.target == 1f) closeFolder()
                        if (list.target < 0.5f) {
                            list.target = 1f
                            preferListChanged(true)
                        }
                    }
                    DOCK_STACK -> {
                        if (folderIn.target == 1f) closeFolder()
                        openStack()
                    }
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
                val af = activeFolder
                if (af != null && !folderRenameRect.isEmpty && folderRenameRect.contains(x, y)) {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    onRenameFolder(af)
                    return
                }
                if (af != null && !folderDeleteRect.isEmpty && folderDeleteRect.contains(x, y)) {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    onDeleteFolder(af)
                    return
                }
                if (af != null && !folderHideRect.isEmpty && folderHideRect.contains(x, y)) {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    onHideFolder(af)
                    return
                }
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
        const val HINT_FACE_SWIPE = "faceswipe"
        const val HINT_PINCH = "pinch"
        const val HINT_CROWN = "crown"
        const val HINT_PIN = "pin"
        const val HINT_SCRUB = "scrub"
        const val CARD_PIN = 0
        const val CARD_UNPIN = 1
        const val CARD_INFO = 2
        const val CARD_UNINSTALL = 3
        const val CARD_OPEN = 4
        const val CARD_MORE = 5
        const val CARD_EDIT = 6
        const val CARD_RENAME = 7
        const val CARD_DELETE_FOLDER = 8
        const val CARD_LAUNCH = 9
        const val CARD_REMOVE_FROM_FOLDER = 10
        const val DOCK_FACE = 0
        const val DOCK_CLUSTER = 1
        const val DOCK_LIST = 2
        const val DOCK_STACK = 3
        /** Bubbles riding the Chrono Bezel's outer carousel ring at once. */
        const val BEZEL_OUTER_CAP = 18
        const val MODE_FACE = 0
        const val MODE_APPS = 1
        const val MODE_STACK = 2
        const val MODE_CONTROL = 3
        val FOCUS_RING_ORDER = listOf(Hex(0, -1), Hex(1, -1), Hex(1, 0), Hex(0, 1), Hex(-1, 1), Hex(-1, 0))
    }
}
