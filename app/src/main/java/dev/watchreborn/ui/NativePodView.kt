package dev.watchreborn.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Pod content for a built-in native island (3.6.0): an enlarged
 * live rendering of the widget (drawn by the WatchView, which owns
 * the data) over rows of action chips routed through the same
 * action ids the old widget pod used, so behaviour matches the
 * retired bubble pods (timer presets, media transport, calendar /
 * alarm intents, battery settings). Refreshes twice a second
 * while attached so a running timer counts down in the pod.
 */
class NativePodView(
    context: Context,
    private val watch: WatchView,
    val kind: NativeKind,
) : View(context) {

    /** Chip action ids (WatchView.handleWidgetPodAction's vocabulary). */
    var onAction: (String) -> Unit = {}

    // A getter so the Vitals "Enable step count" chip disappears as
    // soon as the grant lands (3.7.3), without reopening the pod.
    private val chipRows: List<List<Pair<String, String>>> get() = when (kind) {
        NativeKind.CLOCK -> listOf(listOf("⏰  Alarms" to "CAL_ALARMS"))
        NativeKind.CALENDAR -> listOf(
            listOf("Open Calendar" to "CAL_OPEN", "⏰  Alarms" to "CAL_ALARMS"),
        )
        NativeKind.TIMER -> listOf(
            listOf(
                "1 min" to "TIMER_60", "3 min" to "TIMER_180",
                "5 min" to "TIMER_300", "10 min" to "TIMER_600",
            ),
            listOf("⏯  Start / Pause" to "TIMER_TOGGLE", "↺  Reset" to "TIMER_RESET"),
        )
        NativeKind.VITALS -> if (context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            listOf(listOf("🔋  Battery settings" to "VITALS_BATTERY"))
        } else {
            listOf(listOf("👟  Enable step count" to "VITALS_STEPS", "🔋  Battery" to "VITALS_BATTERY"))
        }
        NativeKind.NOW_PLAYING -> listOf(
            listOf(
                "⏮" to "MEDIA_PREV", "⏯" to "MEDIA_PLAY",
                "⏭" to "MEDIA_NEXT", "Open app" to "MEDIA_OPEN",
            ),
        )
    }

    private val chipRects = ArrayList<Pair<RectF, String>>()
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 500L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(ticker, 500L)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private fun dpv(v: Float): Float = v * resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val rowH = dpv(40f)
        val gap = dpv(8f)
        val rowsH = chipRows.size * rowH + (chipRows.size - 1).coerceAtLeast(0) * gap + dpv(10f)
        val content = RectF(0f, 0f, w, h - rowsH)
        watch.drawNativeIslandContent(c, kind, content, 255, true)

        chipRects.clear()
        var y = h - rowsH + dpv(2f)
        for (row in chipRows) {
            val n = row.size
            val chipW = (w - dpv(24f) - (n - 1) * gap) / n
            var x = dpv(12f)
            for ((label, action) in row) {
                val r = RectF(x, y, x + chipW, y + rowH)
                chipPaint.color = Color.rgb(52, 54, 62)
                c.drawRoundRect(r, dpv(10f), dpv(10f), chipPaint)
                chipText.textSize = dpv(13f)
                val baseline = r.centerY() - (chipText.ascent() + chipText.descent()) / 2f
                c.drawText(label, r.centerX(), baseline, chipText)
                chipRects += r to action
                x += chipW + gap
            }
            y += rowH + gap
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            for ((r, action) in chipRects) {
                if (r.contains(e.x, e.y)) {
                    performClick()
                    onAction(action)
                    invalidate()
                    return true
                }
            }
        }
        // Consume everything: the pod card must not fall through
        // to the scrim's tap-to-close.
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
