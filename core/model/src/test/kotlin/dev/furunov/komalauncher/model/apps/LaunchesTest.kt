package dev.furunov.komalauncher.model.apps

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.test.Test
import kotlin.test.assertEquals

class LaunchesTest {
    private val a = AppKey("p.a", "A")

    @Test
    fun recordIncrements() = assertEquals(2, Launches().record(a).record(a).countOf(a))

    @Test
    fun asMapUsesAppKeys() = assertEquals(mapOf(a to 1), Launches().record(a).asMap())

    @Test
    fun shortcutIdIsSeparateFromApp() {
        val s1 = AppKey.shortcut("p", "1")
        val l = Launches().record(s1)
        assertEquals(1, l.countOf(s1))
        assertEquals(0, l.countOf(AppKey.shortcut("p", "2")))
        assertEquals(0, l.countOf(AppKey("p", "Main")))
        assertEquals(mapOf(s1 to 1), l.asMap())
        assertEquals(setOf("p/@1"), l.counts.keys)
    }

    @Test
    fun countingCatalogRecordsLaunchOnly() {
        val inner = FakeCatalog()
        val store = MemoryLaunchStore()
        val catalog = CountingAppCatalog(inner, store)
        catalog.launch(a)
        catalog.launchShortcut(AppShortcut("p.a", "s", "S"))
        assertEquals(listOf(a), inner.launched)
        assertEquals(1, store.launches.value.countOf(a))
        assertEquals(1, store.launches.value.counts.size)
    }

    private class MemoryLaunchStore : LaunchStore {
        override val launches = MutableStateFlow(Launches())

        override fun update(transform: (Launches) -> Launches) = launches.update(transform)
    }

    private class FakeCatalog : AppCatalog {
        val launched = mutableListOf<AppKey>()
        override val apps: StateFlow<List<AppEntry>> = MutableStateFlow(emptyList())

        override fun resolve(packageName: String): AppKey? = null

        override fun launch(key: AppKey) {
            launched += key
        }

        override fun openAppInfo(key: AppKey) = Unit

        override fun requestUninstall(key: AppKey) = Unit

        override fun shortcuts(key: AppKey): List<AppShortcut> = emptyList()

        override fun launchShortcut(shortcut: AppShortcut) = Unit

        override fun allShortcuts(): List<AppShortcut> = emptyList()

        override suspend fun unpin(key: AppKey) = true
    }
}
