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
}
