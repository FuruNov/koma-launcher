package dev.furunov.komalauncher.model.apps

import dev.furunov.komalauncher.model.folders.Folder
import dev.furunov.komalauncher.model.folders.Folders
import dev.furunov.komalauncher.model.settings.DrawerMove
import dev.furunov.komalauncher.model.settings.DrawerSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppMigrationTest {
    private val oldVivaldi = AppKey("com.vivaldi.browser", "com.vivaldi.browser.IconAlt0")
    private val newVivaldi = AppKey("com.vivaldi.browser", "com.vivaldi.browser.Main")
    private val dialer = AppKey("com.android.contacts", "Dialer")
    private val people = AppKey("com.android.contacts", "People")
    private val oldContacts = AppKey("com.android.contacts", "OldPeople")
    private val gone = AppKey("com.example.gone", "Main")
    private val installed = listOf(AppEntry(newVivaldi, "Vivaldi"), AppEntry(dialer, "電話"), AppEntry(people, "連絡先"))

    @Test
    fun remapsWhenPackageHasOneActivity() = assertEquals(mapOf(oldVivaldi to newVivaldi), AppMigration.remapping(listOf(oldVivaldi, newVivaldi, gone), installed))

    @Test
    fun doesNotRemapWhenPackageHasSeveralActivities() = assertTrue(AppMigration.remapping(listOf(oldContacts), installed).isEmpty())

    @Test
    fun doesNotRemapShortcuts() = assertTrue(AppMigration.remapping(listOf(AppKey.shortcut("com.vivaldi.browser", "x")), installed).isEmpty())

    @Test
    fun remapsFoldersAndDrawer() {
        val m = mapOf(oldVivaldi to newVivaldi)
        val f = AppMigration.remap(Folders(byPanel = mapOf("p" to Folder(listOf(oldVivaldi, gone)))), m)
        assertEquals(listOf(newVivaldi, gone), f.appsOf("p"))
        val d = AppMigration.remap(DrawerSettings(hidden = setOf(oldVivaldi), moved = listOf(DrawerMove(oldVivaldi, "u8")), appOrder = mapOf("u8" to listOf(oldVivaldi))), m)
        assertEquals(setOf(newVivaldi), d.hidden)
        assertEquals(listOf(DrawerMove(newVivaldi, "u8")), d.moved)
        assertEquals(mapOf("u8" to listOf(newVivaldi)), d.appOrder)
    }

    @Test
    fun missingAppsAreRegisteredButNotInstalledOncePerPackage() {
        val f = Folders(byPanel = mapOf("p" to Folder(listOf(gone, newVivaldi, AppKey.shortcut("com.example.pwa", "s")))))
        val d = DrawerSettings(moved = listOf(DrawerMove(gone, "game"), DrawerMove(AppKey("com.example.other", "A"), "u1")))
        assertEquals(listOf("com.example.gone", "com.example.other"), AppMigration.missingPackages(f, d, installed))
    }

    @Test
    fun remembersNamesOfRegisteredApps() {
        val f = Folders(byPanel = mapOf("p" to Folder(listOf(newVivaldi))))
        assertEquals(mapOf("com.vivaldi.browser" to "Vivaldi"), AppMigration.names(f, DrawerSettings(), installed, emptyMap()))
        // 入っていないアプリの名前は、前に覚えたものを残す
        assertEquals(mapOf("com.example.gone" to "消えたアプリ"), AppMigration.names(Folders(byPanel = mapOf("p" to Folder(listOf(gone)))), DrawerSettings(), installed, mapOf("com.example.gone" to "消えたアプリ")))
    }
}
