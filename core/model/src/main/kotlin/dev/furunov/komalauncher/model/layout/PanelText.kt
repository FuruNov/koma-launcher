package dev.furunov.komalauncher.model.layout

import dev.furunov.komalauncher.model.geometry.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * コマの文字（名前・大見出し・時計）の置き方。式はモックアップの drawLabel・drawMasthead・clockPanelId と同じ。
 * 文字の幅はフォントで変わるので、ここでは「1 文字の幅 / 大きさ」の目安（英語 0.62、日本語 1）で箱の大きさを決め、
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
     * 名前の置き場所を探す。大きさの基準はコマに内接する長方形 [box] で決め、大きさは 3 段階で試す。
     * 位置は [area]（デフォルトは [box]）の中を 9×9 で試し、[fits] が false の位置（コマからはみ出す位置）は除く。
     * 斜めのコマでは、内接する長方形の外にも置ける場所（斜めの辺の近くの三角の部分）があるので、[area] を外接長方形にして広く探す。
     * [cover]（長方形の中で人物が占める割合 0〜1）が最も小さい所を選ぶ。縮めた分だけ少し不利に数える（小さくなりすぎないように）。
     * 人物の地図が無ければ（[cover] が null）、最も大きく、左上の位置に置く。どこにも収まらなければ [box] の中に置く。
     */
    fun placeLabel(
        box: Rect,
        length: Int,
        vertical: Boolean,
        english: Boolean,
        cover: ((Rect) -> Float)?,
        area: Rect = box,
        fits: (Rect) -> Boolean = { true },
        /** 大きさの倍率（編集画面のピンチで決める）。 */
        scale: Float = 1f,
    ): Placement {
        val n = length.coerceAtLeast(1)
        val maxSize = scale *
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

        fun search(
            where: Rect,
            steps: Int,
            accept: (Rect) -> Boolean,
        ): Placement? {
            var best: Placement? = null
            var bestScore = Float.POSITIVE_INFINITY
            for (k in listOf(1f, 0.8f, 0.65f)) {
                val size = maxSize * k
                val (bw, bh) = boxOf(size)
                for (i in 0..steps) {
                    for (j in 0..steps) {
                        val cx = where.left + bw / 2f + (where.width - bw) * i / steps
                        val cy = where.top + bh / 2f + (where.height - bh) * j / steps
                        val r = Rect(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)
                        if (!accept(r)) continue
                        val score = (cover?.invoke(r) ?: 0f) + (1f - k) * 0.25f
                        if (score < bestScore) {
                            bestScore = score
                            best = Placement(cx, cy, size, bw, bh)
                        }
                    }
                }
                if (cover == null && best != null) break
            }
            return best
        }
        return search(area, 8, fits) ?: requireNotNull(search(box, 6) { true })
    }

    /**
     * アイコンのまとまり（幅 [width]・高さ [height] の長方形）の置き場所を、[area] の中から 7×7 で探す。[fits] が false の位置（コマからはみ出す位置）は除く。
     * [cover]（人物が占める割合）が小さく、[avoid]（名前・時計・大見出し）と重ならない位置を選ぶ。
     * 同じくらいなら右下に寄せる（名前は左上に置かれやすいので）。どこにも収まらなければ null。
     */
    fun placeIcon(
        area: Rect,
        width: Float,
        height: Float,
        cover: ((Rect) -> Float)?,
        avoid: List<Rect>,
        fits: (Rect) -> Boolean,
        /** true なら、[avoid] と少しでも重なる位置は使わない（置けなければ null）。 */
        clear: Boolean = false,
    ): Rect? {
        if (area.width < width || area.height < height) return null
        var best: Rect? = null
        var bestScore = Float.POSITIVE_INFINITY
        for (i in 0..6) {
            for (j in 0..6) {
                val left = area.left + (area.width - width) * i / 6f
                val top = area.top + (area.height - height) * j / 6f
                val r = Rect(left, top, left + width, top + height)
                if (!fits(r)) continue
                if (clear && avoid.any { overlapRatio(r, it) > 0f }) continue
                // 少しでも重なれば大きく不利にする（まとまりが大きいと、面積の割合だけでは小さな重なりを見逃すため）
                val overlap = avoid.sumOf { overlapRatio(r, it).let { o -> if (o > 0f) 1.0 + o else 0.0 } }.toFloat()
                // 右下からの離れ具合（0〜1）。小さな重みで、同じくらいの候補の中から右下を選ぶ
                val corner = ((6 - i) + (6 - j)) / 12f
                // 名前・時計・大見出しとの重なりは、人物との重なりよりずっと強く避ける（文字が読めなくなるので）
                val score = (cover?.invoke(r) ?: 0f) + overlap * 5f + corner * 0.05f
                if (score < bestScore) {
                    bestScore = score
                    best = r
                }
            }
        }
        return best
    }

    /** [a] のうち [b] と重なる面積の割合（0〜1）。 */
    private fun overlapRatio(
        a: Rect,
        b: Rect,
    ): Float {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (w <= 0f || h <= 0f || a.width <= 0f || a.height <= 0f) return 0f
        return w * h / (a.width * a.height)
    }

    /**
     * 文字をコマの縁から離す距離（画面の画素）。枠はコマの内側に引くので、枠の線の太さ（二重なら内側の線まで）に余白を足す。
     * [scale] は画面の幅 / 基準の幅。
     */
    fun textInset(
        frame: Frame,
        scale: Float,
    ): Float {
        val b = frame.border
        val line =
            when (b.style) {
                BorderStyle.NONE -> 0f
                BorderStyle.SINGLE -> b.width
                BorderStyle.DOUBLE -> b.width + max(4f, b.width * 0.35f)
            }
        return (line + TEXT_MARGIN) * scale
    }

    /** 枠の内側から文字までの余白（基準の幅のときの画素）。 */
    private const val TEXT_MARGIN = 16f

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
