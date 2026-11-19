package dev.furunov.komalauncher

import android.content.Context
import dev.furunov.komalauncher.feature.widgets.WidgetHostController
import dev.furunov.komalauncher.model.apps.AppCatalog
import dev.furunov.komalauncher.model.folders.FolderStore
import dev.furunov.komalauncher.model.ports.ActionPerformer
import dev.furunov.komalauncher.model.ports.LayoutSource
import dev.furunov.komalauncher.model.settings.SettingsStore
import dev.furunov.komalauncher.model.widgets.WidgetStore
import dev.furunov.komalauncher.platform.apps.LauncherAppsCatalog
import dev.furunov.komalauncher.platform.apps.LauncherIconLoader
import dev.furunov.komalauncher.platform.layout.FileLayoutSource
import dev.furunov.komalauncher.platform.store.FileFolderStore
import dev.furunov.komalauncher.platform.store.FileSettingsStore
import dev.furunov.komalauncher.platform.store.FileWidgetStore
import dev.furunov.komalauncher.platform.system.BroadcastScreenEvents
import dev.furunov.komalauncher.platform.system.ReflectionSystemShade
import dev.furunov.komalauncher.ui.icons.IconLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 実装を組み立てる場所。依存の注入は Hilt を使わず、ここでコンストラクタに渡す。
 * この規模では、Hilt の生成コードと設定を保守するほうが手間が大きいため（README に理由）。
 * 機能のモジュールはインターフェースだけを受け取り、ここにある実装クラスを知らない。
 */
class AppContainer(
    context: Context,
) {
    /** アプリの寿命の処理（保存、アプリ一覧の更新）に使う。 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val layoutSource: LayoutSource = FileLayoutSource(context, scope)
    val folderStore: FolderStore = FileFolderStore(context, scope)
    val settingsStore: SettingsStore = FileSettingsStore(context, scope)
    val appCatalog: AppCatalog = LauncherAppsCatalog(context, scope)
    val iconLoader: IconLoader = LauncherIconLoader(context)
    val screenEvents = BroadcastScreenEvents(context)
    val actions: ActionPerformer = DefaultActionPerformer(context, ReflectionSystemShade(context), appCatalog)
    val widgetStore: WidgetStore = FileWidgetStore(context, scope)

    /** Activity が作り直されてもウィジェットのビューを持ち続けるよう、アプリの寿命で持つ。 */
    val widgets = WidgetHostController(context.applicationContext, widgetStore)
}
