package dev.furunov.komalauncher.model.apps

import dev.furunov.komalauncher.model.settings.DrawerSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class DrawerFoldersTest {
    private val game = AppEntry(AppKey("g", "G"), "ゲーム1", AppCategory.GAME)
    private val music = AppEntry(AppKey("m", "M"), "音楽1", AppCategory.AUDIO)
    private val tool = AppEntry(AppKey("t", "T"), "道具")
    private val pwa = AppEntry(AppKey.shortcut("browser", "x"), "クイズ")
    private val apps = listOf(game, music, tool, pwa)

    private fun ids(s: DrawerSettings = DrawerSettings()) = DrawerFolders.of(apps, s).map { it.id to it.apps.map(AppEntry::label) }

    @Test
    fun groupsByCategoryAndPutsUnknownInOther() = assertEquals(
        listOf("game" to listOf("ゲーム1"), "audio" to listOf("音楽1"), "other" to listOf("道具", "クイズ")),
        ids(),
    )

    @Test
    fun movedAppGoesToChosenFolderAndEmptyFolderDisappears() {
        val s = DrawerFolders.move(DrawerSettings(), game, "audio")
        assertEquals(listOf("audio" to listOf("ゲーム1", "音楽1"), "other" to listOf("道具", "クイズ")), ids(s))
    }

    @Test
    fun movingBackToAutomaticFolderForgetsTheMove() {
        val s = DrawerFolders.move(DrawerFolders.move(DrawerSettings(), game, "audio"), game, "game")
        assertEquals(emptyList(), s.moved)
    }

    @Test
    fun newFolderComesBeforeOther() {
        val (s, id) = DrawerFolders.create(DrawerSettings(), "ウェブ")
        val moved = DrawerFolders.move(s, pwa, id)
        assertEquals(listOf("game", "audio", id, "other"), DrawerFolders.of(apps, moved).map { it.id })
        assertEquals("ウェブ", DrawerFolders.of(apps, moved).first { it.id == id }.name)
    }

    @Test
    fun createWithExistingNameReusesFolder() {
        val (s, id) = DrawerFolders.create(DrawerSettings(), "ウェブ")
        assertEquals(s to id, DrawerFolders.create(s, " ウェブ "))
        assertEquals("game", DrawerFolders.create(DrawerSettings(), "ゲーム").second)
    }

    @Test
    fun renameBuiltInFolder() = assertEquals("遊び", DrawerFolders.of(apps, DrawerFolders.rename(DrawerSettings(), "game", "遊び")).first().name)

    @Test
    fun renameToBlankRestoresDefaultName() {
        val s = DrawerFolders.rename(DrawerFolders.rename(DrawerSettings(), "game", "遊び"), "game", "  ")
        assertEquals("ゲーム", DrawerFolders.of(apps, s).first().name)
    }

    @Test
    fun choicesListEveryFolderEvenEmpty() = assertEquals(DrawerFolders.builtIn.size, DrawerFolders.choices(DrawerSettings()).size)

    @Test
    fun savedAppOrderComesFirstAndNewAppsFollowByLabel() {
        val s = DrawerSettings(appOrder = mapOf("other" to listOf(pwa.key, AppKey("gone", "G"))))
        assertEquals(listOf("クイズ", "道具"), DrawerFolders.of(apps, s).first { it.id == "other" }.apps.map(AppEntry::label))
    }

    @Test
    fun reorderAppsSavesWholeFolder() {
        val s = DrawerFolders.reorderApps(DrawerSettings(), "other", listOf(pwa.key, tool.key))
        assertEquals(listOf("クイズ", "道具"), DrawerFolders.of(apps, s).first { it.id == "other" }.apps.map(AppEntry::label))
    }

    @Test
    fun savedTabOrderComesFirst() {
        val s = DrawerSettings(tabOrder = listOf("other", "audio"))
        assertEquals(listOf("other", "audio", "game"), DrawerFolders.of(apps, s).map { it.id })
        assertEquals(listOf("other", "audio", "game"), DrawerFolders.choices(s).map { it.first }.take(3))
    }

    @Test
    fun moveItemShiftsOthers() {
        assertEquals(listOf("b", "c", "a"), DrawerFolders.moveItem(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("c", "a", "b"), DrawerFolders.moveItem(listOf("a", "b", "c"), 2, 0))
        assertEquals(listOf("a", "b"), DrawerFolders.moveItem(listOf("a", "b"), 5, 0))
    }

    @Test
    fun builtInFolderHasSymbolAndCustomFolderUsesFolderSymbol() {
        val (s, id) = DrawerFolders.create(DrawerSettings(), "ウェブ")
        assertEquals("game", DrawerFolders.symbolOf("game", s))
        assertEquals(DrawerFolders.FOLDER_SYMBOL, DrawerFolders.symbolOf(id, s))
    }

    @Test
    fun chosenSymbolWinsAndUnknownSymbolIsIgnored() {
        assertEquals("star", DrawerFolders.symbolOf("game", DrawerFolders.setSymbol(DrawerSettings(), "game", "star")))
        assertEquals("game", DrawerFolders.symbolOf("game", DrawerFolders.setSymbol(DrawerSettings(), "game", "no-such")))
    }

    @Test
    fun everyBuiltInSymbolIsSelectable() = DrawerFolders.builtIn.forEach { (id, _) -> assert(DrawerFolders.symbolOf(id, DrawerSettings()) in DrawerFolders.symbols) }

    @Test
    fun folderOfUsesMoveFirst() = assertEquals("other", DrawerFolders.folderOf(game, DrawerFolders.move(DrawerSettings(), game, "other")))
}
