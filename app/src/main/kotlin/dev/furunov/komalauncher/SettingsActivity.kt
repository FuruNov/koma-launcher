package dev.furunov.komalauncher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import dev.furunov.komalauncher.feature.settings.SettingsDeps
import dev.furunov.komalauncher.feature.settings.SettingsScreen
import dev.furunov.komalauncher.model.apps.AppMigration
import dev.furunov.komalauncher.model.layout.PanelContent
import dev.furunov.komalauncher.ui.icons.LocalIconLoader

/** 設定画面の入れ物。中身は :feature:settings が作り、ここでは依存を渡すだけにする。 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val c = (application as KomaApplication).container
        val deps =
            SettingsDeps(
                store = c.settingsStore,
                folderPanels = {
                    val names = c.folderStore.folders.value.byPanel
                    c.layoutSource.state.value.layout.panels
                        .filter { it.content is PanelContent.Folder }
                        .map { it.id.value to (names[it.id.value]?.name ?: it.label ?: it.id.value) }
                },
                apps = c.appCatalog.apps,
                restore = c.restore,
                autoBackup = c.autoBackup,
                openHomeSettings = { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) },
                hasMusicAccess = c.media::checkAccess,
                audioStatus = c.spectrum.status,
                openMusicAccess = c.media::openAccessSettings,
                openEditor = { startActivity(Intent(this, EditorActivity::class.java)) },
                onClose = ::finish,
                missingApps = {
                    val names = c.settingsStore.settings.value.appNames
                    AppMigration
                        .missingPackages(c.folderStore.folders.value, c.settingsStore.settings.value.drawer, c.appCatalog.apps.value)
                        .map { it to (names[it] ?: it) }
                        .sortedBy { it.second }
                },
                openStorePage = ::openStorePage,
            )
        setContent {
            CompositionLocalProvider(LocalIconLoader provides c.iconLoader) { SettingsScreen(deps) }
        }
    }
}

/** Play ストアでアプリ [packageName] のページを開く。Play ストアの画面が開けなければ、ブラウザで開く。 */
private fun ComponentActivity.openStorePage(packageName: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).setPackage("com.android.vending"))
    } catch (_: ActivityNotFoundException) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))) }
    }
}
