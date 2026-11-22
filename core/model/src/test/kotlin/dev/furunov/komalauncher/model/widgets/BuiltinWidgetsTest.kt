package dev.furunov.komalauncher.model.widgets

import dev.furunov.komalauncher.model.geometry.Point
import dev.furunov.komalauncher.model.layout.BuiltinWidget
import dev.furunov.komalauncher.model.layout.ClockSpec
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.LayoutEdits
import dev.furunov.komalauncher.model.layout.LayoutResolver
import dev.furunov.komalauncher.model.layout.LayoutShape
import dev.furunov.komalauncher.model.layout.Panel
import dev.furunov.komalauncher.model.layout.PanelContent
import dev.furunov.komalauncher.model.layout.PanelId
import dev.furunov.komalauncher.model.layout.PanelText
import dev.furunov.komalauncher.model.layout.WidgetStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuiltinWidgetsTest {
    private val w = 1220f
    private val h = 2712f

    private fun hero(
        clock: ClockSpec = ClockSpec(enabled = true, x = 0.52f, y = 0.22f, size = 1f),
        masthead: String = "KOMA",
    ): Layout {
        val base = LayoutEdits.template(LayoutEdits.Template.HERO, Layout.of(listOf(Panel(PanelId("x"), PanelContent.Drawer))))
        val t = base.shape as LayoutShape.Tree
        return Layout(t.copy(clock = clock, text = t.text.copy(masthead = masthead, mastheadY = 0.48f)))
    }

    @Test
    fun clockRectRoundTrips() {
        val c = Point(600f, 500f)
        val (center, k) = BuiltinWidgets.clockIn(BuiltinWidgets.clockRect(c, 0.8f))
        assertEquals(c.x, center.x, 1e-3f)
        assertEquals(c.y, center.y, 1e-3f)
        assertEquals(0.8f, k, 1e-5f)
    }

    @Test
    fun textRectRoundTrips() {
        val (size, width) = BuiltinWidgets.textIn(BuiltinWidgets.textRect(Point(10f, 20f), 120f, 900f))
        assertEquals(120f, size, 1e-3f)
        assertEquals(900f, width, 1e-3f)
    }

    @Test
    fun migrateKeepsPositionAndSize() {
        val layout = hero()
        val panels = LayoutResolver.resolve(layout, w, h).panels
        val migrated = BuiltinWidgets.migrate(layout, panels, w, h)!!
        val t = migrated.shape as LayoutShape.Tree
        assertTrue(!t.clock.enabled)
        assertEquals("", t.text.masthead)
        val (text, clock) = t.widgets
        assertEquals(BuiltinWidget.TEXT, text.builtin)
        assertEquals(WidgetStyle(text = "KOMA"), text.style)
        assertEquals(BuiltinWidget.CLOCK, clock.builtin)
        // 時計の中心と大きさが以前と同じ
        val b = panels.first { it.panel.id == PanelText.clockPanel(panels, (layout.shape as LayoutShape.Tree).clock) }.polygon.bounds
        val r = dev.furunov.komalauncher.model.geometry.Rect(clock.x * w, clock.y * h, (clock.x + clock.w) * w, (clock.y + clock.h) * h)
        val (center, k) = BuiltinWidgets.clockIn(r)
        assertEquals(b.left + 0.52f * b.width, center.x, 0.5f)
        assertEquals(b.top + 0.22f * b.height, center.y, 0.5f)
        assertEquals(1f, k, 1e-4f)
        // もう一度読み込んでも、書き換え直さない
        assertNull(BuiltinWidgets.migrate(migrated, LayoutResolver.resolve(migrated, w, h).panels, w, h))
    }

    @Test
    fun nothingToMigrate() = assertNull(BuiltinWidgets.migrate(hero(ClockSpec(enabled = false), ""), emptyList(), w, h))

    @Test
    fun builtinStyleRoundTripsThroughJson() {
        val layout = BuiltinWidgets.migrate(hero(), LayoutResolver.resolve(hero(), w, h).panels, w, h)!!
        val styled = WidgetPlacement.widgetsOf(layout).map { it.copy(style = it.style.copy(font = "serif", weight = 300, h24 = false)) }
        val again = Layout((layout.shape as LayoutShape.Tree).copy(widgets = styled))
        val parsed = (dev.furunov.komalauncher.model.layout.LayoutParser.parse(dev.furunov.komalauncher.model.layout.LayoutParser.encode(again)) as dev.furunov.komalauncher.model.layout.LayoutResult.Ok).layout
        assertEquals(again, parsed)
    }
}
