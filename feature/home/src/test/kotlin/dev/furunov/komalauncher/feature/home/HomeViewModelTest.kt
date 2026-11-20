package dev.furunov.komalauncher.feature.home

import dev.furunov.komalauncher.model.apps.AppCatalog
import dev.furunov.komalauncher.model.apps.AppEntry
import dev.furunov.komalauncher.model.apps.AppKey
import dev.furunov.komalauncher.model.folders.FolderEdits
import dev.furunov.komalauncher.model.folders.FolderStore
import dev.furunov.komalauncher.model.folders.Folders
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.LayoutNode
import dev.furunov.komalauncher.model.layout.LayoutShape
import dev.furunov.komalauncher.model.media.MediaSessions
import dev.furunov.komalauncher.model.media.NowPlaying
import dev.furunov.komalauncher.model.media.PlaybackStatus
import dev.furunov.komalauncher.model.layout.Panel
import dev.furunov.komalauncher.model.layout.PanelContent
import dev.furunov.komalauncher.model.layout.PanelId
import dev.furunov.komalauncher.model.ports.ActionPerformer
import dev.furunov.komalauncher.model.ports.LayoutSource
import dev.furunov.komalauncher.model.ports.LayoutState
import dev.furunov.komalauncher.model.settings.GestureKind
import dev.furunov.komalauncher.model.settings.LauncherAction
import dev.furunov.komalauncher.model.settings.Settings
import dev.furunov.komalauncher.model.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val chrome = AppKey("com.android.chrome", "Main")

    /** id の頭文字で種類を決める（w: ウィジェット、d: 全アプリ一覧、それ以外: フォルダ）。 */
    private fun layout(vararg ids: String) = Layout.of(
        ids.map {
            val content =
                when {
                    it.startsWith("w") -> PanelContent.Widget
                    it.startsWith("d") -> PanelContent.Drawer
                    else -> PanelContent.Folder(listOf("com.android.chrome"))
                }
            Panel(PanelId(it), content)
        },
    )

    private class FakeLayout(
        initial: LayoutState,
    ) : LayoutSource {
        val flow = MutableStateFlow(initial)
        override val state: StateFlow<LayoutState> = flow
    }

    private class FakeFolders : FolderStore {
        val flow = MutableStateFlow(Folders())
        override val folders: StateFlow<Folders> = flow

        override fun update(transform: (Folders) -> Folders) = flow.update(transform)
    }

    private class FakeSettings(
        s: Settings = Settings(),
    ) : SettingsStore {
        val flow = MutableStateFlow(s)
        override val settings: StateFlow<Settings> = flow

        override fun update(transform: (Settings) -> Settings) = flow.update(transform)
    }

    private inner class FakeApps : AppCatalog {
        val launched = mutableListOf<AppKey>()
        override val apps: StateFlow<List<AppEntry>> = MutableStateFlow(listOf(AppEntry(chrome, "Chrome")))

        override fun resolve(packageName: String) = chrome.takeIf { it.packageName == packageName }

        override fun launch(key: AppKey) {
            launched += key
        }

        override fun openAppInfo(key: AppKey) = Unit

        override fun requestUninstall(key: AppKey) = Unit

        override fun shortcuts(key: AppKey) = emptyList<dev.furunov.komalauncher.model.apps.AppShortcut>()

        override fun launchShortcut(shortcut: dev.furunov.komalauncher.model.apps.AppShortcut) = Unit

        override fun allShortcuts(): List<dev.furunov.komalauncher.model.apps.AppShortcut> = emptyList()
    }

    private class FakeMedia : MediaSessions {
        val calls = mutableListOf<String>()
        override val nowPlaying = MutableStateFlow<NowPlaying?>(null)
        override val hasAccess = MutableStateFlow(true)

        override fun start() = Unit

        override fun stop() = Unit

        override fun togglePlayPause() {
            calls += "toggle"
        }

        override fun next() {
            calls += "next"
        }

        override fun previous() {
            calls += "previous"
        }

        override fun openPlayer() {
            calls += "openPlayer"
        }

        override fun openAccessSettings() {
            calls += "openAccessSettings"
        }

        override fun openAppInfo() {
            calls += "openAppInfo"
        }
    }

    private class FakeActions : ActionPerformer {
        val performed = mutableListOf<LauncherAction>()

        override fun perform(action: LauncherAction): Boolean {
            performed += action
            return true
        }
    }

    private lateinit var layoutSource: FakeLayout
    private lateinit var folders: FakeFolders
    private lateinit var appsFake: FakeApps
    private lateinit var actions: FakeActions
    private lateinit var vm: HomeViewModel
    private lateinit var media: FakeMedia
    private var now = 0L

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        layoutSource = FakeLayout(LayoutState.Loaded(layout("p1", "w1", "d1"), emptyMap()))
        folders = FakeFolders()
        appsFake = FakeApps()
        actions = FakeActions()
        media = FakeMedia()
        now = 0L
        vm = HomeViewModel(layoutSource, folders, FakeSettings(), appsFake, actions, media) { now }
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun seedsFolderFromLayout() = assertEquals(listOf(chrome), vm.state.value.folders.appsOf("p1"))

    @Test
    fun tapFolderPanelOpensIt() {
        vm.onEvent(HomeEvent.TapPanel(PanelId("p1")))
        assertEquals(Overlay.Folder(PanelId("p1")), vm.state.value.overlay)
    }

    @Test
    fun tapWidgetPanelDoesNotOpenFolder() {
        vm.onEvent(HomeEvent.TapPanel(PanelId("w1")))
        assertEquals(Overlay.None, vm.state.value.overlay)
    }

    @Test
    fun tapDrawerPanelOpensDrawer() {
        vm.onEvent(HomeEvent.TapPanel(PanelId("d1")))
        assertEquals(Overlay.Drawer, vm.state.value.overlay)
    }

    @Test
    fun importErrorIsShownWhileKeepingLayout() {
        layoutSource.flow.value = LayoutState.Loaded(layout("p1", "w1", "d1"), emptyMap(), error = "取り込めない")
        assertEquals("取り込めない", vm.state.value.layoutError)
        assertEquals(listOf(chrome), vm.state.value.folders.appsOf("p1"))
    }

    @Test
    fun homeClosesOverlay() {
        vm.onEvent(HomeEvent.TapPanel(PanelId("p1")))
        vm.onEvent(HomeEvent.Home)
        assertEquals(Overlay.None, vm.state.value.overlay)
    }

    @Test
    fun launchClosesFolder() {
        vm.onEvent(HomeEvent.TapPanel(PanelId("p1")))
        vm.onEvent(HomeEvent.Launch(chrome))
        assertEquals(listOf(chrome), appsFake.launched)
        assertEquals(Overlay.None, vm.state.value.overlay)
    }

    @Test
    fun gesturesFollowSettings() {
        vm.onEvent(HomeEvent.Gesture(GestureKind.SwipeUp))
        assertEquals(Overlay.Drawer, vm.state.value.overlay)
        vm.onEvent(HomeEvent.Gesture(GestureKind.SwipeDown))
        assertEquals(listOf<LauncherAction>(LauncherAction.ExpandNotifications), actions.performed)
    }

    @Test
    fun brokenLayoutDoesNotDropAssignments() {
        layoutSource.flow.value = LayoutState.Fallback(layout("sample-1"), error = "壊れている")
        assertEquals(listOf(chrome), vm.state.value.folders.appsOf("p1"))
        assertEquals("壊れている", vm.state.value.layoutError)
    }

    @Test
    fun removeAndUndo() {
        vm.onEvent(HomeEvent.EditFolders(recordUndo = true) { FolderEdits.remove(it, "p1", chrome) })
        assertEquals(emptyList(), vm.state.value.folders.appsOf("p1"))
        vm.onEvent(HomeEvent.Undo)
        assertEquals(listOf(chrome), vm.state.value.folders.appsOf("p1"))
    }

    private val other = AppKey("org.example", "Main")

    @Test
    fun dropFromDrawerAddsToPanel() {
        vm.onEvent(HomeEvent.OpenOverlay(Overlay.Drawer))
        vm.onEvent(HomeEvent.Drop(other, fromPanel = null, target = DropTarget.Panel("p1")))
        assertEquals(listOf(chrome, other), vm.state.value.folders.appsOf("p1"))
        assertEquals(Overlay.None, vm.state.value.overlay)
        // 追加は「元に戻す」で取り消せる
        vm.onEvent(HomeEvent.Undo)
        assertEquals(listOf(chrome), vm.state.value.folders.appsOf("p1"))
    }

    @Test
    fun dropOnRemoveZoneRemovesWithUndo() {
        vm.onEvent(HomeEvent.Drop(chrome, fromPanel = "p1", target = DropTarget.Remove))
        assertEquals(emptyList(), vm.state.value.folders.appsOf("p1"))
        vm.onEvent(HomeEvent.Undo)
        assertEquals(listOf(chrome), vm.state.value.folders.appsOf("p1"))
    }

    @Test
    fun dropFromDrawerOnRemoveZoneDoesNothing() {
        vm.onEvent(HomeEvent.Drop(other, fromPanel = null, target = DropTarget.Remove))
        assertEquals(listOf(chrome), vm.state.value.folders.appsOf("p1"))
        assertEquals(null, vm.state.value.undo)
    }

    @Test
    fun dropNowhereKeepsFolders() {
        vm.onEvent(HomeEvent.Drop(chrome, fromPanel = "p1", target = DropTarget.Nowhere))
        assertEquals(listOf(chrome), vm.state.value.folders.appsOf("p1"))
    }

    @Test
    fun reorderInsideFolderKeepsItOpen() {
        vm.onEvent(HomeEvent.Drop(other, fromPanel = null, target = DropTarget.Panel("p1")))
        vm.onEvent(HomeEvent.TapPanel(PanelId("p1")))
        vm.onEvent(HomeEvent.Drop(other, fromPanel = "p1", target = DropTarget.Panel("p1", index = 0)))
        assertEquals(listOf(other, chrome), vm.state.value.folders.appsOf("p1"))
        assertEquals(Overlay.Folder(PanelId("p1")), vm.state.value.overlay)
    }

    private val missing = AppKey("gone", "Main")

    private fun directLaunch(vararg apps: AppKey) = folders.update {
        FolderEdits.setDirectLaunch(it.copy(byPanel = it.byPanel + ("p1" to it.byPanel.getValue("p1").copy(apps = apps.toList()))), "p1", true)
    }

    @Test
    fun directLaunchStartsFirstApp() {
        directLaunch(chrome)
        vm.onEvent(HomeEvent.TapPanel(PanelId("p1")))
        assertEquals(listOf(chrome), appsFake.launched)
        assertEquals(Overlay.None, vm.state.value.overlay)
    }

    @Test
    fun directLaunchSkipsMissingApps() {
        directLaunch(missing, chrome)
        vm.onEvent(HomeEvent.TapPanel(PanelId("p1")))
        assertEquals(listOf(chrome), appsFake.launched)
    }

    @Test
    fun directLaunchWithOnlyMissingAppsOpensFolder() {
        directLaunch(missing)
        vm.onEvent(HomeEvent.TapPanel(PanelId("p1")))
        assertEquals(emptyList(), appsFake.launched)
        assertEquals(Overlay.Folder(PanelId("p1")), vm.state.value.overlay)
    }

    @Test
    fun directLaunchEmptyOpensFolder() {
        directLaunch()
        vm.onEvent(HomeEvent.TapPanel(PanelId("p1")))
        assertEquals(Overlay.Folder(PanelId("p1")), vm.state.value.overlay)
    }

    @Test
    fun gestureOpenFolderIgnoresDirectLaunch() {
        directLaunch(chrome)
        vm.onEvent(HomeEvent.Perform(LauncherAction.OpenFolder("p1")))
        assertEquals(emptyList(), appsFake.launched)
        assertEquals(Overlay.Folder(PanelId("p1")), vm.state.value.overlay)
    }

    // ---- 音楽プレイヤーのコマ ----

    /** プレイヤーの表示は線の木（version 2）のレイアウトだけで行うので、2 つのフォルダのコマの木を使う。 */
    private fun useTree() {
        val leaf = { id: String -> LayoutNode.Leaf(Panel(PanelId(id), PanelContent.Folder(listOf("com.android.chrome")))) }
        val tree = Layout(LayoutShape.Tree(LayoutNode.Split(0f, 1f, leaf("p1"), leaf("p2"))))
        layoutSource.flow.value = LayoutState.Loaded(tree, emptyMap())
    }

    private fun play(status: PlaybackStatus = PlaybackStatus.PLAYING, pausedAt: Long? = null) {
        media.nowPlaying.value = NowPlaying("com.example.music", status, "t", pausedAt)
    }

    @Test
    fun tapPlayerPanelTogglesPlayback() {
        useTree()
        folders.update { FolderEdits.setMusicPlayer(it, "p1", true) }
        play()
        vm.onEvent(HomeEvent.TapPanel(PanelId("p1")))
        assertEquals(listOf("toggle"), media.calls)
        assertEquals(Overlay.None, vm.state.value.overlay)
        assertEquals(PanelId("p1"), vm.state.value.player?.panelId)
    }

    @Test
    fun swipeOnPlayerPanelSkips() {
        useTree()
        folders.update { FolderEdits.setMusicPlayer(it, "p1", true) }
        play()
        vm.onEvent(HomeEvent.SwipePanel(PanelId("p1"), toLeft = true))
        vm.onEvent(HomeEvent.SwipePanel(PanelId("p1"), toLeft = false))
        assertEquals(listOf("next", "previous"), media.calls)
    }

    @Test
    fun withoutSessionPlayerPanelOpensFolder() {
        useTree()
        folders.update { FolderEdits.setMusicPlayer(it, "p1", true) }
        vm.onEvent(HomeEvent.TapPanel(PanelId("p1")))
        assertEquals(Overlay.Folder(PanelId("p1")), vm.state.value.overlay)
        assertEquals(null, vm.state.value.player)
    }

    @Test
    fun pausedPastTimeoutOpensFolder() {
        useTree()
        folders.update { FolderEdits.setMusicPlayer(it, "p1", true) }
        now = 31 * 60_000L
        play(PlaybackStatus.PAUSED, pausedAt = 0L)
        assertEquals(null, vm.state.value.player)
        vm.onEvent(HomeEvent.TapPanel(PanelId("p1")))
        assertEquals(Overlay.Folder(PanelId("p1")), vm.state.value.overlay)
    }

    @Test
    fun pausedWithinTimeoutShowsPausedPlayer() {
        useTree()
        folders.update { FolderEdits.setMusicPlayer(it, "p1", true) }
        now = 10 * 60_000L
        play(PlaybackStatus.PAUSED, pausedAt = 0L)
        assertEquals(false, vm.state.value.player?.playing)
    }

    @Test
    fun directLaunchStillWorksWhenNotPlaying() {
        useTree()
        folders.update { FolderEdits.setDirectLaunch(FolderEdits.setMusicPlayer(it, "p1", true), "p1", true) }
        vm.onEvent(HomeEvent.TapPanel(PanelId("p1")))
        assertEquals(listOf(chrome), appsFake.launched)
        play()
        vm.onEvent(HomeEvent.TapPanel(PanelId("p1")))
        assertEquals(listOf("toggle"), media.calls)
        assertEquals(listOf(chrome), appsFake.launched)
    }

    @Test
    fun playerPanelRemovedFromLayoutIsIgnored() {
        useTree()
        folders.update { FolderEdits.setMusicPlayer(it, "gone", true) }
        play()
        assertEquals(null, vm.state.value.player)
    }

    @Test
    fun noPlayerOnVersion1Layout() {
        folders.update { FolderEdits.setMusicPlayer(it, "p1", true) }
        play()
        assertEquals(null, vm.state.value.player)
    }

    @Test
    fun noPlayerWithoutAccess() {
        useTree()
        folders.update { FolderEdits.setMusicPlayer(it, "p1", true) }
        media.hasAccess.value = false
        play()
        assertEquals(null, vm.state.value.player)
    }

    @Test
    fun turningOnWithoutAccessShowsExplanation() {
        useTree()
        media.hasAccess.value = false
        vm.onEvent(HomeEvent.SetMusicPlayer(PanelId("p1"), on = true))
        assertEquals(Overlay.MusicAccess, vm.state.value.overlay)
        assertEquals("p1", vm.state.value.folders.musicPanel())
    }

    @Test
    fun swipeOnFolderPanelDoesNothing() {
        useTree()
        vm.onEvent(HomeEvent.SwipePanel(PanelId("p1"), toLeft = true))
        assertEquals(emptyList(), media.calls)
        assertEquals(Overlay.None, vm.state.value.overlay)
    }
}
