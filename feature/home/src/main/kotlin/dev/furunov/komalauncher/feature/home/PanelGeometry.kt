package dev.furunov.komalauncher.feature.home

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.furunov.komalauncher.model.geometry.CoverTransform
import dev.furunov.komalauncher.model.geometry.Point
import dev.furunov.komalauncher.model.geometry.Polygon
import dev.furunov.komalauncher.model.geometry.Rect
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.Panel

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
)

@Immutable
class HomeGeometry(
    val transform: CoverTransform,
    val panels: List<PanelGeometry>,
) {
    /** 重なっていれば後のコマ。外接長方形で先に絞るので、コマの数が増えても軽い。 */
    fun panelAt(x: Float, y: Float): PanelGeometry? {
        val p = Point(x, y)
        return panels.lastOrNull { it.bounds.contains(p) && it.polygon.contains(p) }
    }

    companion object {
        fun of(layout: Layout, width: Float, height: Float): HomeGeometry {
            val t = CoverTransform(width, height, layout.aspect)
            val panels =
                layout.panels.mapIndexed { i, panel ->
                    val poly = panel.polygon.map(t::toView)
                    val b = poly.bounds
                    PanelGeometry(panel, i, poly, b, poly.largestInscribedRect(), PolygonShape(poly, b.left, b.top))
                }
            return HomeGeometry(t, panels)
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
