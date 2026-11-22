package dev.furunov.komalauncher.model.folders

import dev.furunov.komalauncher.model.apps.AppKey
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.Panel
import dev.furunov.komalauncher.model.layout.PanelContent
import dev.furunov.komalauncher.model.layout.PanelId
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class FolderEditsTest {
    private val a = AppKey("a", "A")
    private val b = AppKey("b", "B")
    private val c = AppKey("c", "C")
    private val base = Folders(byPanel = mapOf("p1" to Folder(listOf(a, b)), "p2" to Folder(listOf(c))))

    @Test
    fun addAppends() = assertEquals(listOf(c), FolderEdits.add(Folders(), "p1", c).appsOf("p1"))

    @Test
    fun addAtIndexIsClamped() = assertEquals(listOf(a, b, c), FolderEdits.add(base, "p1", c, index = 99).appsOf("p1"))

    @Test
    fun addDuplicateIsNoop() = assertSame(base, FolderEdits.add(base, "p1", a))

    @Test
    fun replaceKeepsPositionInEveryFolder() {
        val f = FolderEdits.replace(base.copy(byPanel = base.byPanel + ("p3" to Folder(listOf(c, a)))), listOf(a), c)
        assertEquals(listOf(c, b), f.appsOf("p1"))
        // p3 には c がもう入っているので、a を外すだけにする
        assertEquals(listOf(c), f.appsOf("p3"))
    }

    @Test
    fun replaceSeveralOldKeepsFirstPosition() = assertEquals(listOf(c), FolderEdits.replace(base, listOf(a, b), c).appsOf("p1"))

    @Test
    fun replaceInUnassigned() = assertEquals(listOf(c), FolderEdits.replace(Folders(unassigned = listOf(a)), listOf(a), c).unassigned)

    @Test
    fun addTakesFromUnassigned() {
        val f = FolderEdits.add(base.copy(unassigned = listOf(c)), "p1", c)
        assertEquals(emptyList(), f.unassigned)
    }

    @Test
    fun toggleAddsThenRemoves() {
        val added = FolderEdits.toggle(base, "p1", c)
        assertEquals(listOf(a, b, c), added.appsOf("p1"))
        assertEquals(listOf(a, b), FolderEdits.toggle(added, "p1", c).appsOf("p1"))
    }

    @Test
    fun removeMissingIsNoop() {
        assertSame(base, FolderEdits.remove(base, "p1", c))
        assertSame(base, FolderEdits.remove(base, "nope", a))
        assertEquals(Folders(), FolderEdits.remove(Folders(), "p1", a))
    }

    @Test
    fun reorder() = assertEquals(listOf(b, a), FolderEdits.reorder(base, "p1", 0, 1).appsOf("p1"))

    @Test
    fun reorderOutOfRangeIsSafe() {
        assertSame(base, FolderEdits.reorder(base, "p1", 5, 0))
        assertEquals(listOf(b, a), FolderEdits.reorder(base, "p1", 0, 99).appsOf("p1"))
    }

    @Test
    fun moveBetweenPanels() {
        val f = FolderEdits.move(base, "p1", a, "p2", index = 0)
        assertEquals(listOf(b), f.appsOf("p1"))
        assertEquals(listOf(a, c), f.appsOf("p2"))
    }

    @Test
    fun moveToSamePanelReorders() = assertEquals(listOf(b, a), FolderEdits.move(base, "p1", a, "p1", index = 1).appsOf("p1"))

    @Test
    fun moveAppNotInSourceIsNoop() = assertSame(base, FolderEdits.move(base, "p1", c, "p2"))

    @Test
    fun moveIntoFolderThatAlreadyHasItDropsSource() {
        val f = FolderEdits.move(base.copy(byPanel = base.byPanel + ("p2" to Folder(listOf(a)))), "p1", a, "p2")
        assertEquals(listOf(b), f.appsOf("p1"))
        assertEquals(listOf(a), f.appsOf("p2"))
    }

    @Test
    fun renameBlankClears() = assertEquals(null, FolderEdits.rename(FolderEdits.rename(base, "p1", "x"), "p1", " ").byPanel["p1"]?.name)

    private fun layout(vararg panels: Pair<String, List<String>>) = Layout.of(panels.map { (id, apps) -> Panel(PanelId(id), PanelContent.Folder(apps)) })

    private val resolve: (String) -> AppKey? = { pkg -> listOf(a, b, c).firstOrNull { it.packageName == pkg } }

    @Test
    fun reconcileSeedsOnce() {
        val once = FolderEdits.reconcile(Folders(), layout("p1" to listOf("a", "missing")), resolve)
        assertEquals(listOf(a), once.appsOf("p1"))
        // 利用者が外した後に、もう一度読み込んでも初期値で戻らない
        val edited = FolderEdits.remove(once, "p1", a)
        assertEquals(emptyList(), FolderEdits.reconcile(edited, layout("p1" to listOf("a")), resolve).appsOf("p1"))
    }

    @Test
    fun reconcileKeepsOrphansAsUnassigned() {
        val f = FolderEdits.reconcile(base.copy(seeded = setOf("p1", "p2")), layout("p1" to emptyList()), resolve)
        assertEquals(listOf(a, b), f.appsOf("p1"))
        assertEquals(listOf(c), f.unassigned)
        assertEquals(null, f.byPanel["p2"])
    }

    @Test
    fun oldFoldersJsonWithoutShortcutIdDecodes() {
        val f = Json { ignoreUnknownKeys = true }.decodeFromString(Folders.serializer(), """{"byPanel":{"p1":{"apps":[{"packageName":"a","className":"A"}]}}}""")
        assertEquals(listOf(AppKey("a", "A", null)), f.appsOf("p1"))
    }

    @Test
    fun shortcutKeyRoundTrips() {
        val f = Folders(byPanel = mapOf("p1" to Folder(listOf(a, AppKey.shortcut("com.vivaldi.browser", "pwa-1")))))
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(f, json.decodeFromString(Folders.serializer(), json.encodeToString(Folders.serializer(), f)))
    }

    @Test
    fun shortcutsOfSamePackageAreDistinct() {
        val s1 = AppKey.shortcut("v", "1")
        val s2 = AppKey.shortcut("v", "2")
        val added = FolderEdits.add(FolderEdits.add(Folders(), "p1", s1), "p1", s2)
        assertEquals(listOf(s1, s2), added.appsOf("p1"))
        assertEquals(listOf(s2), FolderEdits.remove(added, "p1", s1).appsOf("p1"))
    }

    @Test
    fun shownAppsHidesMissingShortcutsOnly() {
        val s1 = AppKey.shortcut("v", "1")
        val s2 = AppKey.shortcut("v", "2")
        val f = Folders(byPanel = mapOf("p1" to Folder(listOf(a, s1, b, s2))))
        assertEquals(listOf(a, b, s2), f.shownAppsOf("p1", setOf(a, s2)))
    }

    @Test
    fun dataIndexSkipsHidden() {
        val hidden = AppKey.shortcut("v", "h")
        val apps = listOf(a, hidden, b, c)
        val shown: (AppKey) -> Boolean = { it != hidden }
        assertEquals(0, FolderEdits.dataIndex(apps, 0, shown))
        assertEquals(2, FolderEdits.dataIndex(apps, 1, shown))
        assertEquals(3, FolderEdits.dataIndex(apps, 2, shown))
        assertEquals(4, FolderEdits.dataIndex(apps, 3, shown))
    }
}

