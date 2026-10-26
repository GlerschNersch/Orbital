package dev.watchreborn.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import dev.watchreborn.apps.App
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

enum class CardAction {
    MEDIA_PREV, MEDIA_PLAY_PAUSE, MEDIA_NEXT, MEDIA_OPEN,
    OPEN_ALARMS, OPEN_BATTERY, OPEN_CALENDAR,
}

/** One Smart Stack card: a tinted platter with interactive controls, rings, or recent apps. */
class Card(
    val id: String,
    val tint: Int,
    val title: String,
    val big: String,
    val detail: String = "",
    val apps: List<App> = emptyList(),
    val art: Bitmap? = null,
    val mediaPlaying: Boolean? = null,
    /** Optional 3 activity/system rings (0..1 each). */
    val rings: FloatArray? = null,
    val tapAction: CardAction? = null,
    val pinned: Boolean = false,
)

/**
 * The Smart Stack (turning the crown / swiping up on the face): date and time header, followed by
 * interactive live cards (Now Playing with transport controls, Alarm, Battery & System rings,
 * Activity, and Recent Apps).
 */
class Stack {
    var accent = Color.rgb(166, 227, 110)
    var cards: List<Card> = emptyList()

    /** Scroll in cards (0 = the first card under the header). */
    var scroll = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val rect = RectF()
    private val clipPath = Path()

    /** Hit targets populated each frame. */
    val appRects = ArrayList<Pair<RectF, App>>()
    val actionRects = ArrayList<Pair<RectF, CardAction>>()
    val cardRects = ArrayList<Pair<RectF, Card>>()

    fun draw(c: Canvas, w: Float, h: Float, top: Float, bottom: Float, now: Calendar, t: Float) {
        if (t <= 0f) return
        val a = (t * 255).toInt().coerceIn(0, 255)
        appRects.clear()
        actionRects.clear()
        cardRects.clear()
        val compact = scroll.coerceIn(0f, 1f)

        // Header: date on the left, a large time on the right; one line once scrolled.
        val headerH = lerp(0.42f * w, 0.12f * w, compact)
        val headerTop = top + (1 - t) * 0.3f * w
        text.typeface = bold
        text.textAlign = Paint.Align.LEFT
        text.color = withAlpha(accent, a)
        val date = SimpleDateFormat("EEE MMM d", Locale.getDefault()).format(now.time).uppercase()
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(now.time).substringBefore(' ')
        if (compact < 1f) {
            val fa = ((1 - compact) * a).toInt()
            text.textSize = 0.1f * w
            text.color = withAlpha(accent, fa)
            date.split(' ').forEachIndexed { i, part -> c.drawText(part, 0.09f * w, headerTop + 0.14f * w + i * 0.11f * w, text) }
            text.typeface = condensed
            text.textSize = 0.25f * w
            text.textAlign = Paint.Align.RIGHT
            text.color = withAlpha(Color.WHITE, fa)
            c.drawText(time, 0.92f * w, headerTop + 0.3f * w, text)
        }
        if (compact > 0f) {
            val fa = (compact * a).toInt()
            text.typeface = bold; text.textSize = 0.06f * w
            text.textAlign = Paint.Align.LEFT; text.color = withAlpha(Color.WHITE, fa)
            c.drawText(SimpleDateFormat("EEE d", Locale.getDefault()).format(now.time).uppercase(), 0.1f * w, headerTop + 0.08f * w, text)
            text.textAlign = Paint.Align.RIGHT
            c.drawText(time, 0.9f * w, headerTop + 0.08f * w, text)
        }

        // Cards: front one under the header, later ones following; past ones tuck under the header.
        val cardW = Metrics.CARD_W * w
        val cardH = Metrics.CARD_H * w
        val pitch = cardH + Metrics.CARD_GAP * w
        val firstTop = headerTop + headerH + 0.03f * w + (1 - t) * 0.4f * w
        for (i in cards.indices.reversed()) {
            val pos = i - scroll
            var y = firstTop + pos * pitch
            var scale = 1f
            var alpha = 1f
            if (pos < 0f) {
                val k = (-pos).coerceIn(0f, 1f)
                y = firstTop - k * 0.12f * w
                scale = 1f - 0.12f * k
                alpha = 1f - k
            }
            if (y > bottom + pitch || alpha <= 0f) continue
            drawCard(c, cards[i], w / 2, y, cardW * scale, cardH * scale, (alpha * a).toInt(), w)
        }
    }

