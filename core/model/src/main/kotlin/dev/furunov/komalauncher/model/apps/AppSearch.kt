package dev.furunov.komalauncher.model.apps

/** 照合の結果。[positions] は元の文字列の中の一致した文字の位置（画面で太字にする）。 */
data class Match(
    val score: Int,
    val positions: List<Int>,
)

data class Ranked(
    val app: AppEntry,
    val match: Match,
)

data class RankedShortcut(
    val shortcut: AppShortcut,
    val match: Match,
)

/**
 * 全アプリ一覧の検索。fzf と同じく、入力の文字が順番どおりに含まれていれば一致とし、一致の仕方で点数を付ける。
 * 名前と入力はどちらも [Kana.normalize] でそろえ、入力がローマ字なら仮名に変えた入力でも照合する。
 */
object AppSearch {
    private const val MATCH = 16
    private const val FIRST_CHAR = 24
    private const val WORD_START = 16
    private const val CONSECUTIVE = 12
    private const val GAP = 2

    fun match(
        query: String,
        text: String,
    ): Match? {
        val q = Kana.normalize(query.trim())
        if (q.isEmpty() || text.isEmpty()) return null
        val norm = Kana.normalize(text)
        val plain = matchPattern(q.map { setOf(it) }, norm, text)
        val romaji = Kana.romajiPattern(q.filterNot { it == ' ' })?.let { matchPattern(it, norm, text) }
        return listOfNotNull(plain, romaji).maxByOrNull { it.score }
    }

    /** 点数の高い順。同点なら起動回数の多い順、それも同じなら [apps] の順（ラベルの順）。一致しないアプリは除く。 */
    fun rank(
        query: String,
        apps: List<AppEntry>,
        launches: Map<AppKey, Int>,
    ): List<Ranked> = apps
        .mapNotNull { app -> (match(query, app.label) ?: matchPackage(query, app.key.packageName))?.let { Ranked(app, it) } }
        .sortedWith(compareByDescending<Ranked> { it.match.score }.thenByDescending { launches[it.app.key] ?: 0 })

    fun rankShortcuts(
        query: String,
        shortcuts: List<AppShortcut>,
    ): List<RankedShortcut> = shortcuts
        .mapNotNull { s -> match(query, s.label)?.let { RankedShortcut(s, it) } }
        .sortedByDescending { it.match.score }

    /**
     * パッケージ名の最後の部分（com.android.settings の settings）で照合する。名前での一致より下に並べ、太字にはしない。
     * 最後が android・app のような汎用の語なら、その前の部分を使う（com.twitter.android の twitter）。
     */
    private fun matchPackage(
        query: String,
        packageName: String,
    ): Match? {
        val segment = packageName.split('.').lastOrNull { it.isNotEmpty() && it !in GENERIC_SEGMENTS } ?: return null
        return match(query, segment)?.let { Match(it.score / 2, emptyList()) }
    }

    private val GENERIC_SEGMENTS = setOf("android", "app", "apps", "mobile", "client", "main", "free", "pro", "lite")

    /**
     * [pattern] の各文字を [norm] のどこに当てるかを、点数が最大になるように選ぶ（動的計画法）。
     * 単語の頭の判定には大文字・小文字が要るので、元の文字列 [original] を見る。
     */
    private fun matchPattern(
        pattern: List<Set<Char>>,
        norm: String,
        original: String,
    ): Match? {
        val n = pattern.size
        val m = norm.length
        if (n > m) return null
        val none = Int.MIN_VALUE
        // best[i][j]: pattern[0..i] を当て、pattern[i] を j に当てたときの最大の点数
        val best = Array(n) { IntArray(m) { none } }
        val from = Array(n) { IntArray(m) { -1 } }
        for (i in 0 until n) {
            for (j in i until m) {
                if (norm[j] !in pattern[i]) continue
                val gain = MATCH + bonus(original, j)
                if (i == 0) {
                    best[0][j] = gain
                    continue
                }
                for (k in i - 1 until j) {
                    val prev = best[i - 1][k]
                    if (prev == none) continue
                    val s = prev + gain + if (k == j - 1) CONSECUTIVE else -GAP * (j - k - 1)
                    if (s > best[i][j]) {
                        best[i][j] = s
                        from[i][j] = k
                    }
                }
            }
        }
        var end = -1
        for (j in 0 until m) if (best[n - 1][j] != none && (end < 0 || best[n - 1][j] > best[n - 1][end])) end = j
        if (end < 0) return null
        val positions = IntArray(n)
        var j = end
        for (i in n - 1 downTo 0) {
            positions[i] = j
            j = from[i][j]
        }
        return Match(best[n - 1][end], positions.toList())
    }

    private fun bonus(
        s: String,
        j: Int,
    ): Int {
        if (j == 0) return FIRST_CHAR
        val prev = s[j - 1]
        val cur = s[j]
        return when {
            !prev.isLetterOrDigit() -> WORD_START
            prev.isLowerCase() && cur.isUpperCase() -> WORD_START
            prev.isDigit() != cur.isDigit() -> WORD_START
            else -> 0
        }
    }
}
