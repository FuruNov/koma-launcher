package dev.furunov.komalauncher.model.layout

import dev.furunov.komalauncher.model.geometry.Rect
import kotlin.math.min

/**
 * コマの文字（名前・大見出し・時計）の置き方。式はモックアップの drawLabel・drawMasthead・clockPanelId と同じ。
 * 文字の幅は書体で変わるので、ここでは「1 文字の幅 / 大きさ」の目安（英語 0.62、日本語 1）で箱の大きさを決め、
 * 描くときに箱へ合わせる。
 */
object PanelText {
    /**
     * コマに出す名前。利用者が入力した名前だけを出し、アプリの側で名前を補ったり訳したりしない。
     * 英語表示のときは英語の名前を出し、無ければ名前を出す。ウィジェットのコマには出さない。
     * [name] はホームの長押しで付けたフォルダの名前で、レイアウトの名前より優先する。
     */
    fun labelOf(
        panel: Panel,
        name: String?,
        english: Boolean,
    ): String? {
        if (panel.content == PanelContent.Widget) return null
        val ja = (name ?: panel.label)?.takeIf { it.isNotBlank() }
        val en = panel.labelEn?.takeIf { it.isNotBlank() }
        return if (english) en ?: ja else ja
    }

    /** 時計を置くコマ。指定が無いか消えていれば、いちばん大きいコマ（外接長方形の面積）。 */
    fun clockPanel(
        panels: List<ResolvedPanel>,
        clock: ClockSpec,
    ): PanelId? {
        if (!clock.enabled) return null
        clock.panel?.let { id -> if (panels.any { it.panel.id == id }) return id }
        return panels.maxByOrNull { it.polygon.bounds.width * it.polygon.bounds.height }?.panel?.id
    }

    /** 大見出しを置くコマ。指定が無いか消えていれば時計のコマ、時計も無ければいちばん大きいコマ。 */
    fun mastheadPanel(
        panels: List<ResolvedPanel>,
        text: TextSpec,
        clock: ClockSpec,
    ): PanelId? {
        if (text.masthead.isBlank()) return null
        text.mastheadPanel?.let { id -> if (panels.any { it.panel.id == id }) return id }
        return clockPanel(panels, clock.copy(enabled = true))
    }

    /** 名前を置く場所。[center] は箱の中心、[size] は文字の大きさ（画素）。 */
    data class Placement(
        val centerX: Float,
        val centerY: Float,
        val size: Float,
        /** 箱の幅と高さ。縦書きの英語は 90 度回すので縦長になる。 */
        val boxWidth: Float,
        val boxHeight: Float,
    )

    /**
     * 名前の置き場所を、コマに内接する長方形 [box] の全体から探す。大きさは 3 段階、位置は 7×7 で試し、
     * [cover]（長方形の中で人物が占める割合 0〜1）が最も小さい所を選ぶ。縮めた分だけ少し不利に数える（小さくなりすぎないように）。
     * 人物の地図が無ければ（[cover] が null）、最も大きく、左上の位置に置く。
     */
    fun placeLabel(
        box: Rect,
        length: Int,
        vertical: Boolean,
        english: Boolean,
        cover: ((Rect) -> Float)?,
    ): Placement {
        val n = length.coerceAtLeast(1)
        val maxSize =
            if (vertical) {
                if (english) min(box.width * 0.2f, box.height * 0.7f / (n * 0.62f)) else min(box.width * 0.2f, box.height * 0.72f / n)
            } else {
                min(box.height * 0.22f, box.width * 0.8f / (n * if (english) 0.62f else 1f))
            }

        fun boxOf(size: Float): Pair<Float, Float> {
            val len =
                if (vertical) {
                    if (english) min(box.height * 0.7f, n * size * 0.62f) else n * size * 1.05f
                } else {
                    n * size * if (english) 0.62f else 1f
                }
            return if (vertical) size to len else len to size
        }

        var best: Placement? = null
        var bestScore = Float.POSITIVE_INFINITY
        for (k in listOf(1f, 0.8f, 0.65f)) {
            val size = maxSize * k
            val (bw, bh) = boxOf(size)
            for (i in 0..6) {
                for (j in 0..6) {
                    val cx = box.left + bw / 2f + (box.width - bw) * i / 6f
                    val cy = box.top + bh / 2f + (box.height - bh) * j / 6f
                    val c = cover?.invoke(Rect(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)) ?: 0f
                    val score = c + (1f - k) * 0.25f
                    if (score < bestScore) {
                        bestScore = score
                        best = Placement(cx, cy, size, bw, bh)
                    }
                }
            }
            if (cover == null) break
        }
        return requireNotNull(best)
    }

    /** 大見出しの大きさ。幅はコマの幅の 9 割に合わせる（描くときに横に伸縮する）。 */
    fun mastheadSize(bounds: Rect): Float = min(bounds.height * 0.16f, bounds.width * 0.42f)

    /**
     * 人物の地図（[columns]×[rows]、1 が人物）で、長方形 [r] の中の人物の割合を 6×6 の点で数える。
     * [art] は地図の元の画像を描く長方形（画面座標）。モックアップの personCover と同じ。
     */
    fun cover(
        grid: FloatArray,
        columns: Int,
        rows: Int,
        art: Rect,
        r: Rect,
    ): Float {
        var sum = 0f
        var count = 0
        for (i in 0 until 6) {
            for (j in 0 until 6) {
                val x = r.left + r.width * (i + 0.5f) / 6f
                val y = r.top + r.height * (j + 0.5f) / 6f
                val u = ((x - art.left) / art.width * columns).toInt()
                val v = ((y - art.top) / art.height * rows).toInt()
                if (x < art.left || y < art.top || u >= columns || v >= rows) continue
                sum += grid[v * columns + u]
                count++
            }
        }
        return if (count == 0) 0f else sum / count
    }
}
