package dev.watchreborn.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NavBackTest {
    @Test fun cardMenuPopsBeforeEverything() {
        val s = NavState(cardMenuOpen = true, searching = true, sheetOpen = true, folderOpen = true)
        assertEquals(NavLayer.CARD_MENU, NavBack.layerToPop(s))
    }

    @Test fun searchPopsBeforeSheetAndPicker() {
        val s = NavState(searching = true, sheetOpen = true, topologyPickerOpen = true, folderOpen = true)
        assertEquals(NavLayer.SEARCH, NavBack.layerToPop(s))
    }

    @Test fun pickerPopsBeforeSheet() {
        val s = NavState(topologyPickerOpen = true, sheetOpen = true)
        assertEquals(NavLayer.TOPOLOGY_PICKER, NavBack.layerToPop(s))
    }

    @Test fun editModePopsBeforeFolderAndAfterSheet() {
        assertEquals(
            NavLayer.EDIT_MODE,
            NavBack.layerToPop(NavState(editMode = true, folderOpen = true))
        )
        assertEquals(
            NavLayer.SHEET,
            NavBack.layerToPop(NavState(editMode = true, sheetOpen = true))
        )
    }

    @Test fun sheetPopsBeforeFolder() {
        val s = NavState(sheetOpen = true, folderOpen = true)
        assertEquals(NavLayer.SHEET, NavBack.layerToPop(s))
    }

    @Test fun controlPopsBeforePods() {
        val s = NavState(controlOpen = true, widgetOpen = true)
        assertEquals(NavLayer.CONTROL, NavBack.layerToPop(s))
    }

    @Test fun widgetPodPopsBeforeFolder() {
        val s = NavState(widgetOpen = true, folderOpen = true)
        assertEquals(NavLayer.WIDGET_POD, NavBack.layerToPop(s))
    }

    @Test fun folderPopsBeforeOverview() {
        val s = NavState(folderOpen = true, overviewOpen = true)
        assertEquals(NavLayer.FOLDER, NavBack.layerToPop(s))
    }

    @Test fun pannedGridRecenters() {
        val s = NavState(gridOpen = true, panned = true)
        assertEquals(NavLayer.RECENTER, NavBack.layerToPop(s))
    }

    @Test fun bareClusterLetsSystemHandleBack() {
        // Since 2.0.0 the Cluster is home: with nothing open and the field
        // centred, Back falls through to the system.
        val s = NavState(gridOpen = true)
        assertEquals(NavLayer.NONE, NavBack.layerToPop(s))
    }
}
