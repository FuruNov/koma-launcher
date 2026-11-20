package dev.furunov.komalauncher.model.apps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSearchTest {
    private fun score(
        q: String,
        text: String,
    ) = AppSearch.match(q, text)!!.score

    @Test
    fun subsequenceMatches() {
        assertNotNull(AppSearch.match("gm", "Gmail"))
        assertNull(AppSearch.match("mg", "Gmail"))
    }

    @Test
    fun prefixBeatsMiddle() = assertTrue(score("ma", "Maps") > score("ma", "Gmail"))

    @Test
    fun wordStartBeatsMiddle() = assertTrue(score("m", "Google Maps") > score("m", "Gmail"))

    @Test
    fun consecutiveBeatsScattered() = assertTrue(score("cal", "Calendar") > score("cal", "Clock Alarm"))

    @Test
    fun katakanaAndHiraganaMatch() {
        assertNotNull(AppSearch.match("らいん", "ライン"))
        assertNotNull(AppSearch.match("ライン", "らいん"))
    }

    @Test
    fun romajiMatchesKana() {
        assertNotNull(AppSearch.match("rain", "ライン"))
        assertNotNull(AppSearch.match("kamera", "カメラ"))
        assertNotNull(AppSearch.match("shashin", "写真しゃしん"))
    }

    @Test
    fun romajiPrefixesMatch() {
        listOf("r", "ra", "rai", "rain").forEach { assertNotNull(AppSearch.match(it, "ライン"), it) }
        listOf("k", "ka", "kam", "kame").forEach { assertNotNull(AppSearch.match(it, "カメラ"), it) }
    }

    @Test
    fun romajiDoesNotMatchUnrelatedKana() = assertNull(AppSearch.match("rain", "カメラ"))

    @Test
    fun fullWidthMatches() {
        assertNotNull(AppSearch.match("ｇｍ", "Gmail"))
    }

    @Test
    fun blankQueryMatchesNothing() = assertNull(AppSearch.match("  ", "Gmail"))

    @Test
    fun positionsPointAtOriginal() = assertEquals(listOf(0, 1), AppSearch.match("gm", "Gmail")!!.positions)

    @Test
    fun romajiPositionsPointAtKana() = assertEquals(listOf(0, 1, 2), AppSearch.match("rain", "ライン")!!.positions)

    @Test
    fun gmPutsGmailFirst() {
        val apps =
            listOf(
                AppEntry(AppKey("com.google.android.apps.maps", "M"), "Google Maps"),
                AppEntry(AppKey("com.google.android.gm", "G"), "Gmail"),
            )
        assertEquals("Gmail", AppSearch.rank("gm", apps, emptyMap()).first().app.label)
    }

    @Test
    fun launchesBreakTies() {
        val a = AppEntry(AppKey("a", "A"), "Alpha")
        val b = AppEntry(AppKey("b", "B"), "Alpha")
        assertEquals(b, AppSearch.rank("al", listOf(a, b), mapOf(b.key to 3)).first().app)
    }

    @Test
    fun packageNameMatchesBelowLabel() {
        val settings = AppEntry(AppKey("com.android.settings", "S"), "設定")
        val sound = AppEntry(AppKey("x.y", "S"), "Settings Plus")
        val r = AppSearch.rank("settings", listOf(settings, sound), emptyMap())
        assertEquals(listOf(sound, settings), r.map { it.app })
        assertTrue(r.last().match.positions.isEmpty())
    }

    @Test
    fun rankDropsNonMatches() = assertTrue(AppSearch.rank("zz", listOf(AppEntry(AppKey("a", "A"), "Alpha")), emptyMap()).isEmpty())

    @Test
    fun shortcutsMatchByLabel() {
        val s = listOf(AppShortcut("p", "1", "新規作成"), AppShortcut("p", "2", "Compose"))
        assertEquals(listOf("Compose"), AppSearch.rankShortcuts("comp", s).map { it.shortcut.label })
    }
}
