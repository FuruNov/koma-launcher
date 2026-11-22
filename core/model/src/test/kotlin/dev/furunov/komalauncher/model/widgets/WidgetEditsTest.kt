package dev.furunov.komalauncher.model.widgets

import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.LayoutNode
import dev.furunov.komalauncher.model.layout.LayoutShape
import dev.furunov.komalauncher.model.layout.Panel
import dev.furunov.komalauncher.model.layout.PanelContent
import dev.furunov.komalauncher.model.layout.PanelId
import dev.furunov.komalauncher.model.layout.WidgetSpec
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

class WidgetEditsTest {
    private fun layout(vararg panels: Pair<String, PanelContent>) = Layout.of(panels.map { (id, c) -> Panel(PanelId(id), c) })

    private val assigned =
        WidgetAssignments(byPanel = mapOf("w1" to WidgetRef(1, "a/.A"), "w2" to WidgetRef(2, "b/.B"), "f1" to WidgetRef(3, "c/.C")))

    @Test
    fun keepsLiveWidgetPanels() = assertEquals(setOf("w2", "f1"), WidgetEdits.orphans(assigned, layout("w1" to PanelContent.Widget)))

    @Test
    fun panelThatBecameFolderIsOrphan() = assertEquals(
        setOf("f1"),
        WidgetEdits.orphans(assigned, layout("w1" to PanelContent.Widget, "w2" to PanelContent.Widget, "f1" to PanelContent.Folder(emptyList()))),
    )

    @Test
    fun nothingAssigned() = assertEquals(emptySet(), WidgetEdits.orphans(WidgetAssignments(), layout("w1" to PanelContent.Widget)))
}

class WidgetPlacementTest {
    private val tree = Layout(LayoutShape.Tree(LayoutNode.Leaf(Panel(PanelId("widget-1"), PanelContent.Folder(emptyList())))))

    @Test
    fun newIdAvoidsPanelsAndWidgets() {
        assertEquals("widget-2", WidgetPlacement.newId(tree))
        val placed = WidgetPlacement.add(tree, WidgetSpec("widget-2", "a/.A", 0f, 0f, 0.5f, 0.2f))
        assertEquals("widget-3", WidgetPlacement.newId(placed))
    }

    @Test
    fun initialIsCenteredAndClamped() {
        val s = WidgetPlacement.initial(tree, "w", "a/.A", widthDp = 200f, heightDp = 1000f, screenWidthDp = 400f, screenHeightDp = 800f)
        assertEquals(WidgetSpec("w", "a/.A", 0.25f, 0.04f, 0.5f, 0.92f), s.copy(y = (s.y * 100).roundToInt() / 100f))
    }

    @Test
    fun secondWidgetIsShiftedDown() {
        val first = WidgetPlacement.initial(tree, "a", "a/.A", 200f, 160f, 400f, 800f)
        val second = WidgetPlacement.initial(WidgetPlacement.add(tree, first), "b", "a/.A", 200f, 160f, 400f, 800f)
        assertEquals(first.y + 0.05f, second.y, 1e-4f)
    }

    @Test
    fun freeWidgetsAreNotOrphans() {
        val placed = WidgetPlacement.add(tree, WidgetSpec("widget-2", "a/.A", 0f, 0f, 0.5f, 0.2f))
        val a = WidgetAssignments(byPanel = mapOf("widget-2" to WidgetRef(1, "a/.A"), "widget-9" to WidgetRef(2, "a/.A")))
        assertEquals(setOf("widget-9"), WidgetEdits.orphans(a, placed))
        assertEquals(setOf("widget-2", "widget-9"), WidgetEdits.orphans(a, WidgetPlacement.remove(placed, "widget-2")))
    }
}
