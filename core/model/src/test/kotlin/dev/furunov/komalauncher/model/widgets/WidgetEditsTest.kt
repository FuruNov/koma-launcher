package dev.furunov.komalauncher.model.widgets

import dev.furunov.komalauncher.model.geometry.Point
import dev.furunov.komalauncher.model.geometry.Polygon
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.Panel
import dev.furunov.komalauncher.model.layout.PanelContent
import dev.furunov.komalauncher.model.layout.PanelId
import kotlin.test.Test
import kotlin.test.assertEquals

class WidgetEditsTest {
    private val tri = Polygon(listOf(Point(0f, 0f), Point(1f, 0f), Point(1f, 1f)))

    private fun layout(vararg panels: Pair<String, PanelContent>) = Layout(1, null, 1f, panels.map { (id, c) -> Panel(PanelId(id), tri, c) })

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
