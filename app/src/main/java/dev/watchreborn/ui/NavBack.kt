package dev.watchreborn.ui

/**
 * Explicit navigation-layer model for Back handling.
 *
 * WatchView layers several springs on one canvas; which layer a Back press should
 * pop used to be an order-sensitive chain of ifs inside `back()`. That priority now
 * lives here as a pure function of a state snapshot, so it is unit-testable and the
 * full order is visible in one place.
 *
 * Since 2.0.0 the Cluster is the only base surface (the watch Face and the List
 * page were removed), so Back unwinds overlays and then the pan, and otherwise
 * falls through to the system.
 */
enum class NavLayer {
    CARD_MENU,
    SEARCH,
    TOPOLOGY_PICKER,
    SHEET,
    EDIT_MODE,
    WIDGET_POD,
    FOLDER,
    OVERVIEW,
    RECENTER,
    NONE,
}

/** Snapshot of the navigation-relevant state of WatchView at a Back press. */
data class NavState(
    val cardMenuOpen: Boolean = false,
    val searching: Boolean = false,
    val topologyPickerOpen: Boolean = false,
    val sheetOpen: Boolean = false,
    val editMode: Boolean = false,
    val widgetOpen: Boolean = false,
    val folderOpen: Boolean = false,
    val overviewOpen: Boolean = false,
    val gridOpen: Boolean = false,
    val panned: Boolean = false,
)

object NavBack {
    /** Which layer one Back press should pop, or [NavLayer.NONE] to let the system handle it. */
    fun layerToPop(s: NavState): NavLayer = when {
        s.cardMenuOpen -> NavLayer.CARD_MENU
        s.searching -> NavLayer.SEARCH
        s.topologyPickerOpen -> NavLayer.TOPOLOGY_PICKER
        s.sheetOpen -> NavLayer.SHEET
        s.editMode -> NavLayer.EDIT_MODE
        s.widgetOpen -> NavLayer.WIDGET_POD
        s.folderOpen -> NavLayer.FOLDER
        s.overviewOpen -> NavLayer.OVERVIEW
        s.gridOpen && s.panned -> NavLayer.RECENTER
        else -> NavLayer.NONE
    }
}
