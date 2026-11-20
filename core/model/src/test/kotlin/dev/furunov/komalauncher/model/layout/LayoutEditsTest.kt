package dev.furunov.komalauncher.model.layout

import dev.furunov.komalauncher.model.geometry.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LayoutEditsTest {
    private val frame = Frame(refWidth = 1000f, gutter = 0f, bleed = true)
    private val art = PanelArt("img", 1.5f, zoom = 2f)
    private val one = Layout(LayoutShape.Tree(LayoutNode.Leaf(Panel(PanelId("p1"), PanelContent.Folder(listOf("x")), "よく使う", art)), frame))

    private fun polygonOf(
        layout: Layout,
        id: String,
    ) = LayoutResolver.resolve(layout, 1000f, 2000f).panels.first { it.panel.id.value == id }.polygon.points

    @Test
    fun splitKeepsContentInFirstChild() {
        val (after, fresh) = assertNotNull(LayoutEdits.split(one, PanelId("p1"), SplitDirection.HORIZONTAL, polygonOf(one, "p1")))
        assertEquals(PanelId("p2"), fresh)
        val r = LayoutResolver.resolve(after, 1000f, 2000f)
        assertEquals(listOf("p1", "p2"), r.panels.map { it.panel.id.value })
        // 元の中身（フォルダの初期値・画像）は上のコマが引き継ぐ
        assertEquals(PanelContent.Folder(listOf("x")), r.panels[0].panel.content)
        assertEquals(art, r.panels[0].panel.art)
        assertEquals(PanelContent.Folder(emptyList()), r.panels[1].panel.content)
        // 新しいコマには名前を付けない
        assertNull(r.panels[1].panel.label)
        // 横の線は外接長方形の中心を通る
        assertEquals(1000f, r.panels[0].polygon.bounds.bottom, 0.5f)
        assertEquals(1000f, r.panels[1].polygon.bounds.top, 0.5f)
    }

    @Test
    fun verticalSplitPutsOriginalOnLeft() {
        val (after, _) = assertNotNull(LayoutEdits.split(one, PanelId("p1"), SplitDirection.VERTICAL, polygonOf(one, "p1")))
        val r = LayoutResolver.resolve(after, 1000f, 2000f)
        assertEquals("p1", r.panels[0].panel.id.value)
        assertEquals(500f, r.panels[0].polygon.bounds.right, 0.5f)
        assertEquals(500f, r.panels[1].polygon.bounds.left, 0.5f)
    }

    @Test
    fun splitNestedPanelUsesItsOwnPolygon() {
        val (a, _) = assertNotNull(LayoutEdits.split(one, PanelId("p1"), SplitDirection.HORIZONTAL, polygonOf(one, "p1")))
        val (b, fresh) = assertNotNull(LayoutEdits.split(a, PanelId("p2"), SplitDirection.VERTICAL, polygonOf(a, "p2")))
        assertEquals(PanelId("p3"), fresh)
        val r = LayoutResolver.resolve(b, 1000f, 2000f)
        val p3 = r.panels.first { it.panel.id == fresh }.polygon.bounds
        assertEquals(500f, p3.left, 0.5f)
        assertEquals(1000f, p3.top, 0.5f)
    }

    @Test
    fun mergeKeepsSelectedAndRemovesSibling() {
        val (a, fresh) = assertNotNull(LayoutEdits.split(one, PanelId("p1"), SplitDirection.HORIZONTAL, polygonOf(one, "p1")))
        val kept = LayoutEdits.merge(a, fresh)
        assertEquals(listOf(fresh), kept.panels.map { it.id })
        assertIs<LayoutNode.Leaf>(assertIs<LayoutShape.Tree>(kept.shape).root)
    }

    @Test
    fun mergeDoesNothingWhenSiblingIsSplitOrAtRoot() {
        val hero = LayoutEdits.template(LayoutEdits.Template.HERO, one)
        val top = hero.panels.first()
        // 大ゴマの上のコマの隣は、さらに割られている
        assertSame(hero, LayoutEdits.merge(hero, top.id))
        assertSame(one, LayoutEdits.merge(one, PanelId("p1")))
        assertSame(one, LayoutEdits.merge(one, PanelId("missing")))
    }

    @Test
    fun moveCutChangesOnlyThatEnd() {
        val hero = LayoutEdits.template(LayoutEdits.Template.HERO, one)
        val moved = LayoutEdits.moveCut(hero, "b", CutEnd.A, 1.2f)
        val node = assertIs<LayoutNode.Split>(LayoutEdits.nodeAt(assertIs<LayoutShape.Tree>(moved.shape).root, "b"))
        assertEquals(0.2f, node.ta, 1e-5f)
        assertEquals(0.6503f, node.tb, 1e-5f)
        // ウィジェットのコマ（道筋 baa）を割っている線は ba
        assertEquals("ba", LayoutEdits.parentCutPath(hero, hero.panels[1].id))
        assertNull(LayoutEdits.parentCutPath(one, PanelId("p1")))
    }

    @Test
    fun cycleKindGoesFolderWidgetDrawer() {
        val w = LayoutEdits.cycleKind(one, PanelId("p1"))
        assertEquals(PanelContent.Widget, w.panels[0].content)
        val d = LayoutEdits.cycleKind(w, PanelId("p1"))
        assertEquals(PanelContent.Drawer, d.panels[0].content)
        assertIs<PanelContent.Folder>(LayoutEdits.cycleKind(d, PanelId("p1")).panels[0].content)
    }

    @Test
    fun templateAvoidsExistingIdsAndKeepsFrame() {
        val hero = LayoutEdits.template(LayoutEdits.Template.HERO, one)
        assertEquals(5, hero.panels.size)
        assertFalse(hero.panels.any { it.id == PanelId("p1") })
        assertEquals(frame, assertIs<LayoutShape.Tree>(hero.shape).frame)
        assertTrue(hero.panels.any { it.content == PanelContent.Widget })
        assertTrue(hero.panels.any { it.content == PanelContent.Drawer })
        assertEquals(5, LayoutResolver.resolve(hero, 1220f, 2712f).panels.size)
    }

    @Test
    fun versionOneIsNotEdited() {
        val v1 = Layout.of(listOf(Panel(PanelId("a"), PanelContent.Widget)))
        assertNull(LayoutEdits.split(v1, PanelId("a"), SplitDirection.HORIZONTAL, listOf(Point(0f, 0f), Point(1f, 0f), Point(1f, 1f))))
        assertSame(v1, LayoutEdits.merge(v1, PanelId("a")))
    }

    @Test
    fun historyKeepsLatestFifty() {
        val h = EditHistory<Int>()
        repeat(60) { h.push(it) }
        var current = 60
        val popped = generateSequence { h.undo(current)?.also { current = it } }.toList()
        assertEquals(50, popped.size)
        assertEquals(59, popped.first())
        assertEquals(10, popped.last())
        assertFalse(h.canUndo)
    }

    @Test
    fun redoReturnsUndoneStatesUntilANewEdit() {
        val h = EditHistory<Int>()
        h.push(0)
        h.push(1)
        // 今は 2。2 回戻すと 0、1 回やり直すと 1
        assertEquals(1, h.undo(2))
        assertEquals(0, h.undo(1))
        assertEquals(1, h.redo(0))
        assertTrue(h.canRedo)
        // 新しい操作を積むと、やり直す先は無くなる
        h.push(1)
        assertFalse(h.canRedo)
        assertNull(h.redo(5))
    }

    @Test
    fun placeImageKeepsDim() {
        val dimmed = LayoutEdits.updatePanel(one, PanelId("p1")) { it.copy(art = art.copy(dim = 0.4f)) }
        val placed = LayoutEdits.placeImage(dimmed, PanelId("p1"), PanelArt("new", 1f))
        assertEquals(PanelArt("new", 1f, dim = 0.4f), placed.panel(PanelId("p1"))?.art)
    }

    @Test
    fun placeImageOnEmptyPanelHasNoDim() {
        val bare = LayoutEdits.updatePanel(one, PanelId("p1")) { it.copy(art = null) }
        assertEquals(PanelArt("new", 1f), LayoutEdits.placeImage(bare, PanelId("p1"), PanelArt("new", 1f)).panel(PanelId("p1"))?.art)
    }

    @Test
    fun setDimClampsAndNeedsArt() {
        assertEquals(1f, LayoutEdits.setDim(one, PanelId("p1"), 2f).panel(PanelId("p1"))?.art?.dim)
        val bare = LayoutEdits.updatePanel(one, PanelId("p1")) { it.copy(art = null) }
        assertNull(LayoutEdits.setDim(bare, PanelId("p1"), 0.5f).panel(PanelId("p1"))?.art)
    }
}
