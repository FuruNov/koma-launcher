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

    /** 四辺を [d] だけ内側へ寄せる。寄せすぎて幅や高さが負になるときは、中心の線（点）にする。 */
    fun inset(d: Float): Rect {
        val dx = minOf(d, width / 2f)
        val dy = minOf(d, height / 2f)
        return Rect(left + dx, top + dy, right - dx, bottom - dy)
    }
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
     * 高さ [y0]〜[y1] の帯の中で、どの高さでも多角形の内側にある横の範囲（左端と右端）。帯が多角形にかからなければ null。
     * コマは直線で割ってできる凸多角形なので、帯の上端・下端と、帯の中の頂点の高さだけを調べれば足りる。
     */
    fun spanBetween(
        y0: Float,
        y1: Float,
    ): Pair<Float, Float>? {
        val ys = listOf(y0, y1) + points.map { it.y }.filter { it > y0 && it < y1 }
        var left = Float.NEGATIVE_INFINITY
        var right = Float.POSITIVE_INFINITY
        for (y in ys) {
            val (l, r) = spanAt(y) ?: return null
            left = maxOf(left, l)
            right = minOf(right, r)
        }
        return if (left <= right) left to right else null
    }

    /** 高さ [y] の横の線と多角形が交わる範囲（最も左と最も右）。交わらなければ null。 */
    private fun spanAt(y: Float): Pair<Float, Float>? {
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            if ((y < minOf(a.y, b.y)) || (y > maxOf(a.y, b.y))) continue
            val x = if (a.y == b.y) null else a.x + (y - a.y) / (b.y - a.y) * (b.x - a.x)
            for (v in listOfNotNull(x, if (a.y == b.y) a.x else null, if (a.y == b.y) b.x else null)) {
                lo = minOf(lo, v)
                hi = maxOf(hi, v)
            }
        }
        return if (lo <= hi) lo to hi else null
    }

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
