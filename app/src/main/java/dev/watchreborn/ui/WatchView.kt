package dev.watchreborn.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import dev.watchreborn.apps.App
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The whole launcher on one canvas: the face, the app view (the focus cluster, the full grid and
 * the list) and the Smart Stack. Home on the face opens the apps, as pressing the crown does;
 * swiping up on the face opens the Smart Stack, as turning the crown does.
 */
@SuppressLint("ViewConstructor")
class WatchView(
    context: Context,
    private val onLaunch: (App, RectF) -> Unit,
    private val onLongPressApp: (App) -> Unit,
    private val onLongPressFace: () -> Unit,
    private val onPullDown: () -> Unit,
) : View(context) {

    val face = Face()
    val stack = Stack()
    var info = FaceInfo()

    /** Apps A-Z (List view) and by use (grid and cluster: most used in the middle). */
    private var alphabetical: List<App> = emptyList()
    private var byUse: List<App> = emptyList()
    private var gridHexes: List<Hex> = emptyList()

    var accent: Int = Color.rgb(166, 227, 110)
        set(v) { field = v; face.accent = v; stack.accent = v; invalidate() }

    /** 0 = face, 1 = apps. */
    private val apps = Spring(0f)
    /** 0 = focus cluster, 1 = full grid. */
    private val grid = Spring(0f)
    /** 0 = grid, 1 = list. */
    private val list = Spring(0f)
    /** 0 = face, 1 = Smart Stack. */
    private val stackIn = Spring(0f)
    private val panX = Spring(0f, 0.35f)
    private val panY = Spring(0f, 0.35f)
    private val stackScroll = Spring(0f, 0.3f)
    private var listScroll = 0f
    private var listVelocity = 0f

    /** Open the List view rather than the cluster (the app view the user last chose). */
    var preferList = false
        set(v) { field = v; list.snap(if (v) 1f else 0f) }

    var topInset = 0f
    var bottomInset = 0f

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    private val rect = RectF()
    private val tmp = FloatArray(2)
    private val pill = RectF()

    /** Every icon drawn this frame (centre, radius) for hit testing; last drawn is on top. */
    private val hits = ArrayList<Hit>()
    private class Hit(val x: Float, val y: Float, val r: Float, val app: App)

    private var lastFrame = 0L

    fun setApps(alpha: List<App>, use: List<App>) {
        alphabetical = alpha
        byUse = use
        gridHexes = Hex.spiral(use.size)
        face.favorites = use.take(3)
        val recents = use.take(4)
        stack.cards = buildList {
            info.nextAlarm?.let { add(Card(Color.rgb(255, 159, 10), "Alarm", timeText(it), "Next alarm")) }
            add(Card(Color.rgb(48, 209, 88), "Battery", if (info.battery >= 0) "${info.battery}%" else "--",
                if (info.charging) "Charging" else "On battery"))
            if (recents.isNotEmpty()) add(Card(Color.rgb(100, 210, 255), "Recent", "", apps = recents))
        }
        invalidate()
    }

    fun refreshCards() = setApps(alphabetical, byUse)

    private fun timeText(ms: Long) = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(ms)

    // ---------------------------------------------------------------- navigation

    val onFace get() = apps.target == 0f && stackIn.target == 0f

    /** Home: from the face, the apps (the crown press); from anywhere else, back to the face. */
    fun home() {
        if (onFace) openApps() else toFace()
    }

    /** Back: to the face. Returns false if already there. */
    fun back(): Boolean {
        if (onFace) return false
        toFace()
        return true
    }

    fun openApps() {
        stackIn.target = 0f
        grid.snap(0f)
        panX.snap(0f); panY.snap(0f)
        apps.target = 1f
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        invalidate()
    }

    fun toFace() {
        apps.target = 0f
        stackIn.target = 0f
        stackScroll.target = 0f
        invalidate()
    }

    private fun openStack() {
        stackIn.target = 1f
        stackScroll.snap(0f)
        stack.scroll = 0f
        invalidate()
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1000f).coerceAtMost(0.05f)
        lastFrame = now
        for (s in listOf(apps, grid, list, stackIn, panX, panY, stackScroll)) s.step(dt)
        stepListFling(dt)
        stack.scroll = stackScroll.value

        val w = width.toFloat()
        val h = height.toFloat()
        val top = topInset + 0.02f * w
        val bottom = h - bottomInset - 0.02f * w
        c.drawColor(Color.BLACK)
        hits.clear()
        val cal = Calendar.getInstance()
        face.is24Hour = android.text.format.DateFormat.is24HourFormat(context)

        // The face fades and dims as the apps or the stack come in.
        val faceAlpha = (1f - apps.value) * (1f - stackIn.value)
        face.draw(c, w, h, top, bottom, cal, info, faceAlpha * (1f - (1f - Metrics.FACE_DIM) * apps.value))
        if (stackIn.value > 0.001f) stack.draw(c, w, h, top, bottom, cal, stackIn.value)

        val a = apps.value
        if (a > 0.001f) {
            val focusAlpha = a * (1f - grid.value) * (1f - list.value)
            val gridAlpha = a * grid.value * (1f - list.value)
            val listAlpha = a * list.value
            // Icons arrive large and soft and settle into place (Metrics.ICONS_FROM_SCALE).
            val zoom = Metrics.ICONS_FROM_SCALE + (1f - Metrics.ICONS_FROM_SCALE) * a
            if (focusAlpha > 0.001f) drawFocus(c, w, h, focusAlpha, zoom * (1f - 0.12f * grid.value), a)
            if (gridAlpha > 0.001f) drawGrid(c, w, h, gridAlpha, 1.15f - 0.15f * grid.value)
            if (listAlpha > 0.001f) drawList(c, w, h, top, bottom, listAlpha)
            drawPill(c, w, bottom, a * max(grid.value, list.value))
        }

        val animating = listOf(apps, grid, list, stackIn, panX, panY, stackScroll).any { !it.settled } ||
            abs(listVelocity) > 1f || dragging
        if (animating) postInvalidateOnAnimation()
        else {
            lastFrame = 0L
            // The face's seconds: wake at the next whole second.
            if (faceAlpha > 0f || stackIn.value > 0f) postInvalidateDelayed(1000L - System.currentTimeMillis() % 1000L)
        }
    }

    /** The cluster: most used app in the middle, the next six round it, the rest blurred behind. */
    private fun drawFocus(c: Canvas, w: Float, h: Float, alpha: Float, zoom: Float, sharpness: Float) {
        val cx = w / 2
        val cy = h / 2
        // Behind: the rest of the apps on the full grid's lattice, soft and dim.
        for (i in 7 until byUse.size) {
            pointyCenter(gridHexes[i], Metrics.GRID_PITCH * w * 1.25f * zoom, tmp)
            val d = Metrics.GRID_CENTER_D * w * 0.85f * zoom
            if (abs(tmp[1]) > h / 2 + d) continue
            drawIcon(c, byUse[i].blurred, cx + tmp[0], cy + tmp[1], d, alpha * Metrics.FOCUS_BACKDROP_ALPHA)
        }
        for (i in 0 until minOf(7, byUse.size)) {
            val hex = if (i == 0) Hex(0, 0) else FOCUS_RING_ORDER[i - 1]
            flatCenter(hex, Metrics.FOCUS_RING * w * zoom, tmp)
            val d = (if (i == 0) Metrics.FOCUS_CENTER_D else Metrics.FOCUS_RING_D) * w * zoom
            val x = cx + tmp[0]; val y = cy + tmp[1]
            // Soft while arriving, sharp once settled.
            drawIcon(c, byUse[i].blurred, x, y, d, alpha * (1f - sharpness))
            drawIcon(c, byUse[i].icon, x, y, d, alpha * sharpness)
            hits += Hit(x, y, d / 2, byUse[i])
        }
    }

    /** The full grid: pointy-topped rows with the measured fisheye, panned by [panX]/[panY]. */
    private fun drawGrid(c: Canvas, w: Float, h: Float, alpha: Float, zoom: Float) {
        val cx = w / 2
        val cy = h / 2
        val stretch = max(1f, (h / w) / Metrics.WATCH_ASPECT)
        for (i in byUse.indices) {
            pointyCenter(gridHexes[i], Metrics.GRID_PITCH * w, tmp)
            val dx = (tmp[0] + panX.value) / w
            val dy = (tmp[1] + panY.value) / w
            // Distance on an ellipse the shape of the screen, so a tall phone shows more rows.
            val d = hypot(dx, dy / stretch)
            val size = Fisheye.size(d)
            if (size <= 0.02f) continue
            val k = if (d > 1e-4f) Fisheye.pulled(d) / d else 1f
            val x = cx + dx * k * w * zoom
            val y = cy + dy * k * w * zoom
            val dia = Metrics.GRID_CENTER_D * w * size * zoom
            drawIcon(c, byUse[i].icon, x, y, dia, alpha)
            hits += Hit(x, y, dia / 2, byUse[i])
        }
    }

    /** The List view: A-Z rows of icon and name. */
    private fun drawList(c: Canvas, w: Float, h: Float, top: Float, bottom: Float, alpha: Float) {
        val pitch = Metrics.LIST_PITCH * w
        val d = Metrics.LIST_ICON_D * w
        text.textSize = Metrics.LIST_TEXT * w
        text.color = Color.argb((alpha * 255).toInt(), 255, 255, 255)
        val y0 = top + 0.2f * w - listScroll
        val maxText = w - Metrics.LIST_LABEL_X * w - 0.06f * w
        for ((i, app) in alphabetical.withIndex()) {
            val y = y0 + i * pitch
            if (y < top - pitch || y > bottom + pitch) continue
            // Rows shrink toward the screen's top and bottom edges, as on the watch.
            val edge = minOf(y - top, bottom - y) / (0.25f * w)
            val s = (0.6f + 0.4f * edge).coerceIn(0.6f, 1f)
            val x = Metrics.LIST_ICON_X * w
            drawIcon(c, app.icon, x, y, d * s, alpha * s)
            hits += Hit(w / 2, y, pitch / 2, app)
            text.textSize = Metrics.LIST_TEXT * w * s
            c.drawText(TextUtils.ellipsize(app.label, text, maxText, TextUtils.TruncateAt.END).toString(),
                Metrics.LIST_LABEL_X * w, y + text.textSize * 0.35f, text)
        }
    }

    /** "List View" under the grid, "Grid View" under the list. */
    private fun drawPill(c: Canvas, w: Float, bottom: Float, alpha: Float) {
        if (alpha <= 0.01f) { pill.setEmpty(); return }
        val ph = Metrics.PILL_H * w
        val pw = 0.44f * w
        pill.set(w / 2 - pw / 2, bottom - ph - 0.04f * w, w / 2 + pw / 2, bottom - 0.04f * w)
        fill.color = Color.argb((alpha * 230).toInt(), 44, 44, 46)
        c.drawRoundRect(pill, ph / 2, ph / 2, fill)
        text.textSize = 0.06f * w
        text.color = Color.argb((alpha * 255).toInt(), 255, 255, 255)
        val label = if (list.target >= 0.5f) "Grid View" else "List View"
        val tw = text.measureText(label)
        c.drawText(label, w / 2 - tw / 2, pill.centerY() + text.textSize * 0.35f, text)
    }

    private fun drawIcon(c: Canvas, b: android.graphics.Bitmap, x: Float, y: Float, d: Float, alpha: Float) {
        if (alpha <= 0.004f || d < 2f) return
        bitmapPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        rect.set(x - d / 2, y - d / 2, x + d / 2, y + d / 2)
        c.drawBitmap(b, null, rect, bitmapPaint)
    }

    // ---------------------------------------------------------------- touch

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var longPressed = false
    private var velocity: VelocityTracker? = null
    private var stackAtDown = 0f
    private val longPress = Runnable {
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (apps.target == 1f) hitAt(downX, downY)?.let(onLongPressApp)
        else if (onFace) onLongPressFace()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                dragging = false; longPressed = false
                velocity?.recycle(); velocity = VelocityTracker.obtain().apply { addMovement(e) }
                listVelocity = 0f
                stackAtDown = stackScroll.value
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (!dragging && hypot(e.x - downX, e.y - downY) > slop) {
                    dragging = true
                    removeCallbacks(longPress)
                    // Dragging the cluster opens the full grid, as turning the crown does on the watch.
                    if (apps.target == 1f && list.target == 0f && grid.target == 0f) grid.target = 1f
                }
                if (dragging && !longPressed) drag(e.x - lastX, e.y - lastY, e.y - downY)
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                velocity?.addMovement(e)
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                val vy = velocity?.yVelocity ?: 0f
                if (!longPressed) { if (dragging) release(vx, vy, e.y - downY) else tap(e.x, e.y) }
                dragging = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); dragging = false }
        }
        return true
    }

    private fun drag(dx: Float, dy: Float, totalDy: Float) {
        val w = width.toFloat()
        when {
            stackIn.target == 1f -> {
                val pitch = (Metrics.CARD_H + Metrics.CARD_GAP) * w
                stackScroll.snap((stackScroll.value - dy / pitch).coerceIn(-0.6f, stack.maxScroll + 0.3f))
            }
            apps.target == 1f && list.target == 1f -> listScroll -= dy
            apps.target == 1f -> { panX.snap(panX.value + dx); panY.snap(panY.value + dy) }
            else -> {
                // On the face: pulling up brings the stack along with the finger.
                if (totalDy < 0) stackIn.snap((-totalDy / (0.6f * height)).coerceIn(0f, 1f))
            }
        }
        invalidate()
    }

    private fun release(vx: Float, vy: Float, totalDy: Float) {
        val w = width.toFloat()
        when {
            stackIn.target == 1f -> {
                // Pulled down past the first card: back to the face. Otherwise settle on a card.
                if (stackScroll.value < -0.3f) { toFace(); return }
                val pitch = (Metrics.CARD_H + Metrics.CARD_GAP) * w
                stackScroll.target = (stackScroll.value - vy * 0.25f / pitch).roundToInt().toFloat().coerceIn(0f, stack.maxScroll)
            }
            apps.target == 1f && list.target == 1f -> listVelocity = -vy
            apps.target == 1f -> snapGrid(panX.value + vx * 0.3f, panY.value + vy * 0.3f)
            else -> {
                when {
                    totalDy < -0.12f * height || vy < -1500f -> openStack()
                    totalDy > 0.12f * height -> { stackIn.target = 0f; onPullDown() }
                    else -> stackIn.target = 0f
                }
            }
        }
    }

    /** Settles the grid with the app nearest the screen's centre in the middle, as the watch does. */
    private fun snapGrid(px: Float, py: Float) {
        val w = width.toFloat()
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in byUse.indices) {
            pointyCenter(gridHexes[i], Metrics.GRID_PITCH * w, tmp)
            val d = hypot(tmp[0] + px, tmp[1] + py)
            if (d < bestD) { bestD = d; best = i }
        }
        if (byUse.isEmpty()) return
        pointyCenter(gridHexes[best], Metrics.GRID_PITCH * w, tmp)
        panX.target = -tmp[0]
        panY.target = -tmp[1]
    }

    private fun stepListFling(dt: Float) {
        val w = width.toFloat()
        val maxScroll = max(0f, alphabetical.size * Metrics.LIST_PITCH * w - (height - topInset - bottomInset) * 0.6f)
        if (abs(listVelocity) > 1f) {
            listScroll += listVelocity * dt
            listVelocity *= (1f - 3.5f * dt).coerceAtLeast(0f)
        }
        if (!dragging) {
            // Ease back inside the ends.
            if (listScroll < 0f) { listScroll *= (1f - 12f * dt).coerceAtLeast(0f); listVelocity = 0f }
            if (listScroll > maxScroll) { listScroll = maxScroll + (listScroll - maxScroll) * (1f - 12f * dt).coerceAtLeast(0f); listVelocity = 0f }
        }
    }

    private fun tap(x: Float, y: Float) {
        if (stackIn.target == 1f) {
            stack.appRects.lastOrNull { it.first.contains(x, y) }?.let { (r, app) -> onLaunch(app, RectF(r)); return }
            toFace(); return
        }
        if (apps.target == 1f) {
            if (!pill.isEmpty && pill.contains(x, y)) {
                list.target = if (list.target >= 0.5f) 0f else 1f
                if (list.target == 0f) grid.target = 1f
                preferListChanged(list.target == 1f)
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                invalidate(); return
            }
            val app = hitAt(x, y) ?: return
            val hit = hits.last { it.app === app }
            onLaunch(app, RectF(hit.x - hit.r, hit.y - hit.r, hit.x + hit.r, hit.y + hit.r))
            return
        }
        // On the face: a favourite opens its app; anywhere else opens the apps.
        for ((i, r) in face.favoriteRects.withIndex()) {
            val app = face.favorites.getOrNull(i) ?: continue
            if (!r.isEmpty && r.contains(x, y)) { onLaunch(app, RectF(r)); return }
        }
        openApps()
    }

    /** Set by the activity to remember the user's Grid/List choice. */
    var preferListChanged: (Boolean) -> Unit = {}

    private fun hitAt(x: Float, y: Float): App? = hits.lastOrNull { hypot(x - it.x, y - it.y) <= it.r }?.app

    private companion object {
        /** The cluster's ring, clockwise from the top, on a flat-topped lattice. */
        val FOCUS_RING_ORDER = listOf(Hex(0, -1), Hex(1, -1), Hex(1, 0), Hex(0, 1), Hex(-1, 1), Hex(-1, 0))
    }
}
