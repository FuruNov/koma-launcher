package dev.furunov.komalauncher.model.layout

/** 人物の切り抜きの計算。 */
object MaskMath {
    /** 人物らしさの濃さのうち、この範囲を 0〜1 に詰める。縁のぼけた半透明を減らし、文字の切り口をはっきりさせる。 */
    private const val LOW = 0.35f
    private const val HIGH = 0.65f

    /** 人物らしさ（1 が人物）から、「残す」濃さ（1 が人物以外）へ。 */
    fun keepFromPerson(person: FloatArray): FloatArray = FloatArray(person.size) { 1f - ((person[it] - LOW) / (HIGH - LOW)).coerceIn(0f, 1f) }

    /**
     * 「残す」濃さ（[w]×[h]）から、名前を置く場所を探すための粗い地図（[columns]×[rows]、1 が人物）を作る。
     * 各マスに入る画素の平均をとる。
     */
    fun coverGrid(
        keep: FloatArray,
        w: Int,
        h: Int,
        columns: Int = 64,
        rows: Int = 36,
    ): FloatArray {
        val sum = FloatArray(columns * rows)
        val count = IntArray(columns * rows)
        for (y in 0 until h) {
            val v = y * rows / h
            for (x in 0 until w) {
                val u = x * columns / w
                sum[v * columns + u] += 1f - keep[y * w + x]
                count[v * columns + u]++
            }
        }
        return FloatArray(sum.size) { if (count[it] == 0) 0f else sum[it] / count[it] }
    }

    /** 「残す」濃さ（0〜255 の透明度、255 が人物以外）から、人物の濃さ（255 が人物）へ。 */
    fun personFromKeep(keepAlpha: ByteArray): ByteArray = ByteArray(keepAlpha.size) { (255 - (keepAlpha[it].toInt() and 0xFF)).toByte() }

    /**
     * 箱形のぼかしを横と縦に [passes] 回ずつ掛ける（3 回でガウスのぼかしに近くなる）。半径 [radius] は画素。
     * 人物の影と、ぼかした背景に使う。値は 0〜255。
     */
    fun boxBlur(
        src: ByteArray,
        w: Int,
        h: Int,
        radius: Int,
        passes: Int = 3,
    ): ByteArray {
        if (radius <= 0 || w <= 0 || h <= 0) return src.copyOf()
        var a = IntArray(src.size) { src[it].toInt() and 0xFF }
        var b = IntArray(src.size)
        repeat(passes) {
            blurLine(a, b, w, h, radius, horizontal = true)
            blurLine(b, a, w, h, radius, horizontal = false)
        }
        return ByteArray(a.size) { a[it].toByte() }
    }

    /** 1 方向の箱形のぼかし。端の外は端の値が続くとみなす。 */
    private fun blurLine(
        from: IntArray,
        to: IntArray,
        w: Int,
        h: Int,
        r: Int,
        horizontal: Boolean,
    ) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val step = if (horizontal) 1 else w
        val size = 2 * r + 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            fun at(i: Int) = from[base + i.coerceIn(0, len - 1) * step]
            var sum = 0
            for (i in -r..r) sum += at(i)
            for (i in 0 until len) {
                to[base + i * step] = (sum + size / 2) / size
                sum += at(i + r + 1) - at(i - r)
            }
        }
    }

    /**
     * 人物の濃さを外へ [radius] 画素だけ広げる（縁取りに使う）。半径 [radius] と半分の円の上の 16 方向へずらした値の最大をとる。
     * 細い所（髪の先など）でも縁が途切れないよう、半分の円も使う。
     */
    fun dilate(
        src: ByteArray,
        w: Int,
        h: Int,
        radius: Float,
    ): ByteArray {
        val out = src.copyOf()
        if (radius <= 0f) return out
        val offsets =
            listOf(radius, radius / 2f).flatMap { r ->
                List(16) { k ->
                    val t = k * Math.PI / 8
                    Pair(Math.round(Math.cos(t) * r).toInt(), Math.round(Math.sin(t) * r).toInt())
                }
            }.distinct().filter { it != Pair(0, 0) }
        for ((dx, dy) in offsets) {
            for (y in 0 until h) {
                val sy = y - dy
                if (sy !in 0 until h) continue
                for (x in 0 until w) {
                    val sx = x - dx
                    if (sx !in 0 until w) continue
                    val v = src[sy * w + sx].toInt() and 0xFF
                    if (v > (out[y * w + x].toInt() and 0xFF)) out[y * w + x] = v.toByte()
                }
            }
        }
        return out
    }
}
