package dev.watchreborn.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.text.DateFormat
import java.util.Calendar

/** Live device state displayed in the watchOS-style Control Center. */
data class ControlState(
    val wifiOn: Boolean = false,
    val bluetoothOn: Boolean = false,
    val torchOn: Boolean = false,
    /** 0 = silent, 1 = vibrate, 2 = normal (AudioManager.RINGER_MODE_*). */
    val ringerMode: Int = 2,
    val autoFolders: Boolean = true,
    val notifCount: Int = 0,
    /** Media volume 0..1. */
    val volume: Float = 0.5f,
    /** Screen flashlight overlay when no hardware torch exists. */
    val screenFlashlight: Boolean = false,
)

enum class ControlAction {
    WIFI, BLUETOOTH, TORCH, RINGER, NOTIFICATIONS, LOCK, FOLDERS, VOLUME_DOWN, VOLUME_UP, SET_HOME
}

/**
 * watchOS 27-inspired Control Center: 2-column stadium platters for quick toggles, a live media
 * volume slider bar, and an optional full-screen white flashlight mode.
 */
class ControlCenter {
    var accent = Color.rgb(166, 227, 110)
    var state = ControlState()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val rect = RectF()
    private val path = Path()

    val actionRects = ArrayList<Pair<RectF, ControlAction>>()
    val volumeBarRect = RectF()

    fun draw(c: Canvas, w: Float, h: Float, top: Float, bottom: Float, now: Calendar, battery: Int, charging: Boolean, t: Float) {
        if (t <= 0.001f) return
        val a = (t * 255).toInt().coerceIn(0, 255)
        actionRects.clear()
        volumeBarRect.setEmpty()

        if (state.screenFlashlight && t > 0.5f) {
            fill.color = Color.argb(a, 255, 255, 255)
            c.drawRect(0f, 0f, w, h, fill)
            text.typeface = medium
            text.textSize = 0.045f * w
            text.textAlign = Paint.Align.CENTER
            text.color = Color.argb(a, 120, 120, 128)
            c.drawText("Tap anywhere to turn off Flashlight", w / 2, bottom - 0.1f * w, text)
            actionRects += RectF(0f, 0f, w, h) to ControlAction.TORCH
            return
        }

        val slideY = -(1f - t) * 0.25f * h
        val padX = 0.06f * w
        val gap = 0.035f * w
        val colW = (w - 2 * padX - gap) / 2f
        val tileH = 0.19f * w

        // Header: Battery % on left, Control Center title, Time on right.
        val headerY = top + 0.08f * w + slideY
        text.typeface = bold
        text.textSize = 0.052f * w
        text.textAlign = Paint.Align.LEFT
        text.color = withAlpha(accent, a)
        val batText = if (battery >= 0) "$battery%${if (charging) " ⚡" else ""}" else "Control"
        c.drawText(batText, padX, headerY, text)

        text.textAlign = Paint.Align.RIGHT
        text.color = withAlpha(Color.WHITE, a)
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(now.time).substringBefore(' ')
        c.drawText(time, w - padX, headerY, text)

        // 2x3 grid of stadium toggles
        val startY = headerY + 0.06f * w
        val tiles = listOf(
            TileSpec(
                action = ControlAction.WIFI,
                title = "Wi-Fi",
                sub = if (state.wifiOn) "On" else "Off",
                active = state.wifiOn,
                activeColor = Color.rgb(10, 132, 255),
            ),
            TileSpec(
                action = ControlAction.BLUETOOTH,
                title = "Bluetooth",
                sub = if (state.bluetoothOn) "On" else "Off",
                active = state.bluetoothOn,
                activeColor = Color.rgb(10, 132, 255),
            ),
            TileSpec(
                action = ControlAction.TORCH,
                title = "Flashlight",
                sub = if (state.torchOn || state.screenFlashlight) "On" else "Off",
                active = state.torchOn || state.screenFlashlight,
                activeColor = Color.rgb(255, 159, 10),
            ),
            TileSpec(
                action = ControlAction.RINGER,
                title = when (state.ringerMode) { 0 -> "Silent"; 1 -> "Vibrate"; else -> "Ring" },
                sub = "Sound",
                active = state.ringerMode != 2,
                activeColor = if (state.ringerMode == 0) Color.rgb(255, 69, 58) else Color.rgb(255, 159, 10),
            ),
            TileSpec(
                action = ControlAction.FOLDERS,
                title = "Folders",
                sub = if (state.autoFolders) "Auto" else "Off",
                active = state.autoFolders,
                activeColor = accent,
            ),
            TileSpec(
                action = ControlAction.LOCK,
                title = "Lock",
                sub = "Screen",
                active = false,
                activeColor = Color.rgb(100, 210, 255),
            ),
        )

        tiles.forEachIndexed { idx, spec ->
            val col = idx % 2
            val row = idx / 2
            val x = padX + col * (colW + gap)
            val y = startY + row * (tileH + gap)
            drawTile(c, x, y, colW, tileH, spec, a, w)
        }

        // Row 4: Full-width Volume platter
        val volY = startY + 3 * (tileH + gap)
        val fullW = w - 2 * padX
        drawVolumeBar(c, padX, volY, fullW, tileH * 0.92f, a, w)

        // Row 5: Full-width Notifications button
        val notifY = volY + tileH * 0.92f + gap
        val notifRect = RectF(padX, notifY, padX + fullW, notifY + tileH * 0.85f)
        fill.color = withAlpha(Color.rgb(36, 36, 40), a)
        c.drawRoundRect(notifRect, notifRect.height() / 2, notifRect.height() / 2, fill)
        text.typeface = bold
        text.textSize = 0.044f * w
        text.textAlign = Paint.Align.CENTER
        text.color = withAlpha(Color.WHITE, a)
        val notifLabel = if (state.notifCount > 0) "Notifications (${state.notifCount} active)" else "Open Notifications Shade"
        c.drawText(notifLabel, w / 2, notifRect.centerY() + text.textSize * 0.35f, text)
        if (state.notifCount > 0) {
            fill.color = withAlpha(Color.rgb(255, 69, 58), a)
            c.drawCircle(notifRect.left + 0.07f * w, notifRect.centerY(), 0.015f * w, fill)
        }
        actionRects += notifRect to ControlAction.NOTIFICATIONS

        // Row 6: Full-width Set as Default Launcher button
        val homeY = notifRect.bottom + gap
        val homeRect = RectF(padX, homeY, padX + fullW, homeY + tileH * 0.85f)
        fill.color = withAlpha(accent, a)
        c.drawRoundRect(homeRect, homeRect.height() / 2, homeRect.height() / 2, fill)
        text.typeface = bold
        text.textSize = 0.042f * w
        text.textAlign = Paint.Align.CENTER
        text.color = withAlpha(Color.BLACK, a)
        c.drawText("🏠 Set as Default Launcher", w / 2, homeRect.centerY() + text.textSize * 0.35f, text)
        actionRects += homeRect to ControlAction.SET_HOME
    }