class SampleFolderTest {
    private val a = AppKey("a", "A")
    private fun layoutOf(id: String) = Layout.of(listOf(Panel(PanelId(id), PanelContent.Folder(listOf("a")))))

    @Test
    fun sampleContentsAreDroppedNotUnassigned() {
        val seeded = FolderEdits.seedOnly(Folders(), layoutOf("sample-1"), { if (it == "a") a else null })
        assertEquals(listOf(a), seeded.appsOf("sample-1"))
        val user = FolderEdits.reconcile(seeded, layoutOf("p1"), { if (it == "a") a else null })
        assertEquals(emptyList(), user.unassigned)
        assertEquals(listOf(a), user.appsOf("p1"))
        assertEquals(setOf("p1"), user.seeded)
    }

    @Test
    fun seedOnlyKeepsOrphans() {
        val f = Folders(byPanel = mapOf("gone" to Folder(listOf(a))))
        assertEquals(listOf(a), FolderEdits.seedOnly(f, layoutOf("p1"), { null }).appsOf("gone"))
    }

    @Test
    fun setDirectLaunchKeepsApps() {
        val named = FolderEdits.rename(Folders(byPanel = mapOf("p1" to Folder(listOf(a, AppKey("b", "B"))))), "p1", "よく使う")
        val on = FolderEdits.setDirectLaunch(named, "p1", true)
        assertEquals(true, on.byPanel.getValue("p1").directLaunch)
        assertEquals(listOf(a, AppKey("b", "B")), on.appsOf("p1"))
        assertEquals("よく使う", on.byPanel.getValue("p1").name)
        assertEquals(false, FolderEdits.setDirectLaunch(on, "p1", false).byPanel.getValue("p1").directLaunch)
    }

    @Test
    fun setDirectLaunchCreatesFolder() = assertEquals(true, FolderEdits.setDirectLaunch(Folders(), "new", true).byPanel.getValue("new").directLaunch)

    @Test
    fun musicPlayerIsOnlyOnePanel() {
        val a = FolderEdits.setMusicPlayer(Folders(), "p1", true)
        val b = FolderEdits.setMusicPlayer(a, "p2", true)
        assertEquals("p2", b.musicPanel())
        assertFalse(b.byPanel.getValue("p1").musicPlayer)
        assertNull(FolderEdits.setMusicPlayer(b, "p2", false).musicPanel())
    }

    @Test
    fun oldFoldersJsonHasNoMusicPlayer() {
        val f = Json { ignoreUnknownKeys = true }.decodeFromString(Folders.serializer(), """{"byPanel":{"p1":{"apps":[]}}}""")
        assertNull(f.musicPanel())
    }
}
