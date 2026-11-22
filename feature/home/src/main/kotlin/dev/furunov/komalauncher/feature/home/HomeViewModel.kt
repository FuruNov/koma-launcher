package dev.furunov.komalauncher.feature.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.furunov.komalauncher.model.apps.AppCatalog
import dev.furunov.komalauncher.model.apps.AppEntry
import dev.furunov.komalauncher.model.apps.AppKey
import dev.furunov.komalauncher.model.apps.AppMigration
import dev.furunov.komalauncher.model.apps.AppShortcut
import dev.furunov.komalauncher.model.apps.DrawerFolders
import dev.furunov.komalauncher.model.folders.FolderEdits
import dev.furunov.komalauncher.model.folders.FolderStore
import dev.furunov.komalauncher.model.folders.Folders
import dev.furunov.komalauncher.model.folders.PanelLinks
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
import kotlinx.coroutines.launch

/** ホームの上に重ねて表示するもの。同時に 1 つだけ。 */
sealed interface Overlay {
    /**
     * true なら、全アプリ一覧を開いたまま、その上に重ねて表示する（一覧で開いたメニューなど）。閉じたら一覧に戻る。
     * 一覧で長押ししたメニューのたびに一覧を閉じると、開いていたフォルダや位置が分からなくなり、直感的でないと利用者から指摘があった（2026-11-22）。
     */
    val overDrawer: Boolean get() = false

    data object None : Overlay

    data class Folder(
        val panelId: PanelId,
    ) : Overlay

    data object Drawer : Overlay {
        override val overDrawer: Boolean get() = true
    }

    data object HomeMenu : Overlay

    /** 自由に置くウィジェットの長押しのメニュー。 */
    data class WidgetMenu(
        val widgetId: String,
    ) : Overlay

