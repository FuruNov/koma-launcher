package dev.furunov.komalauncher.model.layout

import dev.furunov.komalauncher.model.geometry.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutParserTest {
    private val valid =
        """
        { "version": 1, "image": "page.webp", "aspect": 0.45,
          "panels": [
            { "id": "p1", "kind": "folder", "label": "仕事",
              "polygon": [[0,0],[0.62,0],[0.55,0.3],[0,0.3]],
              "apps": ["com.android.chrome"] },
            { "id": "p2", "kind": "widget", "polygon": [[0.62,0],[1,0],[1,0.3],[0.55,0.3]] },
            { "id": "p3", "kind": "folder", "polygon": [[0.5,0.2],[0.8,0.2],[0.8,0.5],[0.5,0.5]] } ] }
        """.trimIndent()

    @Test
    fun parsesValid() {
        val layout = assertIs<LayoutResult.Ok>(LayoutParser.parse(valid)).layout
        assertEquals(3, layout.panels.size)
        assertEquals(PanelContent.Folder(listOf("com.android.chrome")), layout.panels[0].content)
        assertEquals(PanelContent.Widget, layout.panels[1].content)
        assertEquals("仕事", layout.panels[0].label)
        assertEquals(0.45f, layout.aspect)
    }

    @Test
    fun hitTestPrefersLaterPanel() {
        val layout = assertIs<LayoutResult.Ok>(LayoutParser.parse(valid)).layout
        assertEquals(PanelId("p1"), layout.panelAt(Point(0.1f, 0.1f))?.id)
        // p2 と p3 が重なる位置は後の p3
        assertEquals(PanelId("p3"), layout.panelAt(Point(0.7f, 0.25f))?.id)
        assertNull(layout.panelAt(Point(0.1f, 0.9f)))
    }

    @Test
    fun brokenJsonIsError() {
        assertIs<LayoutResult.Error>(LayoutParser.parse("{ \"panels\": [ "))
    }

    @Test
    fun unknownKindIsError() {
        assertIs<LayoutResult.Error>(
            LayoutParser.parse("""{"aspect":1,"panels":[{"id":"a","kind":"rocket","polygon":[[0,0],[1,0],[1,1]]}]}"""),
        )
    }

    @Test
    fun duplicateIdIsError() {
        val r =
            LayoutParser.parse(
                """{"aspect":1,"panels":[{"id":"a","kind":"widget","polygon":[[0,0],[1,0],[1,1]]},
                   {"id":"a","kind":"widget","polygon":[[0,0],[1,0],[1,1]]}]}""",
            )
        assertTrue(assertIs<LayoutResult.Error>(r).message.contains("重複"))
    }

    @Test
    fun tooFewVerticesIsError() {
        assertIs<LayoutResult.Error>(
            LayoutParser.parse("""{"aspect":1,"panels":[{"id":"a","kind":"widget","polygon":[[0,0],[1,0]]}]}"""),
        )
    }

    @Test
    fun futureVersionIsError() {
        assertIs<LayoutResult.Error>(
            LayoutParser.parse("""{"version":99,"aspect":1,"panels":[{"id":"a","kind":"widget","polygon":[[0,0],[1,0],[1,1]]}]}"""),
        )
    }

    @Test
    fun unknownKeysAreIgnored() {
        // 将来の項目を古いアプリで読んでも止まらないように
        assertIs<LayoutResult.Ok>(
            LayoutParser.parse("""{"aspect":1,"theme":"dark","panels":[{"id":"a","kind":"widget","polygon":[[0,0],[1,0],[1,1]],"glow":true}]}"""),
        )
    }
}
