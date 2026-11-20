package dev.furunov.komalauncher.model.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutV2ParserTest {
    // モックアップの state と同じ形の木（中身は短くした）
    private val valid =
        """
        { "version": 2,
          "frame": { "refWidth": 1220, "margin": 24, "gutter": 9, "gutterColor": "#ffffff", "bleed": false,
                     "border": { "style": "double", "width": 24, "color": "#111111" } },
          "tree": { "cut": { "ta": 0.3246, "tb": 0.7999 },
                    "a": { "panel": { "id": "p43", "kind": "folder", "label": "よく使う", "apps": ["com.android.chrome"],
                                      "art": { "image": "pic-a-5", "zoom": 1, "dx": -0.067, "dy": 0.0015 } } },
                    "b": { "cut": { "ta": 0.14, "tb": 0.65 },
                           "a": { "panel": { "id": "p44", "kind": "widget", "art": { "image": "pic-b-3", "aspect": 1.7766 } } },
                           "b": { "panel": { "id": "p47", "kind": "drawer", "label": "その他" } } } } }
        """.trimIndent()

    @Test
    fun parsesTree() {
        val layout = assertIs<LayoutResult.Ok>(LayoutParser.parse(valid)).layout
        assertEquals(2, layout.version)
        assertEquals(listOf("p43", "p44", "p47"), layout.panels.map { it.id.value })
        assertEquals(PanelContent.Folder(listOf("com.android.chrome")), layout.panels[0].content)
        assertEquals(PanelContent.Widget, layout.panels[1].content)
        assertEquals(PanelContent.Drawer, layout.panels[2].content)
        assertEquals(PanelArt("pic-a-5", aspect = null, zoom = 1f, dx = -0.067f, dy = 0.0015f), layout.panels[0].art)
        assertEquals(1.7766f, layout.panels[1].art?.aspect)
        assertNull(layout.panels[2].art)
        assertEquals(setOf("pic-a-5", "pic-b-3"), layout.images)
        val shape = assertIs<LayoutShape.Tree>(layout.shape)
        assertEquals(0xFFFFFFFF, shape.frame.gutterColor)
        assertEquals(Border(BorderStyle.DOUBLE, 24f, 0xFF111111), shape.frame.border)
        assertEquals(9f, shape.frame.gutter)
    }

    @Test
    fun encodeThenParseIsSame() {
        val layout = assertIs<LayoutResult.Ok>(LayoutParser.parse(valid)).layout
        val again = assertIs<LayoutResult.Ok>(LayoutParser.parse(LayoutParser.encode(layout))).layout
        assertEquals(layout, again)
    }

    @Test
    fun encodeThenParseKeepsVersion1() {
        val v1 =
            """{"version":1,"image":"page.webp","aspect":0.45,"panels":[{"id":"a","kind":"folder","apps":["x"],"polygon":[[0,0],[1,0],[1,1]]}]}"""
        val layout = assertIs<LayoutResult.Ok>(LayoutParser.parse(v1)).layout
        val text = LayoutParser.encode(layout)
        val again = assertIs<LayoutResult.Ok>(LayoutParser.parse(text)).layout
        assertEquals(1, again.version)
        assertEquals(layout.panels, again.panels)
        assertEquals(
            assertIs<LayoutShape.Fixed>(layout.shape).panels.map { it.polygon.points },
            assertIs<LayoutShape.Fixed>(again.shape).panels.map { it.polygon.points },
        )
    }

    @Test
    fun frameDefaultsWhenOmitted() {
        val layout = assertIs<LayoutResult.Ok>(LayoutParser.parse("""{"version":2,"tree":{"panel":{"id":"x","kind":"folder"}}}""")).layout
        assertEquals(Frame(), assertIs<LayoutShape.Tree>(layout.shape).frame)
    }

    @Test
    fun errors() {
        fun err(text: String) = assertIs<LayoutResult.Error>(LayoutParser.parse(text)).message
        assertTrue(err("""{"version":2}""").contains("tree"))
        // 枝の子が欠けている
        assertTrue(err("""{"version":2,"tree":{"cut":{"ta":0.1,"tb":0.6},"a":{"panel":{"id":"x","kind":"folder"}}}}""").contains("tree"))
        // 葉と枝の両方
        assertTrue(err("""{"version":2,"tree":{"panel":{"id":"x","kind":"folder"},"cut":{"ta":0.1,"tb":0.6}}}""").contains("両方"))
        assertTrue(
            err(
                """{"version":2,"tree":{"cut":{"ta":0.1,"tb":0.6},"a":{"panel":{"id":"x","kind":"folder"}},"b":{"panel":{"id":"x","kind":"widget"}}}}""",
            ).contains("重複"),
        )
        assertTrue(err("""{"version":2,"frame":{"gutterColor":"white"},"tree":{"panel":{"id":"x","kind":"folder"}}}""").contains("#rrggbb"))
        // 画像の id で別の場所のファイルを指せない
        assertTrue(err("""{"version":2,"tree":{"panel":{"id":"x","kind":"folder","art":{"image":"../x"}}}}""").contains("art.image"))
        assertTrue(err("""{"version":1,"image":"../a.png","aspect":1,"panels":[{"id":"a","kind":"widget","polygon":[[0,0],[1,0],[1,1]]}]}""").contains("image"))
        assertTrue(err("""{"version":2,"tree":{"panel":{"id":"x","kind":"folder","art":{"image":"a","aspect":0}}}}""").contains("aspect"))
        // 桁外れの値は描画の大きさを壊すので、読み込みで止める
        assertTrue(err("""{"version":2,"tree":{"panel":{"id":"x","kind":"folder","art":{"image":"a","zoom":1e40}}}}""").contains("zoom"))
        assertTrue(err("""{"version":2,"tree":{"panel":{"id":"x","kind":"folder","art":{"image":"a","dx":1e40}}}}""").contains("dx"))
        assertTrue(err("""{"version":2,"frame":{"gutter":5000},"tree":{"panel":{"id":"x","kind":"folder"}}}""").contains("gutter"))
        assertTrue(err("""{"version":1,"aspect":1e40,"panels":[{"id":"a","kind":"widget","polygon":[[0,0],[1,0],[1,1]]}]}""").contains("aspect"))
    }
}

class SampleLayoutTest {
    @Test
    fun bundledSampleIsValid() {
        // 見本が壊れていると、初回の起動で何も表示できない
        val text = java.io.File("../../app/src/main/assets/sample/layout.json").readText()
        val layout = assertIs<LayoutResult.Ok>(LayoutParser.parse(text)).layout
        assertEquals(2, layout.version)
        assertTrue(layout.panels.all { it.id.value.startsWith("sample-") })
        assertTrue(layout.panels.any { it.content == PanelContent.Drawer })
        assertEquals(5, LayoutResolver.resolve(layout, 1220f, 2712f).panels.size)
    }

    @Test
    fun serializerRoundTripsThroughJson() {
        val text = java.io.File("../../app/src/main/assets/sample/layout.json").readText()
        val layout = assertIs<LayoutResult.Ok>(LayoutParser.parse(text)).layout
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        assertEquals(layout, json.decodeFromString(LayoutParser.serializer, json.encodeToString(LayoutParser.serializer, layout)))
    }
}
