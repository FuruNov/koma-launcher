package dev.furunov.komalauncher

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import dev.furunov.komalauncher.feature.settings.SettingsDeps
import dev.furunov.komalauncher.feature.settings.SettingsScreen
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
                importer = c.importer,
                backup = c.backup,
                openHomeSettings = { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) },
                onClose = ::finish,
            )
        setContent {
            CompositionLocalProvider(LocalIconLoader provides c.iconLoader) { SettingsScreen(deps) }
        }
    }
}
