package dev.furunov.komalauncher.model.layout

import dev.furunov.komalauncher.model.geometry.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PartEditsTest {
    // 2 つに割ったコマ（p1 と p2）。画面は 1000×2000
    private val layout =
        Layout(
            LayoutShape.Tree(
                LayoutNode.Split(0.875f, 0.375f, LayoutNode.Leaf(Panel(PanelId("p1"), PanelContent.Folder(emptyList()))), LayoutNode.Leaf(Panel(PanelId("p2"), PanelContent.Folder(emptyList())))),
                Frame(refWidth = 1000f, gutter = 0f, bleed = true),
                clock = ClockSpec(enabled = true, panel = PanelId("p1"), x = 0.5f, y = 0.5f, size = 1f),
            ),
        )
    private val panels = LayoutResolver.resolve(layout, 1000f, 2000f).panels

    @Test
    fun clockIsFoundAndMovesToThePanelUnderIt() {
        val c = PartEdits.clockCenter(layout, panels)!!
        val part = assertIs<Part.Clock>(PartEdits.textAt(layout, panels, 1000f, c))
        // もう一方のコマの中心まで動かすと、時計はそのコマに置かれる
        val other = panels.first { it.panel.id == PanelId("p2") }.polygon.centroid
        val moved = PartEdits.move(layout, panels, part, other.x - c.x, other.y - c.y)
        assertEquals(PanelId("p2"), (moved.shape as LayoutShape.Tree).clock.panel)
    }

    @Test
    fun iconsMoveWithinTheirPanelAndScaleIsClamped() {
        val part = Part.Icons(PanelId("p1"), Point(500f, 500f))
        val moved = PartEdits.move(layout, panels, part, 100f, 0f)
        val at = moved.panel(PanelId("p1"))!!.iconAt!!
        assertEquals(0.6f, at.x, 0.01f)
        assertEquals(2f, PartEdits.scaleOf(PartEdits.setScale(moved, part, 9f), part))
        // 自動に戻すと、位置も大きさも消える
        val reset = PartEdits.reset(PartEdits.setScale(moved, part, 1.5f), part)
        assertNull(reset.panel(PanelId("p1"))!!.iconAt)
        assertEquals(1f, reset.panel(PanelId("p1"))!!.iconScale)
    }

    @Test
    fun labelsDoNotMoveAndClockSizeStaysInSliderRange() {
        val label = Part.Label(PanelId("p2"))
        assertEquals(layout, PartEdits.move(layout, panels, label, 50f, 50f))
        val clock = Part.Clock(Point(0f, 0f))
        assertEquals(1.6f, PartEdits.scaleOf(PartEdits.setScale(layout, clock, 3f), clock))
    }
}

class LayerOrderTest {
    private val all = LayerKind.entries.toSet()

    @Test
    fun movesSkippingWhatThePanelDoesNotHave() {
        val d = LayerOrder.DEFAULT
        // 時計と大見出しの無いコマで、アイコンを 1 つ前へ: 名前の前、人物の後ろ
        val present = setOf(LayerKind.ICONS, LayerKind.LABEL, LayerKind.PERSON)
        assertEquals(listOf(LayerKind.LABEL, LayerKind.ICONS, LayerKind.MASTHEAD, LayerKind.CLOCK, LayerKind.PERSON), LayerOrder.move(d, LayerKind.ICONS, LayerOrder.Move.FORWARD, present))
        // もう 1 つ前へ: 無い大見出しと時計を飛ばして、人物の前
        val twice = LayerOrder.move(LayerOrder.move(d, LayerKind.ICONS, LayerOrder.Move.FORWARD, present), LayerKind.ICONS, LayerOrder.Move.FORWARD, present)
        assertEquals(listOf(LayerKind.LABEL, LayerKind.MASTHEAD, LayerKind.CLOCK, LayerKind.PERSON, LayerKind.ICONS), twice)
        assertEquals(LayerKind.CLOCK, LayerOrder.move(d, LayerKind.CLOCK, LayerOrder.Move.FRONT, all).last())
        assertEquals(listOf(LayerKind.CLOCK, LayerKind.ICONS, LayerKind.LABEL, LayerKind.MASTHEAD, LayerKind.PERSON), LayerOrder.move(d, LayerKind.CLOCK, LayerOrder.Move.BACK, all))
        // いちばん後ろより後ろへは行かない（背景の絵より後ろには置けない）
        assertEquals(d, LayerOrder.move(d, LayerKind.ICONS, LayerOrder.Move.BACKWARD, all))
        assertEquals(d, LayerOrder.move(d, LayerKind.ICONS, LayerOrder.Move.BACK, all))
    }

    @Test
    fun fillsMissingAndDropsDuplicates() {
        assertEquals(listOf(LayerKind.PERSON, LayerKind.ICONS, LayerKind.LABEL, LayerKind.MASTHEAD, LayerKind.CLOCK), LayerOrder.of(listOf(LayerKind.PERSON, LayerKind.PERSON)))
    }
}
