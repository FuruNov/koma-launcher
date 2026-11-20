package dev.furunov.komalauncher.model.apps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DrawerListTest {
    private val apps = (1..6).map { AppEntry(AppKey("p$it", "C"), "App $it") }

    @Test
    fun frequentIsEmptyWithoutHistory() = assertTrue(DrawerList.frequent(apps, emptySet(), emptyMap(), 4).isEmpty())

    @Test
    fun frequentOrdersByCount() = assertEquals(
        listOf(apps[2], apps[0]),
        DrawerList.frequent(apps, emptySet(), mapOf(apps[0].key to 1, apps[2].key to 5), 4),
    )

    @Test
    fun frequentSkipsHiddenAndMissing() = assertEquals(
        listOf(apps[1]),
        DrawerList.frequent(apps, setOf(apps[0].key), mapOf(apps[0].key to 9, AppKey("gone", "C") to 8, apps[1].key to 1), 4),
    )

    @Test
    fun frequentLimitsToColumns() = assertEquals(4, DrawerList.frequent(apps, emptySet(), apps.associate { it.key to 1 }, 4).size)
}