    private fun drawCard(c: Canvas, card: Card, cx: Float, y: Float, cw: Float, ch: Float, a: Int, w: Float) {
        val cardBounds = RectF(cx - cw / 2, y, cx + cw / 2, y + ch)
        cardRects += cardBounds to card
        fill.color = withAlpha(mix(card.tint, Color.BLACK, 0.58f), a)
        c.drawRoundRect(cardBounds, Metrics.CARD_R * w, Metrics.CARD_R * w, fill)

        val pad = 0.05f * w
        text.textAlign = Paint.Align.LEFT
        text.typeface = bold
        text.textSize = 0.052f * w
        text.color = withAlpha(card.tint, a)
        val titleStr = if (card.pinned) "📌 ${card.title.uppercase()}" else card.title.uppercase()
        c.drawText(titleStr, cardBounds.left + pad, y + pad + 0.048f * w, text)

        // 1. Recent apps row card
        if (card.apps.isNotEmpty()) {
            val d = 0.15f * w
            val gap = (cw - 2 * pad - 4 * d) / 3
            card.apps.take(4).forEachIndexed { i, app ->
                val x = cardBounds.left + pad + i * (d + gap)
                val r = RectF(x, y + ch - pad - d, x + d, y + ch - pad)
                bitmapPaint.alpha = a
                c.drawBitmap(app.icon, null, r, bitmapPaint)
                appRects += r to app
            }
            return
        }

        // 2. Now Playing media card with album art & transport controls
        if (card.mediaPlaying != null) {
            var textLeft = cardBounds.left + pad
            if (card.art != null) {
                val artSize = ch * 0.52f
                val artRect = RectF(textLeft, y + ch * 0.34f, textLeft + artSize, y + ch * 0.34f + artSize)
                c.save()
                clipPath.reset()
                clipPath.addRoundRect(artRect, 0.025f * w, 0.025f * w, Path.Direction.CW)
                c.clipPath(clipPath)
                bitmapPaint.alpha = a
                c.drawBitmap(card.art, null, artRect, bitmapPaint)
                c.restore()
                textLeft += artSize + 0.035f * w
            }
            val btnR = ch * 0.145f
            val controlsLeft = cardBounds.right - pad - btnR * 5.8f
            val availW = (controlsLeft - textLeft - 0.02f * w).coerceAtLeast(0.15f * w)

            text.typeface = bold
            text.textSize = 0.052f * w
            text.color = withAlpha(Color.WHITE, a)
            val ellipsizedTitle = TextUtils.ellipsize(card.big, android.text.TextPaint(text), availW, TextUtils.TruncateAt.END).toString()
            c.drawText(ellipsizedTitle, textLeft, y + ch * 0.58f, text)

            if (card.detail.isNotEmpty()) {
                text.typeface = Typeface.DEFAULT
                text.textSize = 0.040f * w
                text.color = withAlpha(Color.rgb(200, 200, 205), a)
                val ellipsizedArtist = TextUtils.ellipsize(card.detail, android.text.TextPaint(text), availW, TextUtils.TruncateAt.END).toString()
                c.drawText(ellipsizedArtist, textLeft, y + ch * 0.80f, text)
            }

            // Transport controls: Prev, Play/Pause, Next
            val cy = y + ch * 0.62f
            val buttons = listOf(
                Triple(controlsLeft + btnR * 0.9f, "⏮", CardAction.MEDIA_PREV),
                Triple(controlsLeft + btnR * 2.9f, if (card.mediaPlaying) "⏸" else "▶", CardAction.MEDIA_PLAY_PAUSE),
                Triple(controlsLeft + btnR * 4.9f, "⏭", CardAction.MEDIA_NEXT),
            )
            text.textAlign = Paint.Align.CENTER
            for ((bx, sym, act) in buttons) {
                val isPlay = act == CardAction.MEDIA_PLAY_PAUSE
                val r = if (isPlay) btnR * 1.08f else btnR * 0.88f
                fill.color = withAlpha(if (isPlay) card.tint else Color.rgb(55, 55, 62), a)
                c.drawCircle(bx, cy, r, fill)
                text.typeface = bold
                text.textSize = r * 0.85f
                text.color = withAlpha(if (isPlay) Color.BLACK else Color.WHITE, a)
                c.drawText(sym, bx, cy + text.textSize * 0.34f, text)
                actionRects += RectF(bx - r * 1.15f, cy - r * 1.15f, bx + r * 1.15f, cy + r * 1.15f) to act
            }
            card.tapAction?.let { actionRects += RectF(cardBounds.left, cardBounds.top, controlsLeft, cardBounds.bottom) to it }
            return
        }

        // 3. Standard / Rings card
        var maxTextW = cw - 2 * pad
        if (card.rings != null && card.rings.isNotEmpty()) {
            val ringR = ch * 0.30f
            val rcx = cardBounds.right - pad - ringR
            val rcy = y + ch * 0.56f
            maxTextW -= ringR * 2.3f
            val ringColors = intArrayOf(
                Color.rgb(250, 17, 79),   // Move / Battery red-pink
                Color.rgb(166, 227, 110), // Exercise / RAM green
                Color.rgb(0, 245, 234),   // Stand / Storage cyan
            )
            stroke.strokeWidth = ringR * 0.22f
            card.rings.take(3).forEachIndexed { idx, v ->
                val rr = ringR * (1f - idx * 0.28f)
                val col = ringColors.getOrElse(idx) { card.tint }
                rect.set(rcx - rr, rcy - rr, rcx + rr, rcy + rr)
                stroke.color = withAlpha(mix(col, Color.BLACK, 0.72f), a)
                c.drawArc(rect, 0f, 360f, false, stroke)
                stroke.color = withAlpha(col, a)
                c.drawArc(rect, -90f, 360f * v.coerceIn(0.03f, 1f), false, stroke)
            }
        }

        text.textAlign = Paint.Align.LEFT
        text.typeface = bold
        text.textSize = 0.095f * w
        text.color = withAlpha(Color.WHITE, a)
        c.drawText(
            TextUtils.ellipsize(card.big, android.text.TextPaint(text), maxTextW, TextUtils.TruncateAt.END).toString(),
            cardBounds.left + pad,
            y + ch * 0.62f,
            text,
        )
        if (card.detail.isNotEmpty()) {
            text.typeface = Typeface.DEFAULT
            text.textSize = 0.05f * w
            text.color = withAlpha(Color.rgb(200, 200, 200), a)
            c.drawText(
                TextUtils.ellipsize(card.detail, android.text.TextPaint(text), maxTextW, TextUtils.TruncateAt.END).toString(),
                cardBounds.left + pad,
                y + ch - pad,
                text,
            )
        }
        card.tapAction?.let { actionRects += cardBounds to it }
    }

    val maxScroll get() = (cards.size - 1).coerceAtLeast(0).toFloat()

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
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
