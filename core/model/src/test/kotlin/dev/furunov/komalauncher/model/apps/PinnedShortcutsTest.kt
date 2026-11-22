package dev.furunov.komalauncher.model.apps

import kotlin.test.Test
import kotlin.test.assertEquals

class PinnedShortcutsTest {
    private val old = AppKey.shortcut("browser", "old")
    private val other = AppKey.shortcut("browser", "other")
    private val apps =
        listOf(
            AppEntry(AppKey("browser", "Main"), "クイズ"),
            AppEntry(old, "クイズ"),
            AppEntry(other, "地図"),
            AppEntry(AppKey.shortcut("another", "x"), "クイズ"),
        )

    @Test
    fun findsShortcutOfSamePackageAndLabel() = assertEquals(listOf(old), PinnedShortcuts.sameAs(apps, "browser", "クイズ", newId = "new"))

    @Test
    fun skipsTheNewShortcutItself() = assertEquals(emptyList(), PinnedShortcuts.sameAs(apps, "browser", "クイズ", newId = "old"))

    @Test
    fun ignoresDifferentLabel() = assertEquals(emptyList(), PinnedShortcuts.sameAs(apps, "browser", "時計", newId = "new"))
}
