package dev.watchreborn.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import dev.watchreborn.apps.App
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** One Smart Stack card: a tinted platter with a small title, a big line and a detail line. */
class Card(
    val tint: Int,
    val title: String,
    val big: String,
    val detail: String = "",
    /** A row of app icons instead of the big line (the recents card). */
    val apps: List<App> = emptyList(),
)

/**
 * The Smart Stack (turning the crown on the face): the face shrinks to a header of date and time,
 * and cards follow, each 0.91 W wide (on the watch the next one peeks from behind). Scrolled
 * up, the header becomes one line. Our own cards, filled from what Android can tell us. On a
 * tall phone every card fits, so they're laid out one under another rather than stacked.
 */
class Stack {
    var accent = Color.rgb(166, 227, 110)
    var cards: List<Card> = emptyList()

    /** Scroll in cards (0 = the first card under the header). */
    var scroll = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val rect = RectF()

    /** Where each card's app icons were drawn last frame, for taps. */
    val appRects = ArrayList<Pair<RectF, App>>()

    /** [t] 0..1 is how far the stack has come in from the face. */
    fun draw(c: Canvas, w: Float, h: Float, top: Float, bottom: Float, now: Calendar, t: Float) {
        if (t <= 0f) return
        val a = (t * 255).toInt().coerceIn(0, 255)
        appRects.clear()
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

        // Cards: the front one under the header, later ones following; past ones tuck under the header.
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
                // Scrolled past: shrinks and fades as it slides under the header.
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
        rect.set(cx - cw / 2, y, cx + cw / 2, y + ch)
        fill.color = withAlpha(mix(card.tint, Color.BLACK, 0.55f), a)
        c.drawRoundRect(rect, Metrics.CARD_R * w, Metrics.CARD_R * w, fill)
        val pad = 0.05f * w
        text.textAlign = Paint.Align.LEFT
        text.typeface = bold
        text.textSize = 0.055f * w
        text.color = withAlpha(card.tint, a)
        c.drawText(card.title.uppercase(), rect.left + pad, y + pad + 0.05f * w, text)
        if (card.apps.isNotEmpty()) {
            val d = 0.15f * w
            val gap = (cw - 2 * pad - 4 * d) / 3
            card.apps.take(4).forEachIndexed { i, app ->
                val x = rect.left + pad + i * (d + gap)
                val r = RectF(x, y + ch - pad - d, x + d, y + ch - pad)
                bitmapPaint.alpha = a
                c.drawBitmap(app.icon, null, r, bitmapPaint)
                appRects += r to app
            }
            return
        }
        text.textSize = 0.11f * w
        text.color = withAlpha(Color.WHITE, a)
        c.drawText(TextUtils.ellipsize(card.big, android.text.TextPaint(text), cw - 2 * pad, TextUtils.TruncateAt.END).toString(),
            rect.left + pad, y + ch * 0.62f, text)
        if (card.detail.isNotEmpty()) {
            text.typeface = Typeface.DEFAULT
            text.textSize = 0.055f * w
            text.color = withAlpha(Color.rgb(200, 200, 200), a)
            c.drawText(card.detail, rect.left + pad, y + ch - pad, text)
        }
    }

    /** How far the cards can scroll. */
    val maxScroll get() = (cards.size - 1).coerceAtLeast(0).toFloat()

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun mix(c1: Int, c2: Int, t: Float) = Color.rgb(
        (Color.red(c1) * (1 - t) + Color.red(c2) * t).toInt(),
        (Color.green(c1) * (1 - t) + Color.green(c2) * t).toInt(),
        (Color.blue(c1) * (1 - t) + Color.blue(c2) * t).toInt(),
    )
    private fun withAlpha(color: Int, a: Int) = Color.argb(Color.alpha(color) * a.coerceIn(0, 255) / 255, Color.red(color), Color.green(color), Color.blue(color))
}
