package dev.furunov.komalauncher.model.layout

import dev.furunov.komalauncher.model.geometry.CoverTransform
import dev.furunov.komalauncher.model.geometry.Point
import dev.furunov.komalauncher.model.geometry.Polygon
import dev.furunov.komalauncher.model.geometry.Rect
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 線の木の計算。式はモックアップ（docs/mockups/editor.template.html の clip・pointAt・paramOf）と同じにする。
 * 多角形は凸を前提にする（長方形を直線で切ることを繰り返すので、常に凸になる）。
 */
object TreeGeometry {
    /**
     * 凸多角形を、直線 a→b の左側（[sign] = +1）または右側（-1）で切る。境目を [off] だけ内側へずらす（コマの間の幅の半分）。
     * 残りが 3 点未満なら空のリストを返す。
     */
    fun clip(
        poly: List<Point>,
        a: Point,
        b: Point,
        sign: Int,
        off: Float,
    ): List<Point> {
        val nx = -(b.y - a.y)
        val ny = b.x - a.x
        val len = hypot(nx, ny).takeIf { it > 0f } ?: 1f
        fun side(p: Point) = sign * (((p.x - a.x) * nx + (p.y - a.y) * ny) / len) - off
        val out = ArrayList<Point>(poly.size + 2)
        for (i in poly.indices) {
            val p = poly[i]
            val q = poly[(i + 1) % poly.size]
            val sp = side(p)
            val sq = side(q)
            if (sp >= 0f) out.add(p)
            if ((sp >= 0f) != (sq >= 0f)) {
                val t = sp / (sp - sq)
                out.add(Point(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t))
            }
        }
        return if (out.size >= 3) out else emptyList()
    }

    fun perimeter(poly: List<Point>): Float {
        var l = 0f
        for (i in poly.indices) {
            val p = poly[i]
            val q = poly[(i + 1) % poly.size]
            l += hypot(q.x - p.x, q.y - p.y)
        }
        return l
    }

    /** 周の長さに対する割合 [t]（0〜1、範囲外は周回させる）で、多角形の縁の上の点を返す。線の端を親のコマの縁に沿わせるため。 */
    fun pointAt(
        poly: List<Point>,
        t: Float,
    ): Point {
        val l = perimeter(poly)
        var d = (((t % 1f) + 1f) % 1f) * l
        for (i in poly.indices) {
            val p = poly[i]
            val q = poly[(i + 1) % poly.size]
            val s = hypot(q.x - p.x, q.y - p.y)
            if (d <= s) {
                val u = if (s > 0f) d / s else 0f
                return Point(p.x + (q.x - p.x) * u, p.y + (q.y - p.y) * u)
            }
            d -= s
        }
        return poly[0]
    }

    /** [pt] に最も近い縁の上の点の、周に対する割合。[pointAt] の逆。 */
    fun paramOf(
        poly: List<Point>,
        pt: Point,
    ): Float {
        val l = perimeter(poly)
        var best = 0f
        var bestD = Float.POSITIVE_INFINITY
        var acc = 0f
        for (i in poly.indices) {
            val p = poly[i]
            val q = poly[(i + 1) % poly.size]
            val dx = q.x - p.x
            val dy = q.y - p.y
            val s = hypot(dx, dy)
            val u = if (s > 0f) (((pt.x - p.x) * dx + (pt.y - p.y) * dy) / (s * s)).coerceIn(0f, 1f) else 0f
            val d = hypot(pt.x - (p.x + dx * u), pt.y - (p.y + dy * u))
            if (d < bestD) {
                bestD = d
                best = (acc + u * s) / l
            }
            acc += s
        }
        return best
    }
}

/** 画面座標に置いた 1 つのコマ。 */
data class ResolvedPanel(
    val panel: Panel,
    /** 描画の順（[Layout.panels] の順）。 */
    val index: Int,
    val polygon: Polygon,
    /** 画像を描く長方形（画面座標）。コマの外へはみ出す分は、コマの形で切り抜く。null なら絵を描かない。 */
    val art: Rect?,
)

/** 画面座標に置いた 1 本の線。編集画面で線の端のハンドルを表示するのに使う。 */
data class ResolvedCut(
    val node: LayoutNode.Split,
    /** 根からの道筋（"a"・"b" の並び）。根は空文字。 */
    val path: String,
    /** 線が割る親のコマ（コマの間の幅を除く前の形）。 */
    val parent: List<Point>,
    val a: Point,
    val b: Point,
)

data class ResolvedLayout(
    val panels: List<ResolvedPanel>,
    val cuts: List<ResolvedCut>,
)

object LayoutResolver {
    /** これより細いコマ（画素）は、面積が 0 のコマと同じく結果に含めない。 */
    private const val MIN_SIDE = 0.5f

    /**
     * [layout] を幅 [width]・高さ [height] の画面に置く。
     * [parallax] は視差で絵を動かす最大の量（画素）。絵をその分だけ大きく置き、動かしても端が見えないようにする。
     * 面積が 0 になったコマ（線が縁に重なったときなど）は結果に含めない。
     */
    fun resolve(
        layout: Layout,
        width: Float,
        height: Float,
        parallax: Float = 0f,
    ): ResolvedLayout = when (val shape = layout.shape) {
        is LayoutShape.Fixed -> resolveFixed(shape, width, height)
        is LayoutShape.Tree -> resolveTree(shape, width, height, parallax)
    }

