package dev.watchreborn.ui

import kotlin.math.ln

/**
 * Usage growth: how much a bubble's diameter scales with how much its app is used.
 *
 * The input score is AppRepo's recency-decayed launch count. The curve is
 * logarithmic in the score relative to the most-used app, so a couple of launches
 * already nudge a bubble up, the ranking stays readable, and the heaviest app
 * lands exactly on [MAX]. Never-used apps sit at [MIN] — slightly smaller than
 * today's fixed size, which is what makes the growth of the used ones visible.
 */
object UsageScale {
    const val MIN: Float = 0.85f
    const val MAX: Float = 1.5f

    /** Diameter multiplier for an item with usage [score] when the top score is [maxScore]. */
    fun factor(score: Float, maxScore: Float): Float {
        if (maxScore <= 0f) return 1f
        if (score <= 0f) return MIN
        val t = (ln(1.0 + score) / ln(1.0 + maxScore)).toFloat().coerceIn(0f, 1f)
        return MIN + (MAX - MIN) * t
    }
}