    private class TileSpec(
        val action: ControlAction,
        val title: String,
        val sub: String,
        val active: Boolean,
        val activeColor: Int,
    )

    private fun drawTile(c: Canvas, x: Float, y: Float, tw: Float, th: Float, spec: TileSpec, a: Int, w: Float) {
        val r = RectF(x, y, x + tw, y + th)
        fill.color = withAlpha(if (spec.active) spec.activeColor else Color.rgb(38, 38, 42), a)
        c.drawRoundRect(r, th / 2, th / 2, fill)

        val iconCx = x + th * 0.52f
        val iconCy = y + th / 2
        val fg = if (spec.active && isBright(spec.activeColor)) Color.BLACK else Color.WHITE
        drawGlyph(c, spec.action, iconCx, iconCy, th * 0.24f, withAlpha(fg, a), w)

        val textX = x + th * 0.92f
        text.textAlign = Paint.Align.LEFT
        text.typeface = bold
        text.textSize = 0.042f * w
        text.color = withAlpha(fg, a)
        c.drawText(spec.title, textX, y + th * 0.46f, text)

        text.typeface = medium
        text.textSize = 0.033f * w
        text.color = withAlpha(if (fg == Color.BLACK) Color.rgb(30, 30, 30) else Color.rgb(175, 175, 180), a)
        c.drawText(spec.sub, textX, y + th * 0.74f, text)

        actionRects += r to spec.action
    }

