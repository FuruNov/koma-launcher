package dev.furunov.komalauncher

import android.content.Context
import dev.furunov.komalauncher.feature.widgets.WidgetHostController
import dev.furunov.komalauncher.model.apps.AppCatalog
import dev.furunov.komalauncher.model.apps.CountingAppCatalog
import dev.furunov.komalauncher.model.apps.LaunchStore
import dev.furunov.komalauncher.model.folders.FolderStore
import dev.furunov.komalauncher.model.ports.ActionPerformer
import dev.furunov.komalauncher.model.ports.BackupService
import dev.furunov.komalauncher.model.ports.LayoutImporter
import dev.furunov.komalauncher.model.ports.LayoutSource
import dev.furunov.komalauncher.model.ports.LayoutState
import dev.furunov.komalauncher.model.ports.LayoutStore
import dev.furunov.komalauncher.model.settings.SettingsStore
import dev.furunov.komalauncher.model.widgets.WidgetStore
import dev.furunov.komalauncher.platform.apps.LauncherAppsCatalog
import dev.furunov.komalauncher.platform.apps.LauncherIconLoader
import dev.furunov.komalauncher.platform.files.SafBackupService
import dev.furunov.komalauncher.platform.files.SafLayoutImporter
import dev.furunov.komalauncher.platform.layout.FileLayoutStore
import dev.furunov.komalauncher.platform.sensor.SensorTiltSource
import dev.furunov.komalauncher.platform.store.FileFolderStore
import dev.furunov.komalauncher.platform.store.FileLaunchStore
import dev.furunov.komalauncher.platform.store.FileSettingsStore
import dev.furunov.komalauncher.platform.store.FileWidgetStore
import dev.furunov.komalauncher.platform.system.BroadcastScreenEvents
import dev.furunov.komalauncher.platform.system.ReflectionSystemShade
import dev.furunov.komalauncher.ui.icons.IconLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    private val fileLayout = FileLayoutStore(context, scope)
    val layoutSource: LayoutSource = fileLayout
    val layoutStore: LayoutStore = fileLayout
    private val fileFolders = FileFolderStore(context, scope)
    private val fileSettings = FileSettingsStore(context, scope)
    val folderStore: FolderStore = fileFolders
    val settingsStore: SettingsStore = fileSettings
    private val fileLaunches = FileLaunchStore(context, scope)
    val launchStore: LaunchStore = fileLaunches

    /** 起動を数えるよう包む。ジェスチャーからの起動（DefaultActionPerformer）もここを通る。 */
    val appCatalog: AppCatalog = CountingAppCatalog(LauncherAppsCatalog(context, scope), fileLaunches)
    val iconLoader: IconLoader = LauncherIconLoader(context)
    val screenEvents = BroadcastScreenEvents(context)
    val tilt = SensorTiltSource(context)
    val actions: ActionPerformer = DefaultActionPerformer(context, ReflectionSystemShade(context), appCatalog)
    private val fileWidgets = FileWidgetStore(context, scope)
    val widgetStore: WidgetStore = fileWidgets

    /** Activity が作り直されてもウィジェットのビューを持ち続けるよう、アプリの寿命で持つ。 */
    val widgets = WidgetHostController(context.applicationContext, widgetStore)
    val importer: LayoutImporter = SafLayoutImporter(context, fileLayout.importDirectory) { layoutSource.state.value.takeIf { it is LayoutState.Loaded }?.layout }
    val backup: BackupService = SafBackupService(context, fileLayout.importDirectory, fileLayout::currentText, folderStore, settingsStore, widgetStore)

    /** 読めずに既定値で始めた保存ファイルがあれば、その知らせ。ホームの上端に出す。 */
    val storeNotice: String? =
        // レイアウトを読めなかったときは、FileLayoutStore が LayoutState の error で知らせる
        listOfNotNull(fileFolders.brokenFile, fileSettings.brokenFile, fileWidgets.brokenFile, fileLaunches.brokenFile)
            .takeIf { it.isNotEmpty() }
            ?.let { files -> "保存ファイルを読み込めなかったため、初期状態で起動しました。元のファイルは ${files.joinToString { it.name }} に保存されています" }

    init {
        // 利用者の layout.json を正しく読めたときだけ、消えたコマのウィジェットを外す（見本に戻ったときは何もしない）
        scope.launch {
            layoutSource.state.collect { if (it is LayoutState.Loaded) widgets.removeOrphans(it.layout) }
        }
    }

    /** まとめて書く途中の保存を、いま書く。ホームが裏に回ったときに呼ぶ。 */
    fun flushStores() {
        fileFolders.flush()
        fileSettings.flush()
        fileWidgets.flush()
        fileLaunches.flush()
        fileLayout.flush()
    }
}