    private fun resolveFixed(
        shape: LayoutShape.Fixed,
        width: Float,
        height: Float,
    ): ResolvedLayout {
        // 1 枚の絵を画面いっぱいに広げ、各コマはその絵を同じ位置で切り抜く
        val t = CoverTransform(width, height, shape.aspect)
        val art = shape.image?.let { t.imageRect }
        val panels = shape.panels.mapIndexed { i, p -> ResolvedPanel(p.panel, i, p.polygon.map(t::toView), art) }
        return ResolvedLayout(panels, emptyList())
    }

    private fun resolveTree(
        shape: LayoutShape.Tree,
        width: Float,
        height: Float,
        parallax: Float,
    ): ResolvedLayout {
        val frame = shape.frame
        val scale = width / frame.refWidth
        val m = if (frame.bleed) 0f else frame.margin * scale
        val half = frame.gutter * scale / 2f
        val root = listOf(Point(m, m), Point(width - m, m), Point(width - m, height - m), Point(m, height - m))
        val panels = ArrayList<ResolvedPanel>()
        val cuts = ArrayList<ResolvedCut>()
        var index = 0

        fun walk(
            node: LayoutNode,
            poly: List<Point>,
            path: String,
        ) {
            when (node) {
                is LayoutNode.Leaf -> {
                    // 割った先が空になったコマも、描画の順を保つため番号は進める
                    val i = index++
                    if (poly.isEmpty()) return
                    val polygon = Polygon(poly)
                    // 頂点が残っても、幅か高さが無いコマは描かない（絵の置き場所の計算が 0 で割ることになるため）
                    if (polygon.bounds.width < MIN_SIDE || polygon.bounds.height < MIN_SIDE) return
                    val art = node.panel.art?.takeIf { it.aspect != null }?.let { ArtLimits.placement(polygon.bounds, it, parallax) }
                    panels.add(ResolvedPanel(node.panel, i, polygon, art))
                }

                is LayoutNode.Split -> {
                    if (poly.isEmpty()) {
                        walk(node.a, poly, path + "a")
                        walk(node.b, poly, path + "b")
                        return
                    }
                    val a = TreeGeometry.pointAt(poly, node.ta)
                    val b = TreeGeometry.pointAt(poly, node.tb)
                    cuts.add(ResolvedCut(node, path, poly, a, b))
                    walk(node.a, TreeGeometry.clip(poly, a, b, 1, half), path + "a")
                    walk(node.b, TreeGeometry.clip(poly, a, b, -1, half), path + "b")
                }
            }
        }
        walk(shape.root, root, "")
        return ResolvedLayout(panels, cuts)
    }
}

/**
 * 絵の置き場所。絵の端がコマに見えないよう、次の 2 つを守る（モックアップの artLimits・placement・clampArt と同じ式）。
 * - 拡大率は 1（コマの外接長方形をちょうど覆う大きさ）以上。視差で動く量の分だけさらに大きく置く
 * - 中心のずらし（dx・dy）は、覆ったまま動ける範囲に収める。コマの形が変わっても、表示のたびに収め直す
 */
object ArtLimits {
    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 3f

    data class Limits(
        val width: Float,
        val height: Float,
        val maxX: Float,
        val maxY: Float,
    )

    fun limits(
        bounds: Rect,
        aspect: Float,
        zoom: Float,
        parallax: Float,
    ): Limits {
        val z = if (zoom.isFinite()) zoom.coerceIn(MIN_ZOOM, MAX_ZOOM) else MIN_ZOOM
        // 幅か高さが 0 のコマでも NaN にならないよう、割る数を 1 画素以上にする
        val side = max(1f, min(bounds.width, bounds.height))
        val pad = 1f + 2f * parallax / side
        val ar = if (aspect.isFinite() && aspect > 0f) aspect else 1f
        val w = max(bounds.width, bounds.height * ar) * z * pad
        val h = w / ar
        return Limits(w, h, max(0f, (w - bounds.width) / 2f - parallax), max(0f, (h - bounds.height) / 2f - parallax))
    }

    /** 画像を描く長方形。[art] のずらしが範囲の外でも、範囲に収めた位置に置く。 */
    fun placement(
        bounds: Rect,
        art: PanelArt,
        parallax: Float = 0f,
    ): Rect {
        val l = limits(bounds, art.aspect ?: 1f, art.zoom, parallax)
        val ox = (art.dx * bounds.width).coerceIn(-l.maxX, l.maxX)
        val oy = (art.dy * bounds.height).coerceIn(-l.maxY, l.maxY)
        val c = bounds.center
        return Rect(c.x + ox - l.width / 2f, c.y + oy - l.height / 2f, c.x + ox + l.width / 2f, c.y + oy + l.height / 2f)
    }

    /** 拡大率とずらしを、端が見えない範囲へ書き戻した値。ドラッグやピンチの後に呼ぶ。 */
    fun clamp(
        bounds: Rect,
        art: PanelArt,
        parallax: Float = 0f,
    ): PanelArt {
        val zoom = art.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val l = limits(bounds, art.aspect ?: 1f, zoom, parallax)
        return art.copy(
            zoom = zoom,
            dx = (art.dx * bounds.width).coerceIn(-l.maxX, l.maxX) / bounds.width,
            dy = (art.dy * bounds.height).coerceIn(-l.maxY, l.maxY) / bounds.height,
        )
    }
}
