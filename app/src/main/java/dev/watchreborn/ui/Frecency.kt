package dev.watchreborn.ui

import kotlin.math.pow

/**
 * Frecency: a launch score that weighs *recent* launches most, so the
 * cluster's centre follows what the user actually opens now — not
 * all-time launch totals.
 *
 * The stored score decays with a [HALF_LIFE_DAYS]-day half-life between
 * launches, and each launch adds 1. An app opened daily settles near
 * an equilibrium of ~15; one opened three times a week near ~6; an
 * abandoned favourite halves every half-life until it leaves the
 * centre on its own.
 *
 * Replaces the legacy score (all-time count divided by weeks since the
 * last launch), under which a once-heavy app outranked a current daily
 * driver for months. [seedFromLegacy] converts those old counts.
 * [recordLaunch] combines the aging and the +1 increment in one call.
 */
object Frecency {

    const val HALF_LIFE_DAYS: Float = 10f

    /** Highest score a legacy all-time count can seed (about a heavy daily app). */
    const val SEED_CAP: Float = 20f

    private const val DAY_MS: Double = 86_400_000.0

    /**
     * [stored] — a score written at [lastMs] — aged forward to [nowMs].
     * Identity when there is nothing to age or no time has passed.
     */
    fun decay(stored: Float, lastMs: Long, nowMs: Long): Float {
        if (stored <= 0f || lastMs <= 0L || nowMs <= lastMs) return stored.coerceAtLeast(0f)
        val days = (nowMs - lastMs) / DAY_MS
        return (stored * 0.5.pow(days / HALF_LIFE_DAYS)).toFloat()
    }

    /**
     * One-off conversion of a legacy all-time launch [count] last opened
     * at [lastMs]: capped so a huge historical count cannot squat on the
     * centre, then aged by its own staleness.
     */
    fun seedFromLegacy(count: Int, lastMs: Long, nowMs: Long): Float {
        if (count <= 0) return 0f
        return decay(minOf(count.toFloat(), SEED_CAP), lastMs, nowMs)
    }

    /**
     * The score to store when an app launches at [nowMs].
     *
     * When [stored] is non-null the s: key already exists, so the stored
     * score is authoritative — even a value of 0 means "this app has been
     * tracked and its score decayed to zero," and falling back to the
     * legacy [legacyCount] would resurrect a stale all-time count that
     * should no longer influence the centre. Only when [stored] is null
     * (pre-1.18.0 data with no s: key yet) do we seed from [legacyCount].
     *
     * After aging or seeding, 1 is added for the launch that is happening
     * right now, and the result is returned for the caller to persist.
     */
    fun recordLaunch(stored: Float?, legacyCount: Int, lastMs: Long, nowMs: Long): Float {
        val aged = if (stored != null) decay(stored, lastMs, nowMs)
                   else seedFromLegacy(legacyCount, lastMs, nowMs)
        return aged + 1f
    }
}
