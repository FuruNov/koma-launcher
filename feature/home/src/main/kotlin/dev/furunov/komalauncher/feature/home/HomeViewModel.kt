package dev.furunov.komalauncher.feature.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.furunov.komalauncher.model.apps.AppCatalog
import dev.furunov.komalauncher.model.apps.AppKey
import dev.furunov.komalauncher.model.apps.AppShortcut
import dev.furunov.komalauncher.model.folders.FolderEdits
import dev.furunov.komalauncher.model.folders.FolderStore
import dev.furunov.komalauncher.model.folders.Folders
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.PanelContent
import dev.furunov.komalauncher.model.layout.PanelId
import dev.furunov.komalauncher.model.ports.ActionPerformer
import dev.furunov.komalauncher.model.ports.LayoutSource
import dev.furunov.komalauncher.model.ports.LayoutState
import dev.furunov.komalauncher.model.settings.GestureKind
import dev.furunov.komalauncher.model.settings.LauncherAction
import dev.furunov.komalauncher.model.settings.Settings
import dev.furunov.komalauncher.model.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** ホームの上に重ねて表示するもの。同時に 1 つだけ。 */
sealed interface Overlay {
    data object None : Overlay

    data class Folder(
        val panelId: PanelId,
    ) : Overlay

    data object Drawer : Overlay

    data object HomeMenu : Overlay

    /** アイコンの長押しのメニュー。[fromPanel] はフォルダから開いたときのコマ（一覧からなら null）。 */
    data class PanelMenu(
        val panelId: PanelId,
    ) : Overlay

    data class Rename(
        val panelId: PanelId,
    ) : Overlay

    data class AppMenu(
        val app: AppKey,
        val fromPanel: String?,
        val shortcuts: List<AppShortcut>,
    ) : Overlay
}

/** アイコンを落とした先。座標からの判断は画面（HomeScreen）が行い、ここには結果だけが来る。 */
sealed interface DropTarget {
    /** フォルダのコマ。[index] はフォルダを開いた中で落としたときの位置。 */
    data class Panel(
        val panelId: String,
        val index: Int? = null,
    ) : DropTarget

    /** 「フォルダから外す」の場所。 */
    data object Remove : DropTarget

    data object Nowhere : DropTarget
}

@Immutable
data class HomeUiState(
    val layout: Layout,
    /** 画像の id → ファイルの絶対パス。 */
    val images: Map<String, String>,
    /** 画像を取り込み直した回数（読み直しの合図）。 */
    val imagesRevision: Long,
    /** 画像の id → 人物の切り抜きのファイルの絶対パス。 */
    val masks: Map<String, String> = emptyMap(),
    /** レイアウトの読み込み・取り込みの誤りの内容。画面の上端に示す。 */
    val layoutError: String?,
    val folders: Folders,
    val overlay: Overlay,
    val settings: Settings,
    /** 直前に外したアプリ（「元に戻す」のため）。 */
    val undo: Folders?,
    val labels: Map<AppKey, String> = emptyMap(),
) {
    fun labelOf(app: AppKey): String = labels[app] ?: app.packageName
}

sealed interface HomeEvent {
    data class TapPanel(
        val panelId: PanelId,
    ) : HomeEvent

    data class Gesture(
        val kind: GestureKind,
    ) : HomeEvent

    data class Launch(
        val app: AppKey,
    ) : HomeEvent

    /** ホームボタン。開いているものを閉じる。 */
    data object Home : HomeEvent

    data object Dismiss : HomeEvent

    /** フォルダの中身の編集。編集の規則は FolderEdits にあり、ここでは適用と「元に戻す」の記録だけをする。 */
    data class EditFolders(
        val recordUndo: Boolean = false,
        val edit: (Folders) -> Folders,
    ) : HomeEvent

    data object Undo : HomeEvent

    data object ClearUndo : HomeEvent

    /** [fromPanel] が null なら全アプリ一覧から運んだ。 */
    data class Drop(
        val app: AppKey,
        val fromPanel: String?,
        val target: DropTarget,
    ) : HomeEvent

    data class ShowAppMenu(
        val app: AppKey,
        val fromPanel: String?,
    ) : HomeEvent

    data class LaunchShortcut(
        val shortcut: AppShortcut,
    ) : HomeEvent

    data class AppInfo(
        val app: AppKey,
    ) : HomeEvent

    data class Uninstall(
        val app: AppKey,
    ) : HomeEvent

    /** 設定画面を開くなど、ジェスチャー以外から操作を実行する。 */
    data class Perform(
        val action: LauncherAction,
    ) : HomeEvent

    data class Rename(
        val panelId: PanelId,
        val name: String?,
    ) : HomeEvent

    data class OpenOverlay(
        val overlay: Overlay,
    ) : HomeEvent
}

