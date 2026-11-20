package dev.furunov.komalauncher.model.layout

/**
 * 色合いの変換を、4×5 の色の行列（android.graphics.ColorMatrix と同じ並び、平行移動は 0〜255）で表す。
 * GPU で画素ごとに計算されるので、画像を作り直さずに済み、かかり具合を変えてもすぐ反映される。
 *
 * 淡い色合いの式はモックアップ（filterImage の "soft"）と同じ。画素ごとに
 * - L = 0.299 r + 0.587 g + 0.114 b
 * - soft = (0.45 L + 0.55 c) × 0.9 + 紙の色 × 0.1（青だけさらに −6）
 * - 出力 = c + (soft − c) × かかり具合
 * で、どれも r・g・b の 1 次式なので、行列にそのまま書ける。
 */
object ToneMatrix {
    /** 紙の色。モックアップの PAPER。 */
    private val PAPER = floatArrayOf(246f, 242f, 233f)
    private val LUMA = floatArrayOf(0.299f, 0.587f, 0.114f)

    /** 変換しないなら null。 */
    fun of(look: Look): FloatArray? {
        val s = look.strength.coerceIn(0f, 1f)
        if (look.tone == Tone.NONE || s == 0f) return null
        val m = FloatArray(20)
        for (row in 0..2) {
            for (col in 0..2) {
                val identity = if (row == col) 1f else 0f
                val soft = 0.9f * (0.45f * LUMA[col] + 0.55f * identity)
                m[row * 5 + col] = (1f - s) * identity + s * soft
            }
            val offset = 0.1f * PAPER[row] - if (row == 2) 6f else 0f
            m[row * 5 + 4] = s * offset
        }
        m[18] = 1f
        return m
    }

    /** 行列を 1 画素（0〜255）に掛ける。テストとモックアップの式の照合に使う。 */
    fun apply(
        m: FloatArray,
        r: Float,
        g: Float,
        b: Float,
    ): FloatArray = FloatArray(3) { row -> m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 4] }
}
