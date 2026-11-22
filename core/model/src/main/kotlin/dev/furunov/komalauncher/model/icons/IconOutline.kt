package dev.furunov.komalauncher.model.icons

import dev.furunov.komalauncher.model.settings.IconRim
import dev.furunov.komalauncher.model.settings.IconShape
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** 輪郭の 1 つの操作。座標は 0〜1 の正方形の中（左上が原点、y は下向き）。 */
sealed interface OutlineOp {
    data class Move(
        val x: Float,
        val y: Float,
    ) : OutlineOp

    data class Line(
        val x: Float,
        val y: Float,
    ) : OutlineOp

    data class Cubic(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val x: Float,
        val y: Float,
    ) : OutlineOp

    data object Close : OutlineOp
}

/**
 * アイコンの形の輪郭。2026-11-21 に実機で見比べた試作（spike/icon-shapes の IconShaper）と同じ形を、0〜1 の正方形で表す。
 * 弧と楕円は三次曲線で近似する。吹き出しは楕円と尻尾を 1 本の輪郭でつなぐ（図形の合成を使わずに済むように）。
 */
object IconOutline {
    /**
     * [shape] の輪郭。端末の標準（[IconShape.SYSTEM]）は形を取得できないので、
     * 縁か影のために切り抜く必要がある（[needsClip]）ときは角丸の四角を、そうでなければ空（切り抜かない）を返す。
     */
    fun of(
        shape: IconShape,
        needsClip: Boolean,
    ): List<OutlineOp> = when (shape) {
        IconShape.SYSTEM -> if (needsClip) rounded() else emptyList()
        IconShape.CIRCLE -> ellipse(0.5f, 0.5f, 0.5f, 0.5f)
        IconShape.ROUNDED -> rounded()
        IconShape.SQUIRCLE -> squircle()
        IconShape.TEAR -> tear()
        IconShape.BUBBLE -> bubble()
        IconShape.KOMA -> listOf(OutlineOp.Move(0.06f, 0.02f), OutlineOp.Line(0.98f, 0f), OutlineOp.Line(0.94f, 0.98f), OutlineOp.Line(0f, 1f), OutlineOp.Close)
    }

    /** 0〜1 の正方形の中の長方形。 */
    data class Box(
        val l: Float,
        val t: Float,
        val r: Float,
        val b: Float,
    )

    /** 縁の幅（一辺に対する割合）。縁は形の縁の内側に描く。黒フチは外側の白 0.035 と内側の黒 0.06。 */
    fun rimWidth(
        rim: IconRim,
        scale: Float = 1f,
    ): Float = scale * when (rim) {
        IconRim.NONE -> 0f
        IconRim.INK -> INK_WHITE + INK_BLACK
        IconRim.STICKER -> STICKER
    }

    const val INK_WHITE = 0.035f
    const val INK_BLACK = 0.06f
    const val STICKER = 0.08f

    /** 古い形式（2 層でない）アイコンの余白の下限。角丸の四角で縁が無いときの余白（今までの 16%）。 */
    private const val MIN_MARGIN = 0.16f

    /**
     * 2 層でない古い形式のアイコンを置く四角。形の中心に置き、4 つの角が形の内側で、縁に掛からない最大の大きさにする。
     * ただし余白は [MIN_MARGIN] より小さくしない。吹き出しは楕円の中心 (0.5, 0.45) に置く。
     */
    fun contentBox(
        shape: IconShape,
        rim: IconRim,
        scale: Float = 1f,
    ): Box {
        val poly = polygon(of(shape, needsClip = true))
        val w = rimWidth(rim, scale)
        val cx = 0.5f
        val cy = if (shape == IconShape.BUBBLE) 0.45f else 0.5f
        fun fits(h: Float) = listOf(cx - h to cy - h, cx + h to cy - h, cx + h to cy + h, cx - h to cy + h).all { (x, y) -> inside(poly, x, y) && distanceToEdge(poly, x, y) >= w }
        var lo = 0f
        var hi = 0.5f - MIN_MARGIN
        if (fits(hi)) return Box(cx - hi, cy - hi, cx + hi, cy + hi)
        repeat(30) {
            val mid = (lo + hi) / 2f
            if (fits(mid)) lo = mid else hi = mid
        }
        return Box(cx - lo, cy - lo, cx + lo, cy + lo)
    }

    /** 輪郭を折れ線にする（三次曲線は 16 等分する）。 */
    fun polygon(ops: List<OutlineOp>): List<Pair<Float, Float>> {
        val pts = ArrayList<Pair<Float, Float>>()
        var x0 = 0f
        var y0 = 0f
        ops.forEach { op ->
            when (op) {
                is OutlineOp.Move -> {
                    pts += op.x to op.y
                    x0 = op.x
                    y0 = op.y
                }

                is OutlineOp.Line -> {
                    pts += op.x to op.y
                    x0 = op.x
                    y0 = op.y
                }

                is OutlineOp.Cubic -> {
                    for (i in 1..16) {
                        val t = i / 16f
                        val u = 1 - t
                        pts += (u * u * u * x0 + 3 * u * u * t * op.x1 + 3 * u * t * t * op.x2 + t * t * t * op.x) to
                            (u * u * u * y0 + 3 * u * u * t * op.y1 + 3 * u * t * t * op.y2 + t * t * t * op.y)
                    }
                    x0 = op.x
                    y0 = op.y
                }

                OutlineOp.Close -> Unit
            }
        }
        return pts
    }

