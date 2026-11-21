package dev.furunov.komalauncher.ui.art

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import dev.furunov.komalauncher.model.layout.Border
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 絵の無いコマの塗り方。すき間の色が明るければ白い紙に灰色のトーン、暗ければ墨のベタに明るい網点を貼る。
 * 枠の色の「自動」と同じくすき間の色だけで決め、端末のテーマには依存しない（同じ layout.json なら同じ見た目にするため）。
 * 最初のコマだけ、トーンをアプリのアイコンと同じマゼンタにする。
 */
class BlankPage private constructor(
    val paper: Color,
    private val tone: Color,
    private val toneMax: Float,
    private val accentMax: Float,
    /** true なら白い紙。文字と時計を墨で描く。 */
    val light: Boolean,
) {
    /** コマの外接長方形の大きさ [w]×[h] に網点を描く。切り抜きは呼び出し側で済ませてある。[step] は網点の間隔（画素）。 */
    fun dots(
        index: Int,
        w: Float,
        h: Float,
        step: Float,
    ): Dots {
        val (dx, dy) = DIRECTIONS[index % DIRECTIONS.size]
        val len = hypot(dx, dy)
        val ux = dx / len
        val uy = dy / len
        // 外接長方形の 4 つの角を濃淡の向きに射影し、その範囲を 0〜1 に伸ばす
        val corners = listOf(0f, w * ux, h * uy, w * ux + h * uy)
        val lo = corners.min()
        val span = (corners.max() - lo).coerceAtLeast(1f)
        val max = if (index == 0) accentMax else toneMax
        // 半径を LEVELS 段階にまとめ、段階ごとに drawPoints 1 回で描く
        val buckets = Array(LEVELS + 1) { ArrayList<Offset>() }
        val rowStep = step * 0.866f
        var row = 0
        var y = -step
        while (y <= h + step) {
            var x = -step + (row % 2) * step / 2f
            while (x <= w + step) {
                val t = ((x * ux + y * uy) - lo) / span
                val level = (t.coerceIn(0f, 1f) * LEVELS).roundToInt()
                if (level > 0) buckets[level].add(Offset(x, y))
                x += step
            }
            y += rowStep
            row++
        }
        val color = if (index == 0) ACCENT else tone
        return Dots(buckets.mapIndexedNotNull { level, points -> if (points.isEmpty()) null else (step * sqrt(max * level / LEVELS)) to points }, color)
    }

    /** 半径ごとにまとめた網点。直径を線の太さにして、丸い端の点として描く。 */
    class Dots(
        private val groups: List<Pair<Float, List<Offset>>>,
        private val color: Color,
    ) {
        fun DrawScope.draw() {
            groups.forEach { (diameter, points) -> drawPoints(points, PointMode.Points, color, strokeWidth = diameter, cap = StrokeCap.Round) }
        }
    }

    companion object {
        private const val LEVELS = 10
        private val ACCENT = Color(0xFFE0447A)

        /** コマごとの濃淡の向き（濃くなる向き）。隣り合うコマが同じ向きにならないように並べた。 */
        private val DIRECTIONS = listOf(1f to -0.6f, 0f to 1f, -1f to 0.4f, 0.5f to 1f, -0.3f to -1f, 1f to 0.3f)

        val LIGHT = BlankPage(paper = Color(0xFFFBFBF9), tone = Color(0xFF9A9CA4), toneMax = 0.26f, accentMax = 0.2f, light = true)
        val DARK = BlankPage(paper = Color(0xFF1D1E24), tone = Color(0xFF4C4F5B), toneMax = 0.3f, accentMax = 0.3f, light = false)

        /** すき間の色 [gutter] のときの塗り方。 */
        fun on(gutter: Long): BlankPage = if (Border.isDark(gutter)) DARK else LIGHT

        /** 白い紙の上の文字の色。 */
        val INK = Color(0xFF16161A)
    }
}
