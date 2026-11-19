package dev.furunov.komalauncher.feature.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.furunov.komalauncher.model.apps.AppCatalog
import dev.furunov.komalauncher.model.apps.AppKey
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
}

@Immutable
data class HomeUiState(
    val layout: Layout,
    val imagePath: String?,
    /** layout.json の誤りの内容。見本を表示しているときに画面に示す。 */
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
                imagePath = (l as? LayoutState.Loaded)?.imagePath,
                layoutError = (l as? LayoutState.Fallback)?.error,
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
                    imagePath = null,
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
                if (panel.content is PanelContent.Folder) overlay.value = Overlay.Folder(panel.id)
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
        }
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
