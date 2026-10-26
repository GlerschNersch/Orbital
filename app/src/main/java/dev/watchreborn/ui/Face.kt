package dev.watchreborn.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import dev.watchreborn.apps.App
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** What the face shows besides the time. */
class FaceInfo(
    val battery: Int = -1,
    val charging: Boolean = false,
    /** Next alarm's trigger time (ms), or null. */
    val nextAlarm: Long? = null,
)

/**
 * Our own digital face in the spirit of a modular watch face (none of Apple's faces, fonts or art):
 * three small complications on top, a large time with the seconds in the accent colour, an info
 * line, and three complications at the bottom that open your most-used apps. A ring of minute
 * ticks runs around the screen's edge, lit up to the current second.
 */
class Face {
    var accent = Color.rgb(166, 227, 110)
    /** The phone's 12/24-hour setting, for the timeline's hour labels. */
    var is24Hour = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val rect = RectF()
    private val edge = Path()
    private val measure = PathMeasure()
    private val pos = FloatArray(2)
    private val tan = FloatArray(2)

    /** Where the top complications (0=Battery, 1=Date, 2=Alarm) were drawn, for taps. */
    val topRects = Array(3) { RectF() }

    /** Where the bottom complications were drawn, for taps (null where there's no app). */
    val favoriteRects = Array(3) { RectF() }
    var favorites: List<App?> = emptyList()

    fun draw(c: Canvas, w: Float, h: Float, top: Float, bottom: Float, now: Calendar, info: FaceInfo, alpha: Float) {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        if (a == 0) return
        drawTicks(c, w, h, top, bottom, now, a)

        // Top row: battery ring, date, next alarm.
        val small = 0.17f * w
        val rowY = top + 0.16f * w
        val xs = floatArrayOf(0.2f * w, 0.5f * w, 0.8f * w)
        for (i in 0 until 3) topRects[i].set(xs[i] - small / 2, rowY - small / 2, xs[i] + small / 2, rowY + small / 2)
        ring(c, xs[0], rowY, small, if (info.battery >= 0) info.battery / 100f else 0f, a)
        label(c, xs[0], rowY, small, if (info.battery >= 0) "${info.battery}" else "--", if (info.charging) "⚡" else "%", a)
        disc(c, xs[1], rowY, small, a)
        text.typeface = bold
        text.color = withAlpha(accent, a); text.textSize = small * 0.2f
        c.drawText(SimpleDateFormat("EEE", Locale.getDefault()).format(now.time).uppercase(), xs[1], rowY - small * 0.08f, text)
        text.color = withAlpha(Color.WHITE, a); text.textSize = small * 0.38f
        c.drawText("${now.get(Calendar.DAY_OF_MONTH)}", xs[1], rowY + small * 0.3f, text)
        disc(c, xs[2], rowY, small, a)
        val alarm = info.nextAlarm?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(it).replace(" ", "\n") }
        bell(c, xs[2], rowY - small * 0.12f, small * 0.22f, a)
        text.typeface = medium; text.color = withAlpha(Color.WHITE, a); text.textSize = small * 0.17f
        c.drawText(alarm?.lineSequence()?.first() ?: "Off", xs[2], rowY + small * 0.28f, text)

        // The time: hours and minutes in white, seconds in the accent colour.
        // On a tall phone the face spreads out: time in the upper third, timeline in the middle.
        val spare = (bottom - top - 1.2f * w).coerceAtLeast(0f)
        val timeY = top + 0.5f * w + spare * 0.22f
        val hm = DateFormat.getTimeInstance(DateFormat.SHORT).format(now.time).substringBefore(' ')
        val ss = ":%02d".format(now.get(Calendar.SECOND))
        text.typeface = condensed
        text.textSize = 0.27f * w
        text.textAlign = Paint.Align.LEFT
        var wHm = text.measureText(hm)
        var wSs = text.measureText(ss)
        // Shrink to fit: with a two-digit hour ("12:59:59") the full-size time
        // is wider than the screen and the seconds clipped at the right edge.
        val maxTimeW = 0.92f * w
        if (wHm + wSs > maxTimeW) {
            text.textSize *= maxTimeW / (wHm + wSs)
            wHm = text.measureText(hm)
            wSs = text.measureText(ss)
        }
        var x = (w - wHm - wSs) / 2
        text.color = withAlpha(Color.WHITE, a); c.drawText(hm, x, timeY, text)
        x += wHm
        text.color = withAlpha(accent, a); c.drawText(ss, x, timeY, text)
        text.textAlign = Paint.Align.CENTER

