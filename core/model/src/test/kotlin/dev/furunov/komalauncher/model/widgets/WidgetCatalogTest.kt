package dev.furunov.komalauncher.model.widgets

import kotlin.test.Test
import kotlin.test.assertEquals

class WidgetCatalogTest {
    private val entries =
        listOf(
            WidgetEntry("w/.Big", "天気（大）", "お天気", "w"),
            WidgetEntry("w/.Small", "天気（小）", "お天気", "w"),
            WidgetEntry("c/.Clock", "アナログ時計", "時計", "c"),
            WidgetEntry("c/.Digital", "デジタル時計", "時計", "c"),
            WidgetEntry("n/.Note", "メモ", "Notes", "n"),
        )

    @Test
    fun groupsByAppSortedByName() {
        val g = WidgetCatalog.group(entries, "")
        assertEquals(listOf("Notes", "お天気", "時計"), g.map { it.appLabel })
        assertEquals(listOf("天気（大）", "天気（小）"), g[1].widgets.map { it.label })
    }

    @Test
    fun filtersByWidgetName() {
        val g = WidgetCatalog.group(entries, "デジタル")
        assertEquals(listOf("デジタル時計"), g.single().widgets.map { it.label })
    }

    @Test
    fun appNameMatchKeepsAllWidgets() {
        val g = WidgetCatalog.group(entries, "notes")
        assertEquals(listOf("メモ"), g.single().widgets.map { it.label })
        assertEquals(2, WidgetCatalog.group(entries, "お天気").single().widgets.size)
    }

    @Test
    fun cellsFromDpOrTarget() {
        assertEquals(1 to 1, WidgetCatalog.cells(40f, 40f))
        assertEquals(4 to 2, WidgetCatalog.cells(250f, 110f))
        assertEquals(3 to 2, WidgetCatalog.cells(250f, 110f, targetWidth = 3))
    }
}

class WidgetReplaceTest {
    @Test
    fun replaceKeepsRect() {
        val spec = dev.furunov.komalauncher.model.layout.WidgetSpec("w", "a/.A", 0.1f, 0.2f, 0.3f, 0.4f)
        val tree =
            dev.furunov.komalauncher.model.layout.Layout(
                dev.furunov.komalauncher.model.layout.LayoutShape.Tree(
                    dev.furunov.komalauncher.model.layout.LayoutNode.Leaf(
                        dev.furunov.komalauncher.model.layout.Panel(dev.furunov.komalauncher.model.layout.PanelId("p"), dev.furunov.komalauncher.model.layout.PanelContent.Folder(emptyList())),
                    ),
                    widgets = listOf(spec),
                ),
            )
        assertEquals(listOf(spec.copy(provider = "b/.B")), WidgetPlacement.widgetsOf(WidgetPlacement.replace(tree, "w", "b/.B")))
    }
}

class WidgetOrderTest {
    private fun spec(id: String, front: Boolean = false) = dev.furunov.komalauncher.model.layout.WidgetSpec(id, "a/.A", 0f, 0f, 0.5f, 0.5f, front)

    private fun layout(vararg ws: dev.furunov.komalauncher.model.layout.WidgetSpec) = dev.furunov.komalauncher.model.layout.Layout(
        dev.furunov.komalauncher.model.layout.LayoutShape.Tree(
            dev.furunov.komalauncher.model.layout.LayoutNode.Leaf(
                dev.furunov.komalauncher.model.layout.Panel(dev.furunov.komalauncher.model.layout.PanelId("p"), dev.furunov.komalauncher.model.layout.PanelContent.Folder(emptyList())),
            ),
            widgets = ws.toList(),
        ),
    )

    private fun order(
        l: dev.furunov.komalauncher.model.layout.Layout,
    ) = WidgetPlacement.widgetsOf(l).map { it.id + if (it.front) "+" else "" }

    @Test
    fun forwardCrossesPersonThenPassesOtherWidgets() {
        val l = layout(spec("a"), spec("b"), spec("c", front = true))
        val f1 = WidgetPlacement.reorder(l, "b", dev.furunov.komalauncher.model.layout.LayerOrder.Move.FORWARD)
        assertEquals(listOf("a", "b+", "c+"), order(f1))
        val f2 = WidgetPlacement.reorder(f1, "b", dev.furunov.komalauncher.model.layout.LayerOrder.Move.FORWARD)
        assertEquals(listOf("a", "c+", "b+"), order(f2))
    }

    @Test
    fun backAndFront() {
        val l = layout(spec("a"), spec("b"), spec("c", front = true))
        assertEquals(listOf("c", "a", "b"), order(WidgetPlacement.reorder(l, "c", dev.furunov.komalauncher.model.layout.LayerOrder.Move.BACK)))
        assertEquals(listOf("b", "c+", "a+"), order(WidgetPlacement.reorder(l, "a", dev.furunov.komalauncher.model.layout.LayerOrder.Move.FRONT)))
        // いちばん後ろでさらに後ろへ送っても変わらない
        assertEquals(listOf("a", "b", "c+"), order(WidgetPlacement.reorder(l, "a", dev.furunov.komalauncher.model.layout.LayerOrder.Move.BACKWARD)))
    }
}
