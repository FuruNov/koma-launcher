package dev.furunov.komalauncher.model.geometry

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Serializable
data class Point(
    val x: Float,
    val y: Float,
)

data class Rect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val center: Point get() = Point((left + right) / 2f, (top + bottom) / 2f)

    fun contains(p: Point): Boolean = p.x in left..right && p.y in top..bottom
}

/**
 * コマの形。頂点は時計回り・反時計回りのどちらでもよく、凹多角形も扱う。
 * 自己交差する多角形は想定しない（コマ割りでは出てこない）。
 */
class Polygon(
    val points: List<Point>,
) {
    init {
        require(points.size >= 3) { "多角形には頂点が 3 つ以上要る: $points" }
    }

    val bounds: Rect by lazy {
        Rect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }

    val centroid: Point by lazy { Point(points.sumOf { it.x.toDouble() }.toFloat() / points.size, points.sumOf { it.y.toDouble() }.toFloat() / points.size) }

    fun map(transform: (Point) -> Point): Polygon = Polygon(points.map(transform))

    /**
     * 辺と頂点の上は内側として扱う。隣り合うコマの境界線を叩いたときに、
     * どちらのコマも反応しない隙間ができないようにするため。
     */
    fun contains(p: Point): Boolean {
        if (!bounds.contains(p)) return false
        var inside = false
        var j = points.lastIndex
        for (i in points.indices) {
            val a = points[i]
            val b = points[j]
            if (onSegment(p, a, b)) return true
            if ((a.y > p.y) != (b.y > p.y)) {
                val xCross = (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
                if (p.x < xCross) inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * 多角形に収まる、なるべく大きい軸平行の長方形。ウィジェットを置く場所に使う。
     * 厳密な最大を求めるのは重いので、外接長方形を格子に切り、四隅がすべて内側のマスだけを
     * 使って「最大の長方形」を求める（ヒストグラムの方法）。格子の細かさで精度が決まる。
     */
    fun largestInscribedRect(grid: Int = 48): Rect {
        val b = bounds
        val cw = b.width / grid
        val ch = b.height / grid
        // 格子点が内側か
        val inside =
            Array(grid + 1) { gy ->
                BooleanArray(grid + 1) { gx -> contains(Point(b.left + gx * cw, b.top + gy * ch)) }
            }
        val heights = IntArray(grid)
        var best = Rect(b.center.x, b.center.y, b.center.x, b.center.y)
        var bestArea = 0f
        for (gy in 0 until grid) {
            for (gx in 0 until grid) {
                val cellInside = inside[gy][gx] && inside[gy][gx + 1] && inside[gy + 1][gx] && inside[gy + 1][gx + 1]
                heights[gx] = if (cellInside) heights[gx] + 1 else 0
            }
            // この行を底辺とする最大の長方形
            for (start in 0 until grid) {
                var minH = Int.MAX_VALUE
                for (end in start until grid) {
                    minH = min(minH, heights[end])
                    if (minH == 0) break
                    val area = (end - start + 1) * cw * minH * ch
                    if (area > bestArea) {
                        bestArea = area
                        best =
                            Rect(
                                left = b.left + start * cw,
                                top = b.top + (gy + 1 - minH) * ch,
                                right = b.left + (end + 1) * cw,
                                bottom = b.top + (gy + 1) * ch,
                            )
                    }
                }
            }
        }
        return best
    }

    override fun toString(): String = "Polygon($points)"

    private companion object {
        const val EPS = 1e-4f

        fun onSegment(
            p: Point,
            a: Point,
            b: Point,
        ): Boolean {
            val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
            val len = max(abs(b.x - a.x), abs(b.y - a.y))
            if (abs(cross) > EPS * max(1f, len)) return false
            return p.x in min(a.x, b.x) - EPS..max(a.x, b.x) + EPS && p.y in min(a.y, b.y) - EPS..max(a.y, b.y) + EPS
        }
    }
}

/**
 * 画像を画面いっぱいに広げ、はみ出た分を切る（cover）ときの座標変換。
 * コマの頂点は画像に対する 0〜1 の相対座標で持つので、画面の縦横比が違っても
 * 同じ JSON が同じ絵の位置を指す。
 */
class CoverTransform(
    val viewWidth: Float,
    val viewHeight: Float,
    imageAspect: Float,
) {
    /** 画面座標で、画像が描かれる長方形（画面からはみ出すことがある）。 */
    val imageRect: Rect

    init {
        val viewAspect = viewWidth / viewHeight
        imageRect =
            if (viewAspect > imageAspect) {
                val h = viewWidth / imageAspect
                Rect(0f, (viewHeight - h) / 2f, viewWidth, (viewHeight + h) / 2f)
            } else {
                val w = viewHeight * imageAspect
                Rect((viewWidth - w) / 2f, 0f, (viewWidth + w) / 2f, viewHeight)
            }
    }

    fun toView(p: Point): Point = Point(imageRect.left + p.x * imageRect.width, imageRect.top + p.y * imageRect.height)

    fun toImage(p: Point): Point = Point((p.x - imageRect.left) / imageRect.width, (p.y - imageRect.top) / imageRect.height)
}
