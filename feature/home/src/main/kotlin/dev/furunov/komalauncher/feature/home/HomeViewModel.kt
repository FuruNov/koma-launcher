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
import dev.furunov.komalauncher.model.layout.LayoutShape
import dev.furunov.komalauncher.model.layout.PanelContent
import dev.furunov.komalauncher.model.layout.PanelId
import dev.furunov.komalauncher.model.media.MediaSessions
import dev.furunov.komalauncher.model.media.NowPlaying
import dev.furunov.komalauncher.model.media.PlaybackStatus
import dev.furunov.komalauncher.model.media.PlayerRules
import dev.furunov.komalauncher.model.ports.ActionPerformer
import dev.furunov.komalauncher.model.ports.LayoutSource
import dev.furunov.komalauncher.model.ports.LayoutState
import dev.furunov.komalauncher.model.settings.GestureKind
import dev.furunov.komalauncher.model.settings.LauncherAction
import dev.furunov.komalauncher.model.settings.Settings
import dev.furunov.komalauncher.model.settings.SettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
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

    /** 音楽プレイヤーに必要な「通知へのアクセス」の説明。 */
    data object MusicAccess : Overlay

    /** フォルダに入れるアプリを選ぶ画面。全アプリの一覧が要るので、中身はホームの外（MainActivity）が作る。 */
    data class AddApps(
        val panelId: PanelId,
    ) : Overlay
}

/** プレイヤーの表示にしているコマ。表示していなければ HomeUiState.player は null。 */
@Immutable
data class PlayerUi(
    val panelId: PanelId,
    /** false なら一時停止中。 */
    val playing: Boolean,
    val packageName: String,
    /** 曲が変わったことの合図。 */
    val trackKey: String,
)

/** アイコンを落とした先。座標からの判断は画面（HomeScreen）が行い、ここには結果だけが来る。 */
sealed interface DropTarget {
    /** フォルダのコマ。[index] はフォルダを開いた中で落としたときの位置。 */
    data class Panel(
        val panelId: String,
        val index: Int? = null,
    ) : DropTarget

    /** 「フォルダから削除」の場所。 */
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
    val player: PlayerUi? = null,
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

    /** コマの上の左右のスワイプ。 */
    data class SwipePanel(
        val panelId: PanelId,
        val toLeft: Boolean,
    ) : HomeEvent

    data class SetMusicPlayer(
        val panelId: PanelId,
        val on: Boolean,
    ) : HomeEvent

    data object OpenPlayerApp : HomeEvent

    data object OpenMusicAccess : HomeEvent

    data object OpenAppInfoForAccess : HomeEvent
}

class HomeViewModel(
    layoutSource: LayoutSource,
    private val folderStore: FolderStore,
    settingsStore: SettingsStore,
    private val apps: AppCatalog,
    private val actions: ActionPerformer,
    private val media: MediaSessions,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val overlay = MutableStateFlow<Overlay>(Overlay.None)
    private val undo = MutableStateFlow<Folders?>(null)

    private class PlayerInputs(
        val layout: Layout,
        val folders: Folders,
        val timeoutMinutes: Int,
        val nowPlaying: NowPlaying?,
        val hasAccess: Boolean,
    )

    /** 一時停止の期限が来たら、もう一度計算して流す（期限を過ぎたらいつものコマに戻すため）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val player: Flow<PlayerUi?> =
        combine(layoutSource.state, folderStore.folders, settingsStore.settings, media.nowPlaying, media.hasAccess) { l, f, s, np, access ->
            PlayerInputs(l.layout, f, s.music.pauseTimeoutMinutes, np, access)
        }.flatMapLatest { inputs ->
            flow {
                emit(playerOf(inputs))
                val hideAt = PlayerRules.hideAtMillis(inputs.nowPlaying, inputs.timeoutMinutes) ?: return@flow
                val wait = hideAt - clock()
                if (wait > 0) {
                    delay(wait)
                    emit(playerOf(inputs))
                }
            }
        }

    private fun playerOf(i: PlayerInputs): PlayerUi? {
        val id = i.folders.musicPanel() ?: return null
        if (i.layout.shape !is LayoutShape.Tree || !i.hasAccess) return null
        val panel = i.layout.panel(PanelId(id))?.takeIf { it.content is PanelContent.Folder } ?: return null
        val np = i.nowPlaying ?: return null
        if (!PlayerRules.showsPlayer(np, clock(), i.timeoutMinutes)) return null
        return PlayerUi(panel.id, np.status != PlaybackStatus.PAUSED, np.packageName, np.trackKey)
    }

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
            .combine(player) { s, p -> s.copy(player = p) }
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
                // プレイヤーの表示の間は、タップで再生・一時停止を切り替える（フォルダは長押しのメニューから開く）
                if (state.value.player?.panelId == event.panelId) {
                    media.togglePlayPause()
                    return
                }
                val panel = state.value.layout.panel(event.panelId) ?: return
                when (panel.content) {
                    is PanelContent.Folder -> {
                        // 起動はコマを離した時点。フォルダを開くときと同じく、押したときの動きの完了を待たない
                        val first = directLaunchTarget(panel.id)
                        if (first != null) {
                            apps.launch(first)
                            overlay.value = Overlay.None
                        } else {
                            overlay.value = Overlay.Folder(panel.id)
                        }
                    }

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

            is HomeEvent.SwipePanel ->
                if (state.value.player?.panelId == event.panelId) {
                    if (event.toLeft) media.next() else media.previous()
                }

            is HomeEvent.SetMusicPlayer -> {
                folderStore.update { FolderEdits.setMusicPlayer(it, event.panelId.value, event.on) }
                overlay.value = if (event.on && !media.hasAccess.value) Overlay.MusicAccess else Overlay.None
            }

            HomeEvent.OpenPlayerApp -> {
                media.openPlayer()
                overlay.value = Overlay.None
            }

            HomeEvent.OpenMusicAccess -> {
                media.openAccessSettings()
                overlay.value = Overlay.None
            }

            HomeEvent.OpenAppInfoForAccess -> {
                media.openAppInfo()
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

    /**
     * 「直接起動」のフォルダなら、起動するアプリ（入っているアプリのうち先頭）。
     * 切り替えがオフか、起動できるアプリが 1 つも無ければ null（フォルダを開く）。
     */
    private fun directLaunchTarget(panelId: PanelId): AppKey? {
        val folder = folderStore.folders.value.byPanel[panelId.value] ?: return null
        if (!folder.directLaunch) return null
        val installed = apps.apps.value.mapTo(HashSet()) { it.key }
        return folder.apps.firstOrNull { it in installed }
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
