package dev.furunov.komalauncher.model.folders

import dev.furunov.komalauncher.model.apps.AppCategory
import dev.furunov.komalauncher.model.apps.AppEntry
import dev.furunov.komalauncher.model.apps.AppKey
import dev.furunov.komalauncher.model.apps.DrawerFolders
import dev.furunov.komalauncher.model.settings.DrawerSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class PanelLinksTest {
    private val g1 = AppEntry(AppKey("g1", "G"), "ゲーム1", AppCategory.GAME)
    private val g2 = AppEntry(AppKey("g2", "G"), "ゲーム2", AppCategory.GAME)
    private val tool = AppEntry(AppKey("t", "T"), "道具")
    private val apps = listOf(g1, g2, tool)
    private val entryOf: (AppKey) -> AppEntry = { k -> apps.first { it.key == k } }

    private val linked = Folders(byPanel = mapOf("p1" to Folder(apps = listOf(tool.key), drawerFolder = "game"), "p2" to Folder(apps = listOf(tool.key))))

    @Test
    fun linkedPanelShowsDrawerFolder() {
        val f = PanelLinks.effective(linked, DrawerSettings(), apps)
        assertEquals(listOf(g1.key, g2.key), f.appsOf("p1"))
        assertEquals(listOf(tool.key), f.appsOf("p2"))
    }

    @Test
    fun hiddenAppsAreNotShownInLinkedPanel() = assertEquals(listOf(g2.key), PanelLinks.effective(linked, DrawerSettings(hidden = setOf(g1.key)), apps).appsOf("p1"))

    @Test
    fun addingToLinkedPanelMovesAppIntoDrawerFolder() {
        val before = PanelLinks.effective(linked, DrawerSettings(), apps)
        val after = FolderEdits.add(before, "p1", tool.key)
        val drawer = PanelLinks.sync(before, after, DrawerSettings(), entryOf)
        assertEquals("game", DrawerFolders.folderOf(tool, drawer))
        assertEquals(listOf(g1.key, g2.key, tool.key), PanelLinks.effective(linked, drawer, apps).appsOf("p1"))
    }

    @Test
    fun removingFromLinkedPanelMovesAppToOther() {
        val before = PanelLinks.effective(linked, DrawerSettings(), apps)
        val drawer = PanelLinks.sync(before, FolderEdits.remove(before, "p1", g1.key), DrawerSettings(), entryOf)
        assertEquals(DrawerFolders.OTHER, DrawerFolders.folderOf(g1, drawer))
    }

    @Test
    fun reorderingLinkedPanelReordersDrawerFolder() {
        val before = PanelLinks.effective(linked, DrawerSettings(), apps)
        val after = FolderEdits.move(before, "p1", g2.key, "p1", 0)
        val drawer = PanelLinks.sync(before, after, DrawerSettings(), entryOf)
        assertEquals(listOf(g2.key, g1.key), PanelLinks.effective(linked, drawer, apps).appsOf("p1"))
    }

    @Test
    fun unlinkedPanelsDoNotTouchDrawer() {
        val before = PanelLinks.effective(linked, DrawerSettings(), apps)
        val after = FolderEdits.add(before, "p2", g1.key)
        assertEquals(DrawerSettings(), PanelLinks.sync(before, after, DrawerSettings(), entryOf))
    }

    @Test
    fun storedKeepsOwnAppsOfLinkedPanel() {
        val before = PanelLinks.effective(linked, DrawerSettings(), apps)
        val after = FolderEdits.add(FolderEdits.rename(before, "p1", "遊び"), "p2", g1.key)
        val stored = PanelLinks.storable(linked, after)
        assertEquals(listOf(tool.key), stored.appsOf("p1"))
        assertEquals("遊び", stored.byPanel.getValue("p1").name)
        assertEquals(listOf(tool.key, g1.key), stored.appsOf("p2"))
    }

    @Test
    fun unlinkKeepsWhatWasShown() {
        val shown = PanelLinks.effective(linked, DrawerSettings(), apps)
        val f = PanelLinks.link(linked, shown, "p1", null)
        assertEquals(null, f.byPanel.getValue("p1").drawerFolder)
        assertEquals(listOf(g1.key, g2.key), f.appsOf("p1"))
    }
}
