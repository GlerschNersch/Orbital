package dev.watchreborn.ui

/**
 * Explicit navigation-layer model for Back handling.
 *
 * WatchView layers several springs on one canvas; which layer a Back press should
 * pop used to be an order-sensitive chain of ifs inside `back()`. That priority now
 * lives here as a pure function of a state snapshot, so it is unit-testable and the
 * full order is visible in one place.
 */
enum class NavLayer {
    SEARCH,
    TOPOLOGY_PICKER,
    SHEET,
    TORCH_OFF,
    STACK_OR_CONTROL,
    WIDGET_POD,
    FOLDER,
    OVERVIEW,
    LIST,
    RECENTER,
    FACE,
    EXIT_TO_FACE,
    NONE,
}

/** Snapshot of the navigation-relevant state of WatchView at a Back press. */
data class NavState(
    val searching: Boolean = false,
    val topologyPickerOpen: Boolean = false,
    val sheetOpen: Boolean = false,
    val torchOffViaBack: Boolean = false,
    val stackOrControlOpen: Boolean = false,
    val inApps: Boolean = false,
    val widgetOpen: Boolean = false,
    val folderOpen: Boolean = false,
    val overviewOpen: Boolean = false,
    val listOpen: Boolean = false,
    val gridOpen: Boolean = false,
    val panned: Boolean = false,
    val onFace: Boolean = false,
    val honeycombHome: Boolean = false,
)

object NavBack {
    /** Which layer one Back press should pop, or [NavLayer.NONE] to let the system handle it. */
    fun layerToPop(s: NavState): NavLayer = when {
        s.searching -> NavLayer.SEARCH
        s.topologyPickerOpen -> NavLayer.TOPOLOGY_PICKER
        s.sheetOpen -> NavLayer.SHEET
        s.torchOffViaBack -> NavLayer.TORCH_OFF
        s.stackOrControlOpen -> NavLayer.STACK_OR_CONTROL
        s.inApps && s.widgetOpen -> NavLayer.WIDGET_POD
        s.inApps && s.folderOpen -> NavLayer.FOLDER
        s.inApps && s.overviewOpen -> NavLayer.OVERVIEW
        s.inApps && s.listOpen -> NavLayer.LIST
        s.honeycombHome && s.inApps && s.gridOpen && s.panned -> NavLayer.RECENTER
        s.onFace -> if (s.honeycombHome) NavLayer.FACE else NavLayer.NONE
        else -> NavLayer.EXIT_TO_FACE
    }
}