        // Info line: the next alarm, or the full date.
        text.typeface = medium; text.textSize = 0.065f * w
        val dayLine = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now.time)
        if (info.nextAlarm != null) {
            text.color = withAlpha(accent, a)
            c.drawText("ALARM " + DateFormat.getTimeInstance(DateFormat.SHORT).format(info.nextAlarm), w / 2, timeY + 0.1f * w, text)
            text.color = withAlpha(Color.WHITE, a)
            c.drawText(dayLine, w / 2, timeY + 0.18f * w, text)
        } else {
            text.color = withAlpha(Color.WHITE, a)
            c.drawText(dayLine, w / 2, timeY + 0.1f * w, text)
        }

        if (spare > 0.3f * w) drawTimeline(c, w, timeY + 0.3f * w + spare * 0.3f, now, info, a)

        // Bottom row: the three apps you use most.
        val big = 0.2f * w
        val bottomY = bottom - 0.17f * w
        for (i in 0 until 3) {
            val cx = xs[i]
            favoriteRects[i].set(cx - big / 2, bottomY - big / 2, cx + big / 2, bottomY + big / 2)
            val app = favorites.getOrNull(i)
            disc(c, cx, bottomY, big, a)
            if (app != null) icon(c, app.icon, cx, bottomY, big * 0.78f, a)
            else favoriteRects[i].setEmpty()
        }
    }

    private fun drawTicks(c: Canvas, w: Float, h: Float, top: Float, bottom: Float, now: Calendar, a: Int) {
        val inset = 0.03f * w
        rect.set(inset, top - 0.05f * w + inset, w - inset, bottom + 0.05f * w - inset)
        val r = 0.16f * w
        edge.reset()
        edge.moveTo(rect.centerX(), rect.top)
        edge.lineTo(rect.right - r, rect.top)
        edge.arcTo(RectF(rect.right - 2 * r, rect.top, rect.right, rect.top + 2 * r), -90f, 90f)
        edge.lineTo(rect.right, rect.bottom - r)
        edge.arcTo(RectF(rect.right - 2 * r, rect.bottom - 2 * r, rect.right, rect.bottom), 0f, 90f)
        edge.lineTo(rect.left + r, rect.bottom)
        edge.arcTo(RectF(rect.left, rect.bottom - 2 * r, rect.left + 2 * r, rect.bottom), 90f, 90f)
        edge.lineTo(rect.left, rect.top + r)
        edge.arcTo(RectF(rect.left, rect.top, rect.left + 2 * r, rect.top + 2 * r), 180f, 90f)
        edge.close()
        measure.setPath(edge, false)
        val len = measure.length
        val sec = now.get(Calendar.SECOND)
        stroke.strokeWidth = 0.008f * w
        for (i in 0 until 60) {
            measure.getPosTan(len * i / 60f, pos, tan)
            val nx = -tan[1]; val ny = tan[0]
            val l = if (i % 5 == 0) 0.035f * w else 0.018f * w
            stroke.color = withAlpha(if (i <= sec) accent else Color.rgb(60, 66, 60), a)
            c.drawLine(pos[0], pos[1], pos[0] + nx * l, pos[1] + ny * l, stroke)
        }
    }

    private fun drawTimeline(c: Canvas, w: Float, y: Float, now: Calendar, info: FaceInfo, a: Int) {
        val left = 0.1f * w
        val right = 0.9f * w
        val hourW = (right - left) / 6
        val start = (now.clone() as Calendar).apply { set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        text.typeface = medium; text.textSize = 0.04f * w; text.color = withAlpha(Color.rgb(170, 170, 170), a)
        val fmt = SimpleDateFormat(if (is24Hour) "HH" else "h a", Locale.getDefault())
        stroke.strokeWidth = 0.004f * w
        for (i in 0..6) {
            val x = left + i * hourW
            stroke.color = withAlpha(Color.rgb(90, 90, 90), a)
            c.drawLine(x, y - 0.03f * w, x, y + 0.03f * w, stroke)
            if (i in 1..5) {
                val t = (start.clone() as Calendar).apply { add(Calendar.HOUR_OF_DAY, i) }
                c.drawText(fmt.format(t.time), x, y - 0.05f * w, text)
            }
        }
        val frac = now.get(Calendar.MINUTE) / 60f
        fill.color = withAlpha(Color.rgb(90, 90, 90), a)
        rect.set(left, y - 0.008f * w, right, y + 0.008f * w)
        c.drawRoundRect(rect, 0.008f * w, 0.008f * w, fill)
        fill.color = withAlpha(Color.WHITE, a)
        rect.set(left, y - 0.012f * w, left + hourW * frac, y + 0.012f * w)
        c.drawRoundRect(rect, 0.012f * w, 0.012f * w, fill)
        info.nextAlarm?.let { at ->
            val hours = (at - start.timeInMillis) / 3_600_000f
            if (hours in 0f..6f) {
                fill.color = withAlpha(accent, a)
                c.drawCircle(left + hours * hourW, y, 0.02f * w, fill)
            }
        }
    }

    private fun disc(c: Canvas, x: Float, y: Float, d: Float, a: Int) {
        fill.color = withAlpha(Color.rgb(28, 30, 28), a)
        c.drawCircle(x, y, d / 2, fill)
    }

    private fun ring(c: Canvas, x: Float, y: Float, d: Float, value: Float, a: Int) {
        disc(c, x, y, d, a)
        stroke.strokeWidth = d * 0.09f
        rect.set(x - d * 0.4f, y - d * 0.4f, x + d * 0.4f, y + d * 0.4f)
        stroke.color = withAlpha(Color.rgb(50, 60, 45), a)
        c.drawArc(rect, 0f, 360f, false, stroke)
        stroke.color = withAlpha(accent, a)
        c.drawArc(rect, -90f, 360f * value.coerceIn(0f, 1f), false, stroke)
    }

    private fun label(c: Canvas, x: Float, y: Float, d: Float, big: String, small: String, a: Int) {
        text.typeface = bold; text.color = withAlpha(Color.WHITE, a)
        text.textSize = d * 0.3f
        c.drawText(big, x, y + d * 0.06f, text)
        text.textSize = d * 0.15f; text.color = withAlpha(accent, a)
        c.drawText(small, x, y + d * 0.27f, text)
    }

    private fun bell(c: Canvas, x: Float, y: Float, s: Float, a: Int) {
        fill.color = withAlpha(accent, a)
        val p = Path()
        p.moveTo(x - s * 0.5f, y + s * 0.35f)
        p.cubicTo(x - s * 0.4f, y - s * 0.6f, x + s * 0.4f, y - s * 0.6f, x + s * 0.5f, y + s * 0.35f)
        p.close()
        c.drawPath(p, fill)
        c.drawCircle(x, y + s * 0.48f, s * 0.12f, fill)
    }

    private fun icon(c: Canvas, b: Bitmap, x: Float, y: Float, d: Float, a: Int) {
        bitmapPaint.alpha = a
        rect.set(x - d / 2, y - d / 2, x + d / 2, y + d / 2)
        c.drawBitmap(b, null, rect, bitmapPaint)
    }

    private fun withAlpha(color: Int, a: Int) = Color.argb(Color.alpha(color) * a / 255, Color.red(color), Color.green(color), Color.blue(color))
}
