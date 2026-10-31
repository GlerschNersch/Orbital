package dev.watchreborn.ui

import kotlin.math.PI
import kotlin.math.sin

/**
 * 3.8.3 (batch 2 item 3): one shared motion vocabulary. Orbital's
 * animation grew feature by feature — the Intent Bloom, Homecoming
 * Bloom, Living Field ghosts and the theme crossfade each carried
 * their own hand-tuned literals. The values below ARE those tuned
 * values, moved here unchanged (centralize, don't retune) so every
 * animation speaks the same language and the next one is born into
 * it. The new Launch Bloom consumes the tokens and adds its own.
 *
 * Spring settle times are not here on purpose: the view's springs
 * already share theirs through Spring's constructor and
 * Metrics.ZOOM_SECONDS.
 */
object MotionTokens {

    /** Theme pack colour crossfade (3.8.2, updateThemeColors). */
    const val THEME_FADE_MS = 300f

    /** Intent Bloom (3.8.1): the whole envelope, the per-rank stagger
     *  (capped so late ranks still land inside the envelope), each
     *  star's flight, and the replaced set's exit shrink/fade. */
    const val INTENT_BLOOM_TOTAL_MS = 650f
    const val INTENT_BLOOM_STAGGER_MS = 28f
    const val INTENT_BLOOM_STAGGER_CAP_MS = 220f
    const val INTENT_BLOOM_FLY_MS = 430f
    const val INTENT_BLOOM_EXIT_MS = 200f

    /** Homecoming Bloom (3.8.1): delay per unit of fisheye distance
     *  from the centre, then the cascade's own duration. */
    const val HOMECOMING_DELAY_PER_UNIT_MS = 150f
    const val HOMECOMING_DURATION_MS = 240f

    /** Living Field fling ghosts (3.8.1): the fade envelope. */
    const val GHOST_FADE_MS = 300f

    /** Launch Bloom (3.8.3): delay per ring step (distance measured in
     *  bubble pitches from the tapped bubble), each neighbour's pulse
     *  length, and how many rings out the pulse reaches. The total
     *  envelope is the last ring's delay plus one pulse: 224ms. */
    const val LAUNCH_BLOOM_STAGGER_MS = 18f
    const val LAUNCH_BLOOM_PULSE_MS = 170f
    const val LAUNCH_BLOOM_RANGE_RINGS = 3f
    const val LAUNCH_BLOOM_TOTAL_MS = 224f

    /**
     * Launch Bloom pulse envelope for a neighbour [ring] pitches from
     * the tapped bubble at [elapsedMs] into the bloom: 0 before its
     * staggered start and after its pulse, a sine peak of 1 midway.
     * Rings beyond [LAUNCH_BLOOM_RANGE_RINGS] never pulse.
     */
    fun launchPulse(elapsedMs: Float, ring: Float): Float {
        if (ring > LAUNCH_BLOOM_RANGE_RINGS || elapsedMs < 0f) return 0f
        val local = (elapsedMs - ring * LAUNCH_BLOOM_STAGGER_MS) / LAUNCH_BLOOM_PULSE_MS
        if (local <= 0f || local >= 1f) return 0f
        return sin(local * PI.toFloat())
    }

    /** Launch Bloom ripple radius, in bubble pitches, at [elapsedMs]:
     *  grows from the tap point to the pulse range over the envelope. */
    fun launchRippleRings(elapsedMs: Float): Float =
        (elapsedMs / LAUNCH_BLOOM_TOTAL_MS).coerceIn(0f, 1f) * LAUNCH_BLOOM_RANGE_RINGS

    /** Launch Bloom ripple alpha envelope: full at the tap, gone by
     *  the end of the envelope. */
    fun launchRippleAlpha(elapsedMs: Float): Float =
        1f - (elapsedMs / LAUNCH_BLOOM_TOTAL_MS).coerceIn(0f, 1f)
}
