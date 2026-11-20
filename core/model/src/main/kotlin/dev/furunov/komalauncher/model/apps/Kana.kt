package dev.furunov.komalauncher.model.apps

/**
 * 検索のための文字の正規化と、ローマ字から仮名への変換。依存を増やさないよう、変換表は自前で持つ。
 */
object Kana {
    /**
     * 全角英数を半角に、大文字を小文字に、カタカナをひらがなにそろえる。
     * 文字数を変えない（一致した位置を、そのまま元の文字列の位置として使うため）。
     */
    fun normalize(s: String): String = buildString(s.length) {
        for (c in s) {
            val half = if (c in '！'..'～') (c - 0xFEE0) else c
            append(
                when (half) {
                    in 'ァ'..'ヶ' -> half - 0x60
                    else -> half.lowercaseChar()
                },
            )
        }
    }

    /**
     * ローマ字の入力を、1 文字ずつの「受け入れる文字の集まり」の並びに変える。
     * 末尾に打ちかけの子音（「kam」の「m」）が残れば、その子音で始まる仮名のどれにでも一致する 1 文字として扱う。
     * 英字（と「-」）だけでない入力や、途中で変換できない入力（「gm」）なら null。
     */
    fun romajiPattern(s: String): List<Set<Char>>? {
        if (s.isEmpty() || s.any { it !in 'a'..'z' && it != '-' }) return null
        val out = ArrayList<Set<Char>>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '-' -> {
                    out += setOf('ー')
                    i++
                }

                // 促音: 同じ子音が続く（n を除く）
                i + 1 < s.length && c == s[i + 1] && c !in VOWELS && c != 'n' -> {
                    out += setOf('っ')
                    i++
                }

                // 撥音: n の後が母音・y でない（nn も ん）
                c == 'n' && i + 1 < s.length && s[i + 1] !in VOWELS && s[i + 1] != 'y' -> {
                    out += setOf('ん')
                    i += if (s[i + 1] == 'n') 2 else 1
                }

                else -> {
                    val key = (3 downTo 1).firstNotNullOfOrNull { n -> s.substring(i, minOf(s.length, i + n)).takeIf { it.length == n && it in TABLE } }
                    if (key != null) {
                        TABLE.getValue(key).forEach { out += setOf(it) }
                        i += key.length
                    } else {
                        // 末尾の打ちかけだけを許す
                        val rest = s.substring(i)
                        val heads = TABLE.filterKeys { it.startsWith(rest) }.values.mapTo(HashSet()) { it.first() }
                        if (rest == "n") heads += 'ん'
                        if (heads.isEmpty()) return null
                        out += heads
                        i = s.length
                    }
                }
            }
        }
        return out
    }

    private val VOWELS = setOf('a', 'i', 'u', 'e', 'o')

    private val TABLE: Map<String, String> =
        buildMap {
            val rows =
                listOf(
                    "" to "あいうえお", "k" to "かきくけこ", "g" to "がぎぐげご", "s" to "さしすせそ", "z" to "ざじずぜぞ",
                    "t" to "たちつてと", "d" to "だぢづでど", "n" to "なにぬねの", "h" to "はひふへほ", "b" to "ばびぶべぼ",
                    "p" to "ぱぴぷぺぽ", "m" to "まみむめも", "r" to "らりるれろ", "x" to "ぁぃぅぇぉ", "l" to "ぁぃぅぇぉ",
                )
            for ((c, kana) in rows) "aiueo".forEachIndexed { i, v -> put(c + v, kana[i].toString()) }
            put("ya", "や")
            put("yu", "ゆ")
            put("yo", "よ")
            put("wa", "わ")
            put("wo", "を")
            put("shi", "し")
            put("chi", "ち")
            put("tsu", "つ")
            put("fu", "ふ")
            put("ji", "じ")
            put("vu", "ゔ")
            // 拗音
            val youon =
                listOf(
                    "ky" to "き", "gy" to "ぎ", "sh" to "し", "sy" to "し", "j" to "じ", "jy" to "じ", "zy" to "じ", "ch" to "ち",
                    "ty" to "ち", "cy" to "ち", "dy" to "ぢ", "ny" to "に", "hy" to "ひ", "by" to "び", "py" to "ぴ", "my" to "み", "ry" to "り",
                )
            for ((c, i) in youon) {
                put(c + "a", i + "ゃ")
                put(c + "u", i + "ゅ")
                put(c + "o", i + "ょ")
            }
            put("she", "しぇ")
            put("je", "じぇ")
            put("che", "ちぇ")
            put("fa", "ふぁ")
            put("fi", "ふぃ")
            put("fe", "ふぇ")
            put("fo", "ふぉ")
            put("ti", "てぃ")
            put("di", "でぃ")
            put("va", "ゔぁ")
            put("vi", "ゔぃ")
            put("ve", "ゔぇ")
            put("vo", "ゔぉ")
        }
}
