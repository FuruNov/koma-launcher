package dev.furunov.komalauncher.model.folders

import dev.furunov.komalauncher.model.apps.AppKey
import dev.furunov.komalauncher.model.geometry.Point
import dev.furunov.komalauncher.model.geometry.Polygon
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.Panel
import dev.furunov.komalauncher.model.layout.PanelContent
import dev.furunov.komalauncher.model.layout.PanelId
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun addTakesFromUnassigned() {
        val f = FolderEdits.add(base.copy(unassigned = listOf(c)), "p1", c)
        assertEquals(emptyList(), f.unassigned)
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

    private fun layout(vararg panels: Pair<String, List<String>>) = Layout(
        version = 1,
        image = null,
        aspect = 1f,
        panels =
        panels.map { (id, apps) ->
            Panel(PanelId(id), Polygon(listOf(Point(0f, 0f), Point(1f, 0f), Point(1f, 1f))), PanelContent.Folder(apps))
        },
    )

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
}