    private fun drawVolumeBar(c: Canvas, x: Float, y: Float, bw: Float, bh: Float, a: Int, w: Float) {
        volumeBarRect.set(x, y, x + bw, y + bh)
        fill.color = withAlpha(Color.rgb(38, 38, 42), a)
        c.drawRoundRect(volumeBarRect, bh / 2, bh / 2, fill)

        // Inner progress fill
        val fillW = (bw * state.volume.coerceIn(0.08f, 1f))
        val prog = RectF(x, y, x + fillW, y + bh)
        c.save()
        path.reset()
        path.addRoundRect(volumeBarRect, bh / 2, bh / 2, Path.Direction.CW)
        c.clipPath(path)
        fill.color = withAlpha(mix(accent, Color.BLACK, 0.35f), a)
        c.drawRect(prog, fill)
        c.restore()

        text.typeface = bold
        text.textSize = 0.055f * w
        text.textAlign = Paint.Align.CENTER
        text.color = withAlpha(Color.WHITE, a)
        c.drawText("−", x + bh * 0.55f, y + bh * 0.62f, text)
        c.drawText("+", x + bw - bh * 0.55f, y + bh * 0.62f, text)

        text.textSize = 0.038f * w
        c.drawText("VOLUME  ${(state.volume * 100).toInt()}%", x + bw / 2, y + bh * 0.58f, text)

        actionRects += RectF(x, y, x + bh * 1.1f, y + bh) to ControlAction.VOLUME_DOWN
        actionRects += RectF(x + bw - bh * 1.1f, y, x + bw, y + bh) to ControlAction.VOLUME_UP
    }

    private fun drawGlyph(c: Canvas, action: ControlAction, cx: Float, cy: Float, r: Float, color: Int, w: Float) {
        stroke.color = color
        stroke.strokeWidth = 0.007f * w
        fill.color = color
        when (action) {
            ControlAction.WIFI -> {
                for (i in 1..2) {
                    val ar = r * (0.45f * i)
                    rect.set(cx - ar, cy - ar + r * 0.3f, cx + ar, cy + ar + r * 0.3f)
                    c.drawArc(rect, -135f, 90f, false, stroke)
                }
                c.drawCircle(cx, cy + r * 0.35f, r * 0.14f, fill)
            }
            ControlAction.BLUETOOTH -> {
                path.reset()
                path.moveTo(cx - r * 0.35f, cy - r * 0.45f)
                path.lineTo(cx + r * 0.4f, cy + r * 0.35f)
                path.lineTo(cx, cy + r * 0.75f)
                path.lineTo(cx, cy - r * 0.75f)
                path.lineTo(cx + r * 0.4f, cy - r * 0.35f)
                path.lineTo(cx - r * 0.35f, cy + r * 0.45f)
                c.drawPath(path, stroke)
            }
            ControlAction.TORCH -> {
                rect.set(cx - r * 0.28f, cy - r * 0.15f, cx + r * 0.28f, cy + r * 0.65f)
                c.drawRoundRect(rect, r * 0.12f, r * 0.12f, fill)
                rect.set(cx - r * 0.42f, cy - r * 0.6f, cx + r * 0.42f, cy - r * 0.15f)
                c.drawRoundRect(rect, r * 0.1f, r * 0.1f, fill)
            }
            ControlAction.RINGER -> {
                path.reset()
                path.moveTo(cx - r * 0.5f, cy + r * 0.35f)
                path.cubicTo(cx - r * 0.4f, cy - r * 0.6f, cx + r * 0.4f, cy - r * 0.6f, cx + r * 0.5f, cy + r * 0.35f)
                path.close()
                c.drawPath(path, fill)
                c.drawCircle(cx, cy + r * 0.52f, r * 0.14f, fill)
            }
            ControlAction.FOLDERS -> {
                val dr = r * 0.26f
                c.drawCircle(cx, cy, dr, fill)
                for (i in 0 until 6) {
                    val ang = Math.toRadians((i * 60).toDouble())
                    c.drawCircle(
                        cx + (kotlin.math.cos(ang) * r * 0.65f).toFloat(),
                        cy + (kotlin.math.sin(ang) * r * 0.65f).toFloat(),
                        dr * 0.8f,
                        fill,
                    )
                }
            }
            ControlAction.LOCK -> {
                rect.set(cx - r * 0.48f, cy - r * 0.05f, cx + r * 0.48f, cy + r * 0.65f)
                c.drawRoundRect(rect, r * 0.15f, r * 0.15f, fill)
                rect.set(cx - r * 0.3f, cy - r * 0.58f, cx + r * 0.3f, cy + r * 0.05f)
                c.drawArc(rect, -180f, 180f, false, stroke)
            }
            else -> {}
        }
    }

    private fun isBright(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 > 165
    private fun mix(c1: Int, c2: Int, t: Float) = Color.rgb(
        (Color.red(c1) * (1 - t) + Color.red(c2) * t).toInt(),
        (Color.green(c1) * (1 - t) + Color.green(c2) * t).toInt(),
        (Color.blue(c1) * (1 - t) + Color.blue(c2) * t).toInt(),
    )
    private fun withAlpha(color: Int, a: Int) = Color.argb(
        Color.alpha(color) * a.coerceIn(0, 255) / 255,
        Color.red(color), Color.green(color), Color.blue(color),
    )
}
