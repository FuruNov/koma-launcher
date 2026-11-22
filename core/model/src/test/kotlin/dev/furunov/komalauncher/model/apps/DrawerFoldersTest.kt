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
    fun folderOfUsesMoveFirst() = assertEquals("other", DrawerFolders.folderOf(game, DrawerFolders.move(DrawerSettings(), game, "other")))
}