    /** 点が折れ線の内側にあるか（偶奇の規則）。 */
    fun inside(
        poly: List<Pair<Float, Float>>,
        x: Float,
        y: Float,
    ): Boolean {
        var c = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val (xi, yi) = poly[i]
            val (xj, yj) = poly[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }

    /** 点から折れ線の辺までの最短の距離。 */
    fun distanceToEdge(
        poly: List<Pair<Float, Float>>,
        x: Float,
        y: Float,
    ): Float {
        var best = Float.MAX_VALUE
        var j = poly.size - 1
        for (i in poly.indices) {
            val (ax, ay) = poly[j]
            val (bx, by) = poly[i]
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0f, 1f)
            val px = ax + t * dx - x
            val py = ay + t * dy - y
            best = minOf(best, kotlin.math.sqrt(px * px + py * py))
            j = i
        }
        return best
    }

    private const val CORNER = 0.14f

    private fun rounded(): List<OutlineOp> {
        val r = CORNER
        return buildList {
            add(OutlineOp.Move(r, 0f))
            add(OutlineOp.Line(1 - r, 0f))
            addAll(arc(1 - r, r, r, r, -90f, 0f))
            add(OutlineOp.Line(1f, 1 - r))
            addAll(arc(1 - r, 1 - r, r, r, 0f, 90f))
            add(OutlineOp.Line(r, 1f))
            addAll(arc(r, 1 - r, r, r, 90f, 180f))
            add(OutlineOp.Line(0f, r))
            addAll(arc(r, r, r, r, 180f, 270f))
            add(OutlineOp.Close)
        }
    }

    private fun squircle(): List<OutlineOp> = listOf(
        OutlineOp.Move(0.5f, 0f),
        OutlineOp.Cubic(0.88f, 0f, 1f, 0.12f, 1f, 0.5f),
        OutlineOp.Cubic(1f, 0.88f, 0.88f, 1f, 0.5f, 1f),
        OutlineOp.Cubic(0.12f, 1f, 0f, 0.88f, 0f, 0.5f),
        OutlineOp.Cubic(0f, 0.12f, 0.12f, 0f, 0.5f, 0f),
        OutlineOp.Close,
    )

    /** 右下の角だけ尖った形。 */
    private fun tear(): List<OutlineOp> = buildList {
        add(OutlineOp.Move(0.5f, 0f))
        addAll(arc(0.5f, 0.5f, 0.5f, 0.5f, -90f, 0f))
        add(OutlineOp.Line(1f, 1f))
        add(OutlineOp.Line(0.5f, 1f))
        addAll(arc(0.5f, 0.5f, 0.5f, 0.5f, 90f, 270f))
        add(OutlineOp.Close)
    }

    /** 楕円の吹き出し。角度 125° の点から時計回りに一周して 110° の点まで描き、尻尾の先（左下）へ寄り道して閉じる。 */
    private fun bubble(): List<OutlineOp> {
        val cx = 0.5f
        val cy = 0.45f
        val rx = 0.47f
        val ry = 0.43f
        val start = point(cx, cy, rx, ry, 125f)
        return buildList {
            add(OutlineOp.Move(start.first, start.second))
            addAll(arc(cx, cy, rx, ry, 125f, 470f))
            add(OutlineOp.Line(0.10f, 0.99f))
            add(OutlineOp.Close)
        }
    }

    private fun ellipse(
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
    ): List<OutlineOp> {
        val start = point(cx, cy, rx, ry, 0f)
        return listOf(OutlineOp.Move(start.first, start.second)) + arc(cx, cy, rx, ry, 0f, 360f) + OutlineOp.Close
    }

    private fun point(
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        deg: Float,
    ): Pair<Float, Float> {
        val a = deg * PI.toFloat() / 180f
        return (cx + rx * cos(a)) to (cy + ry * sin(a))
    }

    /** 楕円の弧を、[from] 度から [to] 度まで（時計回り、y は下向き）、45 度以下に分けた三次曲線で近似する。制御点が正方形の外に出ないように細かく分ける。 */
    private fun arc(
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        from: Float,
        to: Float,
    ): List<OutlineOp> {
        val n = kotlin.math.ceil((to - from) / 45f).toInt().coerceAtLeast(1)
        val step = (to - from) / n
        return (0 until n).map { i ->
            val a0 = (from + step * i) * PI.toFloat() / 180f
            val a1 = (from + step * (i + 1)) * PI.toFloat() / 180f
            val k = 4f / 3f * tan((a1 - a0) / 4f)
            val x0 = cx + rx * cos(a0)
            val y0 = cy + ry * sin(a0)
            val x1 = cx + rx * cos(a1)
            val y1 = cy + ry * sin(a1)
            OutlineOp.Cubic(
                x0 - k * rx * sin(a0),
                y0 + k * ry * cos(a0),
                x1 + k * rx * sin(a1),
                y1 - k * ry * cos(a1),
                x1,
                y1,
            )
        }
    }
}
