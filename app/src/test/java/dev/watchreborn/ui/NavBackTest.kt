package dev.watchreborn.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NavBackTest {
    @Test fun cardMenuPopsBeforeEverything() {
        val s = NavState(cardMenuOpen = true, searching = true, sheetOpen = true, inApps = true, folderOpen = true)
        assertEquals(NavLayer.CARD_MENU, NavBack.layerToPop(s))
    }

    @Test fun searchPopsBeforeEverything() {
        val s = NavState(searching = true, sheetOpen = true, topologyPickerOpen = true, inApps = true, folderOpen = true)
        assertEquals(NavLayer.SEARCH, NavBack.layerToPop(s))
    }

    @Test fun pickerPopsBeforeSheet() {
        val s = NavState(topologyPickerOpen = true, sheetOpen = true, inApps = true)
        assertEquals(NavLayer.TOPOLOGY_PICKER, NavBack.layerToPop(s))
    }

    @Test fun editModePopsBeforeFolderAndAfterSheet() {
        assertEquals(
            NavLayer.EDIT_MODE,
            NavBack.layerToPop(NavState(inApps = true, editMode = true, folderOpen = true))
        )
        assertEquals(
            NavLayer.SHEET,
            NavBack.layerToPop(NavState(inApps = true, editMode = true, sheetOpen = true))
        )
    }

    @Test fun sheetPopsBeforeFolder() {
        val s = NavState(sheetOpen = true, inApps = true, folderOpen = true)
        assertEquals(NavLayer.SHEET, NavBack.layerToPop(s))
    }

    @Test fun stackAndControlPopBeforePods() {
        val s = NavState(stackOrControlOpen = true, inApps = true, widgetOpen = true)
        assertEquals(NavLayer.STACK_OR_CONTROL, NavBack.layerToPop(s))
    }

    @Test fun widgetPodPopsBeforeFolder() {
        val s = NavState(inApps = true, widgetOpen = true, folderOpen = true)
        assertEquals(NavLayer.WIDGET_POD, NavBack.layerToPop(s))
    }

    @Test fun listPopsBackToGrid() {
        // The Phase-3 fix: Back in List view returns to the grid instead of
        // jumping straight to the face.
        val s = NavState(inApps = true, listOpen = true, gridOpen = true)
        assertEquals(NavLayer.LIST, NavBack.layerToPop(s))
    }

    @Test fun pannedGridRecentersBeforeLeaving() {
        val s = NavState(honeycombHome = true, inApps = true, gridOpen = true, panned = true)
        assertEquals(NavLayer.RECENTER, NavBack.layerToPop(s))
    }

    @Test fun faceWithHoneycombHomeOpensApps() {
        val s = NavState(onFace = true, honeycombHome = true)
        assertEquals(NavLayer.FACE, NavBack.layerToPop(s))
    }

    @Test fun faceWithoutHoneycombHomeLetsSystemHandleBack() {
        val s = NavState(onFace = true, honeycombHome = false)
        assertEquals(NavLayer.NONE, NavBack.layerToPop(s))
    }

    @Test fun plainAppsExitToFace() {
        val s = NavState(inApps = true, gridOpen = true)
        assertEquals(NavLayer.EXIT_TO_FACE, NavBack.layerToPop(s))
    }
}
