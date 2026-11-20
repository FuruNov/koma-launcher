package dev.furunov.komalauncher.model.layout

import dev.furunov.komalauncher.model.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PanelTextTest {
    private val box = Rect(0f, 0f, 600f, 1000f)

    @Test
    fun labelShowsOnlyWhatTheUserTyped() {
        val folder = Panel(PanelId("a"), PanelContent.Folder(emptyList()), "道具")
        // 英語の名前が無ければ、訳さずに名前を出す
        assertEquals("道具", PanelText.labelOf(folder, null, english = true))
        assertEquals("道具", PanelText.labelOf(folder, null, english = false))
        val named = folder.copy(labelEn = "Tools")
        assertEquals("Tools", PanelText.labelOf(named, null, english = true))
        assertEquals("道具", PanelText.labelOf(named, null, english = false))
        // ホームの長押しで付けた名前が優先
        assertEquals("ゲーム", PanelText.labelOf(folder, "ゲーム", english = false))
        // 全アプリ一覧のコマも、付けた名前を出す。名前が無ければ出さない
        assertEquals("その他", PanelText.labelOf(Panel(PanelId("d"), PanelContent.Drawer, "その他"), null, english = true))
        assertNull(PanelText.labelOf(Panel(PanelId("d"), PanelContent.Drawer), null, english = true))
        assertNull(PanelText.labelOf(Panel(PanelId("e"), PanelContent.Folder(emptyList()), " "), null, english = false))
        assertNull(PanelText.labelOf(Panel(PanelId("w"), PanelContent.Widget, "ウィジェット"), null, english = true))
    }

    @Test
    fun withoutMapLabelIsLargestAtTopLeft() {
        val p = PanelText.placeLabel(box, 9, vertical = true, english = true, cover = null)
        assertEquals(p.boxWidth / 2f, p.centerX, 1e-3f)
        assertEquals(p.boxHeight / 2f, p.centerY, 1e-3f)
        // 縦書きの英語: 幅は 0.2 × 600 か、高さ 0.7 × 1000 / (9 × 0.62) の小さいほう
        assertEquals(minOf(120f, 700f / (9 * 0.62f)), p.size, 1e-3f)
    }

    @Test
    fun labelAvoidsPerson() {
        // 左半分に人物がいる
        val cover: (Rect) -> Float = { r -> ((300f - r.left).coerceIn(0f, r.width) / r.width) }
        val p = PanelText.placeLabel(box, 5, vertical = true, english = true, cover = cover)
        assertTrue(p.centerX - p.boxWidth / 2f >= 300f - 1e-3f, "人物のいない右半分に置く: $p")
    }

    @Test
    fun labelStaysInsideBox() {
        listOf(true, false).forEach { vertical ->
            listOf(true, false).forEach { en ->
                val p = PanelText.placeLabel(box, 7, vertical, en) { r -> if (r.top < 500f) 1f else 0f }
                assertTrue(p.centerX - p.boxWidth / 2f >= -1e-3f && p.centerX + p.boxWidth / 2f <= 600.001f)
                assertTrue(p.centerY - p.boxHeight / 2f >= -1e-3f && p.centerY + p.boxHeight / 2f <= 1000.001f)
            }
        }
    }

    @Test
    fun shrinksOnlyWhenItHelpsEnough() {
        // どこも半分隠れるなら、縮めても得をしないので最大のまま
        val p = PanelText.placeLabel(box, 5, vertical = false, english = true) { 0.5f }
        assertEquals(PanelText.placeLabel(box, 5, vertical = false, english = true, cover = null).size, p.size, 1e-3f)
    }

    @Test
    fun coverSamplesGrid() {
        // 2×1 の地図: 左が人物
        val grid = floatArrayOf(1f, 0f)
        val art = Rect(0f, 0f, 200f, 100f)
        assertEquals(1f, PanelText.cover(grid, 2, 1, art, Rect(0f, 0f, 100f, 100f)), 1e-4f)
        assertEquals(0f, PanelText.cover(grid, 2, 1, art, Rect(100f, 0f, 200f, 100f)), 1e-4f)
        assertEquals(0.5f, PanelText.cover(grid, 2, 1, art, Rect(0f, 0f, 200f, 100f)), 1e-4f)
        // 絵の外は数えない
        assertEquals(0f, PanelText.cover(grid, 2, 1, art, Rect(300f, 0f, 400f, 100f)), 1e-4f)
    }

    @Test
    fun clockAndMastheadPanels() {
        val layout = LayoutEdits.template(LayoutEdits.Template.HERO, Layout.of(listOf(Panel(PanelId("x"), PanelContent.Widget))))
        val r = LayoutResolver.resolve(layout, 1220f, 2712f).panels
        val biggest = r.maxBy { it.polygon.bounds.width * it.polygon.bounds.height }.panel.id
        assertNull(PanelText.clockPanel(r, ClockSpec()))
        assertEquals(biggest, PanelText.clockPanel(r, ClockSpec(enabled = true, panel = PanelId("gone"))))
        assertEquals(r[2].panel.id, PanelText.clockPanel(r, ClockSpec(enabled = true, panel = r[2].panel.id)))
        assertNull(PanelText.mastheadPanel(r, TextSpec(), ClockSpec()))
        assertEquals(biggest, PanelText.mastheadPanel(r, TextSpec(masthead = "KOMA"), ClockSpec()))
    }
}