    /** 内蔵のウィジェット（時計・テキスト）の見た目を変えるシート。 */
    data class WidgetStyle(
        val widgetId: String,
    ) : Overlay

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
        /** true なら全アプリ一覧で長押しした。「非表示」と「フォルダを変更」を表示する。 */
        val fromDrawer: Boolean = false,
        /**
         * メニューの下に表示したまま残す画面（全アプリ一覧かフォルダ）。閉じたらここに戻る。
         * 長押しですぐメニューを出すので、長押しした元の画面を消すと、続けてドラッグの操作も止まってしまう。そのため残す。
         */
        val under: Overlay = None,
    ) : Overlay {
        override val overDrawer: Boolean get() = under.overDrawer
    }

    /** 追加したショートカット（PWA など）の削除の確認。固定の解除はランチャーから戻せないので、押す前に尋ねる。 */
    data class ConfirmRemove(
        val app: AppKey,
        /** true なら、前回の解除に失敗した。確認の画面にそのことを表示する。 */
        val failed: Boolean = false,
        /** true なら全アプリ一覧のメニューから開いた。 */
        val fromDrawer: Boolean = false,
    ) : Overlay {
        override val overDrawer: Boolean get() = fromDrawer
    }

    /** 音楽プレイヤーに必要な「通知へのアクセス」の説明。 */
    data object MusicAccess : Overlay

    /** コマの「ドロワーと同期」。[choices] は全アプリ一覧のフォルダの id と名前、[current] は今紐付けているフォルダの id（無ければ null）。 */
    data class LinkPanel(
        val panelId: PanelId,
        val choices: List<Pair<String, String>>,
        val current: String?,
    ) : Overlay

    /** 全アプリ一覧の「フォルダを変更」。[choices] はフォルダの id と名前、[current] は今入っているフォルダの id。 */
    data class DrawerFolderMenu(
        val app: AppKey,
        val choices: List<Pair<String, String>>,
        val current: String,
    ) : Overlay {
        override val overDrawer: Boolean get() = true
    }

    /** 全アプリ一覧のフォルダの名前の入力。[folderId] が null なら新しいフォルダを作って [app] を移し、そうでなければ名前を変える。 */
    data class DrawerFolderName(
        val app: AppKey?,
        val folderId: String?,
        val current: String,
    ) : Overlay {
        override val overDrawer: Boolean get() = true
    }

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
    /** 画像をインポートし直した回数（読み直しの合図）。 */
    val imagesRevision: Long,
    /** 画像の id → 人物の切り抜きのファイルの絶対パス。 */
    val masks: Map<String, String> = emptyMap(),
    /** レイアウトの読み込み・インポートの誤りの内容。画面の上端に示す。 */
    val layoutError: String?,
    val folders: Folders,
    val overlay: Overlay,
    val settings: Settings,
    /** 直前に外したアプリ（「元に戻す」のため）。 */
    val undo: Folders?,
    val labels: Map<AppKey, String> = emptyMap(),
    val player: PlayerUi? = null,
    /** true なら「配置を調整」の状態（時計・大見出し・名前・アイコンを指で動かし、拡大縮小する）。 */
    val adjusting: Boolean = false,
) {
    fun labelOf(app: AppKey): String = labels[app] ?: app.packageName

    /** 表示するフォルダの中身。一覧に無いアプリとショートカットを隠す（Folders.shownAppsOf）。 */
    fun shownAppsOf(panelId: String): List<AppKey> = folders.shownAppsOf(panelId, labels.keys)
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

    /** 「配置を調整」の状態に入る・出る。 */
    data class Adjust(
        val on: Boolean,
    ) : HomeEvent

    data object Dismiss : HomeEvent

    /** 長押しのメニューの「削除」。確認を出すだけで、まだ解除しない。 */
    data class RemoveShortcut(
        val app: AppKey,
    ) : HomeEvent

    /** 確認の「削除」。固定を解除し、全てのフォルダから外す。 */
    data class ConfirmRemoveShortcut(
        val app: AppKey,
    ) : HomeEvent

    /** フォルダの中身の編集。編集の規則は FolderEdits にあり、ここでは適用と「元に戻す」の記録だけをする。 */
    data class EditFolders(
        val recordUndo: Boolean = false,
        val edit: (Folders) -> Folders,
    ) : HomeEvent

    data object Undo : HomeEvent

    data object ClearUndo : HomeEvent

    /** [fromPanel] が null なら全アプリ一覧からドラッグした。 */
    data class Drop(
        val app: AppKey,
        val fromPanel: String?,
        val target: DropTarget,
    ) : HomeEvent

    data class ShowAppMenu(
        val app: AppKey,
        val fromPanel: String?,
        val fromDrawer: Boolean = false,
    ) : HomeEvent

    /** 長押しの後に運び始めたとき。長押しで出したメニューを消し、下に残していた画面に戻る。 */
    data object HideAppMenu : HomeEvent

    /** コマのメニューの「ドロワーと同期」。紐付ける全アプリ一覧のフォルダを選ぶ画面を出す。 */
    data class ShowLinkPanel(
        val panelId: PanelId,
    ) : HomeEvent

    /** コマを全アプリ一覧のフォルダ [drawerFolder] に紐付ける。null なら紐付けを外す。 */
    data class LinkPanel(
        val panelId: PanelId,
        val drawerFolder: String?,
    ) : HomeEvent

    /** 長押しのメニューの「フォルダを変更」。移す先を選ぶ画面を出す。 */
    data class ShowDrawerFolders(
        val app: AppKey,
    ) : HomeEvent

    data class MoveToDrawerFolder(
        val app: AppKey,
        val folderId: String,
    ) : HomeEvent

    /** フォルダのタブの長押し。名前の入力を出す。 */
    data class EditDrawerFolderName(
        val folderId: String,
    ) : HomeEvent

    /** 名前の入力の「保存」。[Overlay.DrawerFolderName] と同じく、[folderId] が null なら作って移す。 */
    data class NameDrawerFolder(
        val app: AppKey?,
        val folderId: String?,
        val name: String,
    ) : HomeEvent

    /** 長押しのメニューの「非表示」。全アプリ一覧から外す。戻すのは設定画面の「非表示のアプリ」。 */
    data class Hide(
        val app: AppKey,
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
    private val settingsStore: SettingsStore,
    private val apps: AppCatalog,
    private val actions: ActionPerformer,
    private val media: MediaSessions,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val overlay = MutableStateFlow<Overlay>(Overlay.None)
    private val adjusting = MutableStateFlow(false)
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
        }.combine(apps.apps) { s, list -> s.copy(labels = list.associate { it.key to it.label }, folders = PanelLinks.effective(s.folders, s.settings.drawer, list)) }
            .combine(player) { s, p -> s.copy(player = p) }
            .combine(adjusting) { s, a -> s.copy(adjusting = a) }
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
        // 利用者の layout.json を読めたときだけ、フォルダの初期値のインポートと、消えたコマの整理をする。
        // サンプルに戻っているとき（JSON の誤り）に整理すると、書き損じ 1 つで割り当てを失うため。
        // サンプルを表示しているときも初期値だけはインポートする（初回の起動で空のフォルダにならないように）。
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

        // アプリの一覧を読み込んだら、機種変更などで入口の名前だけが違う同じアプリを今の入口に読み替え、
        // 登録してあるアプリの名前を覚える（まだ入れていないアプリの名前を、新しい端末で出すため）。AppMigration
        apps.apps
            .onEach { list ->
                if (list.isEmpty()) return@onEach
                val drawer = settingsStore.settings.value.drawer
                val m = AppMigration.remapping(AppMigration.registered(folderStore.folders.value, drawer) + drawer.hidden, list)
                if (m.isNotEmpty()) {
                    folderStore.update { AppMigration.remap(it, m) }
                    settingsStore.update { it.copy(drawer = AppMigration.remap(it.drawer, m)) }
                }
                settingsStore.update { s ->
                    val names = AppMigration.names(folderStore.folders.value, s.drawer, list, s.appNames)
                    if (names == s.appNames) s else s.copy(appNames = names)
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
                }
            }

            is HomeEvent.Gesture -> perform(state.value.settings.actionFor(event.kind))

            is HomeEvent.Launch -> {
                apps.launch(event.app)
                overlay.value = Overlay.None
            }

            HomeEvent.Home -> {
                overlay.value = Overlay.None
                adjusting.value = false
            }

            HomeEvent.Dismiss -> {
                // メニューを閉じたら、下に残していた画面（全アプリ一覧かフォルダ）に戻る。一覧そのものを閉じたらホームに戻る
                val o = overlay.value
                overlay.value =
                    when {
                        o is Overlay.AppMenu -> o.under
                        o.overDrawer && o != Overlay.Drawer -> Overlay.Drawer
                        else -> Overlay.None
                    }
                adjusting.value = false
            }

            is HomeEvent.Adjust -> {
                overlay.value = Overlay.None
                adjusting.value = event.on
            }

            is HomeEvent.EditFolders -> {
                val before = shown()
                if (event.recordUndo) undo.value = before
                commit(before, event.edit(before))
            }

            HomeEvent.Undo -> {
                val before = undo.value ?: return
                commit(shown(), before)
                undo.value = null
            }

            is HomeEvent.ShowLinkPanel -> {
                val drawer = settingsStore.settings.value.drawer
                overlay.value = Overlay.LinkPanel(event.panelId, DrawerFolders.choices(drawer), folderStore.folders.value.byPanel[event.panelId.value]?.drawerFolder)
            }

            is HomeEvent.LinkPanel -> {
                val current = shown()
                folderStore.update { PanelLinks.link(it, current, event.panelId.value, event.drawerFolder) }
                overlay.value = Overlay.None
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

            is HomeEvent.ShowAppMenu -> {
                // 全アプリ一覧とフォルダは、メニューの下に残す
                val current = overlay.value
                val under =
                    when {
                        event.fromDrawer -> Overlay.Drawer
                        current is Overlay.Folder -> current
                        else -> Overlay.None
                    }
                overlay.value = Overlay.AppMenu(event.app, event.fromPanel, apps.shortcuts(event.app), event.fromDrawer, under)
            }

            HomeEvent.HideAppMenu -> (overlay.value as? Overlay.AppMenu)?.let { overlay.value = it.under }

            is HomeEvent.ShowDrawerFolders -> {
                val drawer = settingsStore.settings.value.drawer
                overlay.value = Overlay.DrawerFolderMenu(event.app, DrawerFolders.choices(drawer), DrawerFolders.folderOf(entryOf(event.app), drawer))
            }

            is HomeEvent.MoveToDrawerFolder -> {
                settingsStore.update { it.copy(drawer = DrawerFolders.move(it.drawer, entryOf(event.app), event.folderId)) }
                overlay.value = Overlay.Drawer
            }

            is HomeEvent.EditDrawerFolderName ->
                overlay.value = Overlay.DrawerFolderName(null, event.folderId, DrawerFolders.nameOf(event.folderId, settingsStore.settings.value.drawer))

            is HomeEvent.NameDrawerFolder -> {
                val id = event.folderId
                val app = event.app
                if (id != null) {
                    settingsStore.update { it.copy(drawer = DrawerFolders.rename(it.drawer, id, event.name)) }
                } else if (app != null && event.name.isNotBlank()) {
                    settingsStore.update { s ->
                        val (drawer, created) = DrawerFolders.create(s.drawer, event.name)
                        s.copy(drawer = DrawerFolders.move(drawer, entryOf(app), created))
                    }
                }
                overlay.value = Overlay.Drawer
            }

            is HomeEvent.Hide -> {
                settingsStore.update { it.copy(drawer = it.drawer.copy(hidden = it.drawer.hidden + event.app)) }
                overlay.value = Overlay.Drawer
            }

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

            is HomeEvent.RemoveShortcut -> overlay.value = Overlay.ConfirmRemove(event.app, fromDrawer = overlay.value.overDrawer)

            is HomeEvent.ConfirmRemoveShortcut ->
                viewModelScope.launch {
                    val fromDrawer = overlay.value.overDrawer
                    // 解除できたときだけフォルダから外す。失敗したら、確認の画面に残して知らせる
                    if (!apps.unpin(event.app)) {
                        overlay.value = Overlay.ConfirmRemove(event.app, failed = true, fromDrawer = fromDrawer)
                        return@launch
                    }
                    // 全てのコマと「割り当てなし」から外す
                    folderStore.update { f -> f.byPanel.keys.fold(f) { acc, panel -> FolderEdits.remove(acc, panel, event.app) }.let { it.copy(unassigned = it.unassigned - event.app) } }
                    overlay.value = if (fromDrawer) Overlay.Drawer else Overlay.None
                }

            is HomeEvent.SwipePanel ->
                if (state.value.player?.panelId == event.panelId) {
                    // 右へスワイプすると次の曲、左へスワイプすると前の曲（利用者が実機で試し、こちらが直感に合うとした。2026-11-20）
                    if (event.toLeft) media.previous() else media.next()
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
        val before = shown()
        // フォルダの画面の位置は、一覧に無いアプリとショートカットを隠した並びの位置なので、データの中の位置に直す
        val target =
            (e.target as? DropTarget.Panel)?.takeIf { it.index != null }?.let { t ->
                val available = apps.apps.value.mapTo(HashSet()) { it.key }
                t.copy(index = FolderEdits.dataIndex(before.appsOf(t.panelId), t.index!!) { available.isEmpty() || it in available })
            } ?: e.target
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
            commit(before, after)
        }
        // 同じフォルダの中での並べ替えなら開いたままにし、それ以外は閉じてホームを見せる
        val stayOpen = target is DropTarget.Panel && target.panelId == e.fromPanel
        if (!stayOpen) overlay.value = Overlay.None
    }

    /** 分類を知るための一覧の項目。一覧に無ければ（アンインストールの直後など）分類の無い項目として扱う。 */
    private fun entryOf(app: AppKey): AppEntry = apps.apps.value.firstOrNull { it.key == app } ?: AppEntry(app, "")

    /** 表示に使うフォルダの中身。全アプリ一覧のフォルダに紐付けたコマは、そのフォルダの中身になる（PanelLinks）。 */
    private fun shown(): Folders = PanelLinks.effective(folderStore.folders.value, settingsStore.settings.value.drawer, apps.apps.value)

    /** 編集の前後（どちらも [shown] の中身）から、コマの中身と、紐付けた全アプリ一覧のフォルダの両方を書く。 */
    private fun commit(
        before: Folders,
        after: Folders,
    ) {
        if (after == before) return
        folderStore.update { PanelLinks.storable(it, after) }
        settingsStore.update { it.copy(drawer = PanelLinks.sync(before, after, it.drawer, ::entryOf)) }
    }

    /**
     * 「直接起動」のフォルダなら、起動するアプリ（入っているアプリのうち先頭）。
     * 切り替えがオフか、起動できるアプリが 1 つも無ければ null（フォルダを開く）。
     */
    private fun directLaunchTarget(panelId: PanelId): AppKey? {
        val folder = shown().byPanel[panelId.value] ?: return null
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
