package dev.watchreborn.ui

/**
 * Built-in native widgets (3.6.0). Orbital's pre-2.5.0 widget
 * bubbles (clock / calendar / timer / vitals / now playing) were
 * frozen off when the Widgets page moved to hosted system widgets;
 * their live renderers survived in WatchView's drawWidgetBubble.
 * 3.6.0 revives them as ISLANDS: canvas-drawn tiles in the island
 * system, so they fisheye, slosh and scale like everything else on
 * the field instead of riding a snapshot.
 *
 * Every kind renders only from data the app already reaches — the
 * wall clock, the next-alarm clock, the step counter behind the
 * existing ACTIVITY_RECOGNITION permission, battery / memory /
 * storage ratios, and the notification-listener now-playing feed.
 * Calendar shows the date + next alarm only: reading calendar
 * EVENTS would need READ_CALENDAR, a new permission 3.6.0 does not
 * add. The old "Control Cluster" bubble is NOT revived: it had no
 * surviving live renderer and its surface was the Control Center
 * removed in 3.0.0.
 */
enum class NativeKind(
    val label: String,
    val blurb: String,
    val glyph: String,
    /** Accent tint, inherited from the old widget bubbles. */
    val tint: Int,
    /** Default tile size (dp) for a fresh island of this kind,
     *  inside the islandTileDp clamps (180-420 x 110-300). */
    val defaultWDp: Int,
    val defaultHDp: Int,
) {
    CLOCK("Clock", "Time and date, drawn live", "🕐", 0xFFA6E36E.toInt(), 300, 150),
    CALENDAR("Calendar", "The date and your next alarm", "📅", 0xFFFF453A.toInt(), 300, 150),
    TIMER("Timer", "A countdown you start from its pod", "⏱", 0xFFFF9F0A.toInt(), 300, 150),
    VITALS("Vitals", "Steps, battery and system rings", "💚", 0xFF30D158.toInt(), 300, 150),
    NOW_PLAYING("Now Playing", "The live track; transport in its pod", "🎵", 0xFFFF375F.toInt(), 300, 150),
    ;

    companion object {
        fun fromName(name: String?): NativeKind? =
            if (name == null) null else entries.firstOrNull { it.name == name }
    }
}

object NativeIslands {
    /** Picker display order. */
    val pickerKinds: List<NativeKind> = listOf(
        NativeKind.CLOCK, NativeKind.CALENDAR, NativeKind.TIMER,
        NativeKind.VITALS, NativeKind.NOW_PLAYING,
    )
}
