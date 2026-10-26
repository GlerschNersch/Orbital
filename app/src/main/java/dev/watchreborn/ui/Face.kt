package dev.watchreborn.ui

/**
 * Status snapshot the activity hands the view: battery level, charging
 * state and the next alarm. (The watch Face that used to render its own
 * screen from this was removed in 2.0.0; the Cluster hub, the Vitals pod
 * and the Control Center still read it.)
 */
class FaceInfo(
    val battery: Int = -1,
    val charging: Boolean = false,
    /** Next alarm's trigger time (ms), or null. */
    val nextAlarm: Long? = null,
)
