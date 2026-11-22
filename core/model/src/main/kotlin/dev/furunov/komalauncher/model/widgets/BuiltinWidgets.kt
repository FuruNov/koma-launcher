package dev.furunov.komalauncher.model.widgets

import dev.furunov.komalauncher.model.geometry.Point
import dev.furunov.komalauncher.model.geometry.Polygon
import dev.furunov.komalauncher.model.geometry.Rect
import dev.furunov.komalauncher.model.layout.BuiltinWidget
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.LayoutShape
import dev.furunov.komalauncher.model.layout.PanelText
import dev.furunov.komalauncher.model.layout.ResolvedPanel
import dev.furunov.komalauncher.model.layout.WidgetSpec
import dev.furunov.komalauncher.model.layout.WidgetStyle

/**
 * ランチャーに内蔵のウィジェット（時計・テキスト）の置き場所の計算。
 * 以前は時計と大見出しをコマに属するものとして layout.json の clock と text.masthead に持っていた。
 * 今はほかのウィジェットと同じく widgets に持ち、画面の座標の長方形から、描く大きさを決める。
 */
object BuiltinWidgets {
    /** 時計の中心から、左右の端・日付の上端・時刻の下端までのおよその距離（k = 1 のとき）。 */
    const val CLOCK_HALF_WIDTH = 450f
    const val CLOCK_UP = 260f
    const val CLOCK_DOWN = 170f

    /** 大見出しの文字の大きさに対する、上下の半分の高さ。 */
    const val TEXT_HALF_HEIGHT = 0.6f

    /** 時計の大きさ k の時計が占める長方形（画面の座標）。 */
    fun clockRect(
        center: Point,
        k: Float,
    ): Rect = Rect(center.x - CLOCK_HALF_WIDTH * k, center.y - CLOCK_UP * k, center.x + CLOCK_HALF_WIDTH * k, center.y + CLOCK_DOWN * k)

    /** 長方形 [r]（画面の座標）に収まる時計の大きさ k と中心。縦横の比が時計と違えば、収まる方に合わせて横の中央に置く。 */
    fun clockIn(r: Rect): Pair<Point, Float> {
        val k = minOf(r.width / (2f * CLOCK_HALF_WIDTH), r.height / (CLOCK_UP + CLOCK_DOWN)).coerceAtLeast(0.01f)
        val extra = r.height - (CLOCK_UP + CLOCK_DOWN) * k
        return Point(r.center.x, r.top + extra / 2f + CLOCK_UP * k) to k
    }

    /** 大きさ [size]・幅 [width] の大見出しが占める長方形（画面の座標）。 */
    fun textRect(
        center: Point,
        size: Float,
        width: Float,
    ): Rect = Rect(center.x - width / 2f, center.y - size * TEXT_HALF_HEIGHT, center.x + width / 2f, center.y + size * TEXT_HALF_HEIGHT)

    /** 長方形 [r] に描くテキストの、文字の大きさと幅。 */
    fun textIn(r: Rect): Pair<Float, Float> = r.height / (2f * TEXT_HALF_HEIGHT) to r.width

