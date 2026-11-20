package dev.furunov.komalauncher.feature.home

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.furunov.komalauncher.model.geometry.Point
import dev.furunov.komalauncher.model.geometry.Polygon
import dev.furunov.komalauncher.model.geometry.Rect
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.LayoutResolver
import dev.furunov.komalauncher.model.layout.LayoutShape
import dev.furunov.komalauncher.model.layout.Panel
import dev.furunov.komalauncher.ui.art.PanelFace
import dev.furunov.komalauncher.ui.art.PanelStyle
import kotlin.math.max

/** 1 つのコマの、画面座標での形。画面の大きさが決まったときに 1 度だけ計算する。 */
@Immutable
class PanelGeometry(
    val panel: Panel,
    val index: Int,
    /** 画面座標の多角形。 */
    val polygon: Polygon,
    val bounds: Rect,
    /** ウィジェットを置く長方形（画面座標）。 */
    val inscribed: Rect,
    /** [bounds] の左上を原点にした形。graphicsLayer の切り抜きに使う。 */
    val shape: Shape,
    /** 描く画像の id。null なら仮の色を塗る。 */
    val image: String?,
    /** 画像を描く長方形（画面座標）。コマからはみ出す分は [shape] で切り抜く。 */
    val art: Rect?,
    style: PanelStyle = PanelStyle.NONE,
) {
    /** コマの中身の描き方（編集画面と共有する）。 */
    val face = PanelFace(polygon, art, PanelFace.placeholderFor(index), style, panel.art?.dim ?: 0f)
}

@Immutable
class HomeGeometry(
    val panels: List<PanelGeometry>,
) {
    /** 画像の id → 読む大きさ（画素）。同じ画像を複数のコマで使うときは、最も大きく描くコマに合わせる。 */
    val artSizes: Map<String, Size> by lazy {
        val sizes = HashMap<String, Size>()
        for (g in panels) {
            val id = g.image ?: continue
            val r = g.art ?: continue
            val s = sizes[id]
            sizes[id] = if (s == null) Size(r.width, r.height) else Size(max(s.width, r.width), max(s.height, r.height))
        }
        sizes
    }

    /** 重なっていれば後のコマ。外接長方形で先に絞るので、コマの数が増えても軽い。 */
    fun panelAt(x: Float, y: Float): PanelGeometry? {
        val p = Point(x, y)
        return panels.lastOrNull { it.bounds.contains(p) && it.polygon.contains(p) }
    }

    companion object {
        fun of(layout: Layout, width: Float, height: Float): HomeGeometry {
            val fixedImage = (layout.shape as? LayoutShape.Fixed)?.image
            val style = PanelStyle.of(layout, width)
            val panels =
                LayoutResolver.resolve(layout, width, height, parallaxOf(layout, width).art).panels.map { r ->
                    val poly = r.polygon
                    val b = poly.bounds
                    val image = fixedImage ?: r.panel.art?.image
                    PanelGeometry(r.panel, r.index, poly, b, poly.largestInscribedRect(), PolygonShape(poly, b.left, b.top), image, r.art, style)
                }
            return HomeGeometry(panels)
        }
    }
}

private class PolygonShape(
    polygon: Polygon,
    originX: Float,
    originY: Float,
) : Shape {
    private val points = polygon.points.map { Offset(it.x - originX, it.y - originY) }

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        path.close()
        return Outline.Generic(path)
    }
}

/** 視差で動かす最大の量（画素）。絵・文字・時計の順。 */
class ParallaxAmounts(
    val art: Float,
    val text: Float,
    val clock: Float,
)

/** レイアウトの視差の指定を、この画面の画素にする（長さはレイアウトの基準の幅で書いてあるので、画面の幅に合わせる）。 */
fun parallaxOf(
    layout: Layout,
    width: Float,
): ParallaxAmounts {
    val tree = layout.shape as? LayoutShape.Tree ?: return ParallaxAmounts(0f, 0f, 0f)
    val s = width / tree.frame.refWidth
    return ParallaxAmounts(tree.fx.artShift() * s, tree.fx.textShift() * s, tree.fx.clockShift() * s)
}
