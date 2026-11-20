package dev.furunov.komalauncher.model.apps

import kotlin.test.Test
import kotlin.test.assertEquals

class KanaTest {
    /** 1 文字ずつの集まりがどれも 1 文字なら、つないだ文字列にする。 */
    private fun kana(romaji: String) = Kana.romajiPattern(romaji)!!.joinToString("") { it.single().toString() }

    @Test
    fun tiAndDiFollowImeConvention() {
        assertEquals("ち", kana("ti"))
        assertEquals("ぢ", kana("di"))
        assertEquals("てぃ", kana("thi"))
        assertEquals("でぃ", kana("dhi"))
    }

    @Test
    fun nnBeforeVowelLeavesNForNextKana() {
        assertEquals("こんにちは", kana("konnichiha"))
        assertEquals("こんにちは", kana("konnnichiha"))
        assertEquals("ほんや", kana("honnya"))
        assertEquals("さんぽ", kana("sannpo"))
    }

    @Test
    fun extraKana() {
        assertEquals("うぃうぇいぇ", kana("wiweye"))
        assertEquals("っっっっ", kana("xtultuxtsultsu"))
        assertEquals("ゃゅょゃゅょ", kana("xyaxyuxyolyalyulyo"))
    }
}