    /**
     * 以前の時計（clock）と大見出し（text.masthead）を、同じ位置と大きさの内蔵のウィジェットに書き換える。
     * 書き換えたら clock を非表示にし、大見出しを空にする（次の読み込みで書き換え直さないように）。
     * 書き換えるものが無ければ null。[panels] と [viewWidth]・[viewHeight] は、今の画面に置いたコマ。
     */
    fun migrate(
        layout: Layout,
        panels: List<ResolvedPanel>,
        viewWidth: Float,
        viewHeight: Float,
    ): Layout? {
        val tree = layout.shape as? LayoutShape.Tree ?: return null
        val masthead = tree.text.masthead.trim()
        if (!tree.clock.enabled && masthead.isEmpty()) return null
        if (viewWidth <= 0f || viewHeight <= 0f) return null
        val scale = viewWidth / tree.frame.refWidth
        val inset = PanelText.textInset(tree.frame, scale)
        val added = mutableListOf<WidgetSpec>()
        var next = layout
        fun add(
            kind: BuiltinWidget,
            r: Rect,
            style: WidgetStyle,
        ) {
            val spec =
                WidgetSpec(
                    id = WidgetPlacement.newId(next),
                    provider = kind.provider,
                    x = r.left / viewWidth,
                    y = r.top / viewHeight,
                    w = r.width / viewWidth,
                    h = r.height / viewHeight,
                    style = style,
                )
            added += spec
            next = WidgetPlacement.add(next, spec)
        }
        // 大見出しを先に置く（以前も時計が大見出しの上に重なっていた）
        if (masthead.isNotEmpty()) {
            panels.firstOrNull { it.panel.id == PanelText.mastheadPanel(panels, tree.text, tree.clock) }?.let { p ->
                val (center, size, width) = mastheadOf(p.polygon, tree.text.mastheadY, inset, tree.text.mastheadScale)
                add(BuiltinWidget.TEXT, textRect(center, size, width), WidgetStyle(text = masthead))
            }
        }
        if (tree.clock.enabled) {
            panels.firstOrNull { it.panel.id == PanelText.clockPanel(panels, tree.clock) }?.let { p ->
                val b = p.polygon.bounds
                val center = Point(b.left + tree.clock.x * b.width, b.top + tree.clock.y * b.height)
                add(BuiltinWidget.CLOCK, clockRect(center, tree.clock.size * scale), WidgetStyle())
            }
        }
        val t = next.shape as LayoutShape.Tree
        return Layout(t.copy(clock = t.clock.copy(enabled = false), text = t.text.copy(masthead = "")))
    }

    /**
     * 以前の大見出しの置き場所（中心・文字の大きさ・幅。画面の座標）。置く高さの帯で、コマの左右の縁の間（枠と余白 [inset] を除く）に幅を合わせる。
     * 上下も、枠と余白の内側に収まるように寄せる。
     */
    fun mastheadOf(
        polygon: Polygon,
        y: Float,
        inset: Float,
        scale: Float = 1f,
    ): Triple<Point, Float, Float> {
        val b = polygon.bounds
        val size = PanelText.mastheadSize(b) * scale
        val half = size * TEXT_HALF_HEIGHT
        val lo = b.top + inset + half
        val hi = b.bottom - inset - half
        val cy = if (lo <= hi) (b.top + y * b.height).coerceIn(lo, hi) else b.center.y
        val span = polygon.spanBetween(cy - half, cy + half)
        val left = (span?.first ?: b.left) + inset
        val right = (span?.second ?: b.right) - inset
        return Triple(Point((left + right) / 2f, cy), size, ((right - left) * minOf(1f, scale)).coerceAtLeast(1f))
    }

    /**
     * 時計を描ける範囲（枠と余白 [inset] の内側。画面の座標）。以前と同じく、時計の中心があるコマでは、
     * この範囲に収まるよう中心を保ったまま縮めて描く（コマの縁にかからないように）。
     */
    fun clockLimit(
        polygon: Polygon,
        center: Point,
        k: Float,
        inset: Float,
    ): Rect {
        val b = polygon.bounds
        val span = polygon.spanBetween(center.y - CLOCK_UP * k, center.y + CLOCK_DOWN * k)
        return Rect((span?.first ?: b.left) + inset, b.top + inset, (span?.second ?: b.right) - inset, b.bottom - inset)
    }

    /** 新しく置く内蔵のウィジェットの既定の大きさ（画面に対する割合）。時計は以前の既定の大きさ。 */
    fun defaultSize(
        kind: BuiltinWidget,
        layout: Layout,
        viewWidth: Float,
        viewHeight: Float,
    ): Pair<Float, Float> {
        val tree = layout.shape as? LayoutShape.Tree
        val scale = viewWidth / (tree?.frame?.refWidth ?: 1220f)
        return when (kind) {
            BuiltinWidget.CLOCK -> {
                val k = 0.78f * scale
                (2f * CLOCK_HALF_WIDTH * k / viewWidth) to ((CLOCK_UP + CLOCK_DOWN) * k / viewHeight)
            }

            BuiltinWidget.TEXT -> 0.8f to (0.07f * viewWidth / viewHeight)
        }
    }
}