class HomeViewModel(
    layoutSource: LayoutSource,
    private val folderStore: FolderStore,
    settingsStore: SettingsStore,
    private val apps: AppCatalog,
    private val actions: ActionPerformer,
) : ViewModel() {
    private val overlay = MutableStateFlow<Overlay>(Overlay.None)
    private val undo = MutableStateFlow<Folders?>(null)

    val state: StateFlow<HomeUiState> =
        combine(layoutSource.state, folderStore.folders, settingsStore.settings, overlay, undo) { l, f, s, o, u ->
            HomeUiState(
                layout = l.layout,
                images = (l as? LayoutState.Loaded)?.images.orEmpty(),
                imagesRevision = (l as? LayoutState.Loaded)?.revision ?: 0L,
                masks = (l as? LayoutState.Loaded)?.masks.orEmpty(),
                layoutError = l.error,
                folders = f,
                overlay = o,
                settings = s,
                undo = u,
            )
        }.combine(apps.apps) { s, list -> s.copy(labels = list.associate { it.key to it.label }) }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                HomeUiState(
                    layout = layoutSource.state.value.layout,
                    images = emptyMap(),
                    imagesRevision = 0L,
                    layoutError = null,
                    folders = folderStore.folders.value,
                    overlay = Overlay.None,
                    settings = settingsStore.settings.value,
                    undo = null,
                ),
            )

    init {
        // 利用者の layout.json を読めたときだけ、フォルダの初期値の取り込みと、消えたコマの整理をする。
        // 見本に戻っているとき（JSON の誤り）に整理すると、書き損じ 1 つで割り当てを失うため。
        // 見本を表示しているときも初期値だけは取り込む（初回の起動で空のフォルダにならないように）。
        layoutSource.state
            .onEach { l ->
                when (l) {
                    is LayoutState.Loaded -> folderStore.update { FolderEdits.reconcile(it, l.layout, apps::resolve) }

                    is LayoutState.Fallback ->
                        if (l.error == null) {
                            folderStore.update { FolderEdits.seedOnly(it, l.layout, apps::resolve) }
                        }
                }
            }.launchIn(viewModelScope)
    }

    fun onEvent(event: HomeEvent) {
        when (event) {
            is HomeEvent.TapPanel -> {
                val panel = state.value.layout.panel(event.panelId) ?: return
                when (panel.content) {
                    is PanelContent.Folder -> overlay.value = Overlay.Folder(panel.id)
                    PanelContent.Drawer -> overlay.value = Overlay.Drawer
                    PanelContent.Widget -> Unit
                }
            }

            is HomeEvent.Gesture -> perform(state.value.settings.actionFor(event.kind))

            is HomeEvent.Launch -> {
                apps.launch(event.app)
                overlay.value = Overlay.None
            }

            HomeEvent.Home, HomeEvent.Dismiss -> overlay.value = Overlay.None

            is HomeEvent.EditFolders -> {
                if (event.recordUndo) undo.value = folderStore.folders.value
                folderStore.update(event.edit)
            }

            HomeEvent.Undo -> {
                val before = undo.value ?: return
                folderStore.update { before }
                undo.value = null
            }

            HomeEvent.ClearUndo -> undo.value = null

            is HomeEvent.OpenOverlay -> overlay.value = event.overlay

            is HomeEvent.Drop -> drop(event)

            is HomeEvent.Perform -> {
                overlay.value = Overlay.None
                perform(event.action)
            }

            is HomeEvent.Rename -> {
                folderStore.update { FolderEdits.rename(it, event.panelId.value, event.name) }
                overlay.value = Overlay.None
            }

            is HomeEvent.ShowAppMenu -> overlay.value = Overlay.AppMenu(event.app, event.fromPanel, apps.shortcuts(event.app))

            is HomeEvent.LaunchShortcut -> {
                apps.launchShortcut(event.shortcut)
                overlay.value = Overlay.None
            }

            is HomeEvent.AppInfo -> {
                apps.openAppInfo(event.app)
                overlay.value = Overlay.None
            }

            is HomeEvent.Uninstall -> {
                apps.requestUninstall(event.app)
                overlay.value = Overlay.None
            }
        }
    }

    private fun drop(e: HomeEvent.Drop) {
        val target = e.target
        val before = folderStore.folders.value
        val after =
            when {
                target is DropTarget.Remove && e.fromPanel != null -> FolderEdits.remove(before, e.fromPanel, e.app)
                target is DropTarget.Panel && e.fromPanel == null -> FolderEdits.add(before, target.panelId, e.app, target.index)
                target is DropTarget.Panel && e.fromPanel != null -> FolderEdits.move(before, e.fromPanel, e.app, target.panelId, target.index)
                else -> before
            }
        if (after != before) {
            // 外したときと別のコマへ移したときは、すぐ戻せるようにする。並べ替えは戻す必要が薄いので記録しない
            val reorderOnly = target is DropTarget.Panel && target.panelId == e.fromPanel
            if (!reorderOnly) undo.value = before
            folderStore.update { after }
        }
        // 同じフォルダの中での並べ替えなら開いたままにし、それ以外は閉じてホームを見せる
        val stayOpen = target is DropTarget.Panel && target.panelId == e.fromPanel
        if (!stayOpen) overlay.value = Overlay.None
    }

    /** ホームの中で完結する操作はここで処理し、それ以外は ActionPerformer に渡す。 */
    private fun perform(action: LauncherAction) {
        when (action) {
            LauncherAction.None -> Unit

            LauncherAction.OpenDrawer -> overlay.value = Overlay.Drawer

            LauncherAction.OpenHomeMenu -> overlay.value = Overlay.HomeMenu

            is LauncherAction.OpenFolder ->
                state.value.layout.panel(PanelId(action.panelId))?.let { overlay.update { _ -> Overlay.Folder(it.id) } }

            else -> actions.perform(action)
        }
    }
}
