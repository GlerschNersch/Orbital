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
    fun launchPulse(elapsedMs: Float, ring: Float): Float =
        launchPulse(elapsedMs, ring, params(MotionDialect.STOCK))

    /** Launch Bloom ripple radius, in bubble pitches, at [elapsedMs]:
     *  grows from the tap point to the pulse range over the envelope. */
    fun launchRippleRings(elapsedMs: Float): Float =
        launchRippleRings(elapsedMs, params(MotionDialect.STOCK))

    /** Launch Bloom ripple alpha envelope: full at the tap, gone by
     *  the end of the envelope. */
    fun launchRippleAlpha(elapsedMs: Float): Float =
        launchRippleAlpha(elapsedMs, params(MotionDialect.STOCK))

    // ------------------------------------------------------------------
    // 3.10.0 (theming phase B): motion dialects. A pack's dialect
    // retimes the shared tokens above as one personality: CRISP runs
    // every envelope at roughly three-quarter time with tighter
    // staggers and a harder overshoot; DRIFT runs at roughly
    // four-thirds time with longer staggers and a gentler settle —
    // the PS3's patience. STOCK is the tuned 3.8.3 values verbatim
    // (the constants above), so an un-dialected pack moves exactly
    // as Orbital always has. An animation captures its params when
    // it STARTS; a mid-animation pack switch never retimes it.
    // ------------------------------------------------------------------

    /** One dialect's retiming of the shared animation tokens. */
    data class DialectParams(
        val themeFadeMs: Float,
        val intentBloomTotalMs: Float,
        val intentBloomStaggerMs: Float,
        val intentBloomStaggerCapMs: Float,
        val intentBloomFlyMs: Float,
        val intentBloomExitMs: Float,
        val homecomingDelayPerUnitMs: Float,
        val homecomingDurationMs: Float,
        val launchBloomStaggerMs: Float,
        val launchBloomPulseMs: Float,
        val launchBloomTotalMs: Float,
        /** Overshoot constant scale for the Intent Bloom's flight
         *  ease: > 1 snaps past the slot harder, < 1 settles soft. */
        val springScale: Float,
    )

    fun params(dialect: MotionDialect): DialectParams = when (dialect) {
        MotionDialect.STOCK -> DialectParams(
            themeFadeMs = THEME_FADE_MS,
            intentBloomTotalMs = INTENT_BLOOM_TOTAL_MS,
            intentBloomStaggerMs = INTENT_BLOOM_STAGGER_MS,
            intentBloomStaggerCapMs = INTENT_BLOOM_STAGGER_CAP_MS,
            intentBloomFlyMs = INTENT_BLOOM_FLY_MS,
            intentBloomExitMs = INTENT_BLOOM_EXIT_MS,
            homecomingDelayPerUnitMs = HOMECOMING_DELAY_PER_UNIT_MS,
            homecomingDurationMs = HOMECOMING_DURATION_MS,
            launchBloomStaggerMs = LAUNCH_BLOOM_STAGGER_MS,
            launchBloomPulseMs = LAUNCH_BLOOM_PULSE_MS,
            launchBloomTotalMs = LAUNCH_BLOOM_TOTAL_MS,
            springScale = 1f,
        )
        MotionDialect.CRISP -> DialectParams(
            themeFadeMs = 220f,
            intentBloomTotalMs = 488f,
            intentBloomStaggerMs = 20f,
            intentBloomStaggerCapMs = 165f,
            intentBloomFlyMs = 322f,
            intentBloomExitMs = 150f,
            homecomingDelayPerUnitMs = 112f,
            homecomingDurationMs = 180f,
            launchBloomStaggerMs = 13f,
            launchBloomPulseMs = 128f,
            launchBloomTotalMs = 167f,
            springScale = 1.3f,
        )
        MotionDialect.DRIFT -> DialectParams(
            themeFadeMs = 405f,
            intentBloomTotalMs = 878f,
            intentBloomStaggerMs = 38f,
            intentBloomStaggerCapMs = 297f,
            intentBloomFlyMs = 580f,
            intentBloomExitMs = 270f,
            homecomingDelayPerUnitMs = 203f,
            homecomingDurationMs = 324f,
            launchBloomStaggerMs = 24f,
            launchBloomPulseMs = 230f,
            launchBloomTotalMs = 302f,
            springScale = 0.75f,
        )
    }

    /** [launchPulse] under one dialect's timing. */
    fun launchPulse(elapsedMs: Float, ring: Float, p: DialectParams): Float {
        if (ring > LAUNCH_BLOOM_RANGE_RINGS || elapsedMs < 0f) return 0f
        val local = (elapsedMs - ring * p.launchBloomStaggerMs) / p.launchBloomPulseMs
        if (local <= 0f || local >= 1f) return 0f
        return sin(local * PI.toFloat())
    }

    /** [launchRippleRings] under one dialect's timing. */
    fun launchRippleRings(elapsedMs: Float, p: DialectParams): Float =
        (elapsedMs / p.launchBloomTotalMs).coerceIn(0f, 1f) * LAUNCH_BLOOM_RANGE_RINGS

    /** [launchRippleAlpha] under one dialect's timing. */
    fun launchRippleAlpha(elapsedMs: Float, p: DialectParams): Float =
        1f - (elapsedMs / p.launchBloomTotalMs).coerceIn(0f, 1f)

    /**
     * The Intent Bloom's flight ease under a dialect: easeOutBack
     * with the overshoot constant scaled by the dialect's spring
     * scale. At STOCK (scale 1) this is MorphMath.ease exactly —
     * same constant, same curve, endpoints exact.
     */
    fun bloomEase(t: Float, p: DialectParams): Float {
        val c = 1.15f * p.springScale
        val u = t.coerceIn(0f, 1f) - 1f
        return 1f + (c + 1f) * u * u * u + c * u * u
    }

    // ------------------------------------------------------------------
    // 3.10.0 (theming phase B): the switch wave — on a manual pack
    // pick, a pulse sweeps the field outward from its centre in the
    // new pack's accent, riding on top of the colour crossfade.
    // Distance is measured in bubble pitches from the field centre,
    // the same unit the Launch Bloom counts rings in.
    // ------------------------------------------------------------------

    /** Delay per pitch of distance from the field centre. */
    const val SWITCH_WAVE_STAGGER_MS = 14f

    /** One item's pulse length once the wave reaches it. */
    const val SWITCH_WAVE_PULSE_MS = 240f

    /** Hard bound for the whole sweep: the view parks the wave
     *  clock here whatever the field's radius. */
    const val SWITCH_WAVE_TOTAL_MS = 700f

    /** Pulses start no further out than this: the radius at which
     *  a pulse still completes inside [SWITCH_WAVE_TOTAL_MS]. */
    const val SWITCH_WAVE_MAX_DIST = (SWITCH_WAVE_TOTAL_MS - SWITCH_WAVE_PULSE_MS) / SWITCH_WAVE_STAGGER_MS

    /**
     * Switch wave envelope for an item [distUnits] pitches from the
     * field centre at [elapsedMs] into the wave: 0 before its
     * staggered start and after its pulse, a sine peak of 1 midway.
     */
    fun switchWavePulse(elapsedMs: Float, distUnits: Float): Float {
        if (distUnits > SWITCH_WAVE_MAX_DIST || elapsedMs < 0f) return 0f
        val local = (elapsedMs - distUnits * SWITCH_WAVE_STAGGER_MS) / SWITCH_WAVE_PULSE_MS
        if (local <= 0f || local >= 1f) return 0f
        return sin(local * PI.toFloat())
    }
}
