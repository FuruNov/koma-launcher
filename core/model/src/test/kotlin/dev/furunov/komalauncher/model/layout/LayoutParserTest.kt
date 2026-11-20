package dev.furunov.komalauncher.model.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
        assertEquals(1, layout.version)
        assertEquals(3, layout.panels.size)
        assertEquals(PanelContent.Folder(listOf("com.android.chrome")), layout.panels[0].content)
        assertEquals(PanelContent.Widget, layout.panels[1].content)
        assertEquals("仕事", layout.panels[0].label)
        assertEquals(0.45f, assertIs<LayoutShape.Fixed>(layout.shape).aspect)
        assertEquals(setOf("page.webp"), layout.images)
    }

    @Test
    fun resolvedFixedPanelsKeepImageCoordinates() {
        val layout = assertIs<LayoutResult.Ok>(LayoutParser.parse(valid)).layout
        // 画像と同じ縦横比の画面なら、相対座標をそのまま拡大した位置になる
        val r = LayoutResolver.resolve(layout, 450f, 1000f)
        assertEquals(0.62f * 450f, r.panels[0].polygon.points[1].x, 0.01f)
        assertEquals(0f, r.panels[0].polygon.points[1].y, 0.01f)
        // 1 枚の絵を、どのコマも同じ位置に描く
        assertEquals(r.panels[0].art, r.panels[2].art)
        assertEquals(1000f, r.panels[0].art!!.height, 0.01f)
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
