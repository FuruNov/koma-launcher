package dev.furunov.komalauncher

import android.content.Context
import android.content.Intent
import dev.furunov.komalauncher.model.apps.AppCatalog
import dev.furunov.komalauncher.model.ports.ActionPerformer
import dev.furunov.komalauncher.model.ports.SystemShade
import dev.furunov.komalauncher.model.settings.LauncherAction

/**
 * ホームの外へ出る操作の実行。操作を増やすときは LauncherAction に 1 つ加え、ここに 1 行加える。
 * ホームの中で完結する操作（フォルダ、全アプリ一覧、メニュー）は HomeViewModel が先に処理するので、ここには来ない。
 */
class DefaultActionPerformer(
    private val context: Context,
    private val shade: SystemShade,
    private val apps: AppCatalog,
) : ActionPerformer {
    override fun perform(action: LauncherAction): Boolean = when (action) {
        LauncherAction.ExpandNotifications -> shade.expandNotifications()

        LauncherAction.ExpandQuickSettings -> shade.expandQuickSettings()

        LauncherAction.OpenSettings -> {
            context.startActivity(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }

        is LauncherAction.LaunchApp -> {
            apps.launch(action.app)
            true
        }

        // 画面ロックには AccessibilityService が要る。今後実装する
        LauncherAction.LockScreen -> false

        LauncherAction.None, LauncherAction.OpenDrawer, LauncherAction.OpenHomeMenu, is LauncherAction.OpenFolder -> false
    }
}
