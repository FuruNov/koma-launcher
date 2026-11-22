package dev.furunov.komalauncher.feature.home

import dev.furunov.komalauncher.model.apps.AppCatalog
import dev.furunov.komalauncher.model.apps.AppEntry
import dev.furunov.komalauncher.model.apps.AppKey
import dev.furunov.komalauncher.model.apps.DrawerFolders
import dev.furunov.komalauncher.model.folders.Folder
import dev.furunov.komalauncher.model.folders.FolderEdits
import dev.furunov.komalauncher.model.folders.FolderStore
import dev.furunov.komalauncher.model.folders.Folders
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.LayoutNode
import dev.furunov.komalauncher.model.layout.LayoutShape
import dev.furunov.komalauncher.model.layout.Panel
import dev.furunov.komalauncher.model.layout.PanelContent
import dev.furunov.komalauncher.model.layout.PanelId
import dev.furunov.komalauncher.model.media.MediaSessions
import dev.furunov.komalauncher.model.media.NowPlaying
import dev.furunov.komalauncher.model.media.PlaybackStatus
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

    /** id の頭文字で種類を決める（d: 全アプリ一覧、それ以外: フォルダ）。 */
    private fun layout(vararg ids: String) = Layout.of(
        ids.map {
            val content =
                when {
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
        val unpinned = mutableListOf<AppKey>()
        var unpinSucceeds = true
        val list = MutableStateFlow(listOf(AppEntry(chrome, "Chrome")))
        override val apps: StateFlow<List<AppEntry>> = list

        override fun resolve(packageName: String) = chrome.takeIf { it.packageName == packageName }

        override fun launch(key: AppKey) {
            launched += key
        }

        override fun openAppInfo(key: AppKey) = Unit

        override fun requestUninstall(key: AppKey) = Unit

        override fun shortcuts(key: AppKey) = emptyList<dev.furunov.komalauncher.model.apps.AppShortcut>()

        override fun launchShortcut(shortcut: dev.furunov.komalauncher.model.apps.AppShortcut) = Unit

        override fun allShortcuts(): List<dev.furunov.komalauncher.model.apps.AppShortcut> = emptyList()

        override suspend fun unpin(key: AppKey): Boolean {
            unpinned += key
            return unpinSucceeds
        }
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
    private lateinit var settings: FakeSettings
    private var now = 0L

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        layoutSource = FakeLayout(LayoutState.Loaded(layout("p1", "w1", "d1"), emptyMap()))
        folders = FakeFolders()
        appsFake = FakeApps()
        actions = FakeActions()
        media = FakeMedia()
        settings = FakeSettings()
        now = 0L
        vm = HomeViewModel(layoutSource, folders, settings, appsFake, actions, media) { now }
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
        // 左へ払うと前の曲、右へ払うと次の曲
        assertEquals(listOf("previous", "next"), media.calls)
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

    private val sc = AppKey.shortcut("com.vivaldi.browser", "pwa-1")

    /** 一覧に [sc] を並べ（固定したショートカットとして）、p1 と p2 に入れておく。 */
    private fun withShortcutInTwoFolders() {
        appsFake.list.value = appsFake.list.value + AppEntry(sc, "アイマスイントロ")
        folders.flow.value = Folders(byPanel = mapOf("p1" to Folder(listOf(chrome, sc)), "p2" to Folder(listOf(sc))), seeded = setOf("p1"))
    }

    @Test
    fun removeShortcutAsksBeforeUnpinning() {
        withShortcutInTwoFolders()
        val before = folders.flow.value
        vm.onEvent(HomeEvent.RemoveShortcut(sc))
        assertEquals(Overlay.ConfirmRemove(sc), vm.state.value.overlay)
        assertEquals(emptyList(), appsFake.unpinned)
        assertEquals(before, folders.flow.value)
    }

    @Test
    fun confirmRemoveUnpinsAndRemovesFromAllFolders() {
        withShortcutInTwoFolders()
        vm.onEvent(HomeEvent.RemoveShortcut(sc))
        vm.onEvent(HomeEvent.ConfirmRemoveShortcut(sc))
        assertEquals(listOf(sc), appsFake.unpinned)
        assertEquals(listOf(chrome), folders.flow.value.appsOf("p1"))
        assertEquals(emptyList(), folders.flow.value.appsOf("p2"))
        assertEquals(Overlay.None, vm.state.value.overlay)
    }

    @Test
    fun hideFromDrawerMenuHidesAndReturnsToDrawer() {
        vm.onEvent(HomeEvent.ShowAppMenu(chrome, fromPanel = null, fromDrawer = true))
        vm.onEvent(HomeEvent.Hide(chrome))
        assertEquals(setOf(chrome), settings.flow.value.drawer.hidden)
        assertEquals(Overlay.Drawer, vm.state.value.overlay)
    }

    @Test
    fun dismissDrawerMenuReturnsToDrawer() {
        vm.onEvent(HomeEvent.ShowAppMenu(chrome, fromPanel = null, fromDrawer = true))
        vm.onEvent(HomeEvent.Dismiss)
        assertEquals(Overlay.Drawer, vm.state.value.overlay)
    }

    @Test
    fun drawerFolderMenuMarksCurrentFolder() {
        vm.onEvent(HomeEvent.ShowDrawerFolders(chrome))
        val menu = vm.state.value.overlay as Overlay.DrawerFolderMenu
        assertEquals("other", menu.current)
        assertEquals(DrawerFolders.builtIn, menu.choices)
    }

    @Test
    fun moveToNewDrawerFolderCreatesItAndReturnsToDrawer() {
        vm.onEvent(HomeEvent.ShowDrawerFolders(chrome))
        vm.onEvent(HomeEvent.OpenOverlay(Overlay.DrawerFolderName(app = chrome, folderId = null, current = "")))
        vm.onEvent(HomeEvent.NameDrawerFolder(app = chrome, folderId = null, name = "ウェブ"))
        val s = settings.flow.value.drawer
        assertEquals(listOf("ウェブ"), s.folderNames.values.toList())
        assertEquals(s.folderNames.keys.single(), s.moved.single { it.app == chrome }.folder)
        assertEquals(Overlay.Drawer, vm.state.value.overlay)
    }

    @Test
    fun renameDrawerFolderFromTab() {
        vm.onEvent(HomeEvent.EditDrawerFolderName("game"))
        assertEquals(Overlay.DrawerFolderName(app = null, folderId = "game", current = "ゲーム"), vm.state.value.overlay)
        vm.onEvent(HomeEvent.NameDrawerFolder(app = null, folderId = "game", name = "遊び"))
        assertEquals(mapOf("game" to "遊び"), settings.flow.value.drawer.folderNames)
        assertEquals(Overlay.Drawer, vm.state.value.overlay)
    }

    @Test
    fun drawerMenusKeepDrawerOpen() {
        vm.onEvent(HomeEvent.ShowAppMenu(chrome, fromPanel = null, fromDrawer = true))
        assert(vm.state.value.overlay.overDrawer)
        vm.onEvent(HomeEvent.ShowDrawerFolders(chrome))
        assert(vm.state.value.overlay.overDrawer)
        vm.onEvent(HomeEvent.ShowAppMenu(chrome, fromPanel = "p1"))
        assert(!vm.state.value.overlay.overDrawer)
    }

    @Test
    fun removingShortcutFromDrawerReturnsToDrawer() {
        withShortcutInTwoFolders()
        vm.onEvent(HomeEvent.ShowAppMenu(sc, fromPanel = null, fromDrawer = true))
        vm.onEvent(HomeEvent.RemoveShortcut(sc))
        assert(vm.state.value.overlay.overDrawer)
        vm.onEvent(HomeEvent.ConfirmRemoveShortcut(sc))
        assertEquals(Overlay.Drawer, vm.state.value.overlay)
    }

    @Test
    fun startingToCarryHidesMenuAndKeepsWhatWasUnder() {
        vm.onEvent(HomeEvent.OpenOverlay(Overlay.Folder(PanelId("p1"))))
        vm.onEvent(HomeEvent.ShowAppMenu(chrome, fromPanel = "p1"))
        assertEquals(Overlay.Folder(PanelId("p1")), (vm.state.value.overlay as Overlay.AppMenu).under)
        vm.onEvent(HomeEvent.HideAppMenu)
        assertEquals(Overlay.Folder(PanelId("p1")), vm.state.value.overlay)
    }

    @Test
    fun dismissMenuOverFolderReturnsToFolder() {
        vm.onEvent(HomeEvent.OpenOverlay(Overlay.Folder(PanelId("p1"))))
        vm.onEvent(HomeEvent.ShowAppMenu(chrome, fromPanel = "p1"))
        vm.onEvent(HomeEvent.Dismiss)
        assertEquals(Overlay.Folder(PanelId("p1")), vm.state.value.overlay)
    }

    @Test
    fun linkedPanelShowsDrawerFolderAndRemovingMovesToOther() {
        folders.flow.value = Folders(byPanel = mapOf("p1" to Folder(emptyList())), seeded = setOf("p1"))
        vm.onEvent(HomeEvent.LinkPanel(PanelId("p1"), "other"))
        // chrome は分類が無いので、全アプリ一覧では「その他」に入る
        assertEquals(listOf(chrome), vm.state.value.folders.appsOf("p1"))
        assertEquals(emptyList(), folders.flow.value.appsOf("p1"))
        vm.onEvent(HomeEvent.ShowLinkPanel(PanelId("p1")))
        assertEquals("other", (vm.state.value.overlay as Overlay.LinkPanel).current)
    }

    @Test
    fun appListRemapsOldEntryAndRemembersNames() {
        val old = AppKey("com.android.chrome", "OldMain")
        folders.flow.value = Folders(byPanel = mapOf("p1" to Folder(listOf(old))), seeded = setOf("p1"))
        // 一覧が更新されたときに読み替える（機種変更で入口の名前だけが違う同じアプリ）。初期値と同じ値では更新が届かないので、別の値にする
        appsFake.list.value = listOf(AppEntry(chrome, "Chrome"), AppEntry(AppKey("com.example.other", "Main"), "Other"))
        assertEquals(listOf(chrome), folders.flow.value.appsOf("p1"))
        assertEquals(mapOf("com.android.chrome" to "Chrome"), settings.flow.value.appNames)
    }

    @Test
    fun dismissFolderMenuReturnsHome() {
        vm.onEvent(HomeEvent.ShowAppMenu(chrome, fromPanel = "p1"))
        vm.onEvent(HomeEvent.Dismiss)
        assertEquals(Overlay.None, vm.state.value.overlay)
    }

    @Test
    fun cancelRemoveKeepsEverything() {
        withShortcutInTwoFolders()
        val before = folders.flow.value
        vm.onEvent(HomeEvent.RemoveShortcut(sc))
        vm.onEvent(HomeEvent.Dismiss)
        assertEquals(emptyList(), appsFake.unpinned)
        assertEquals(before, folders.flow.value)
        assertEquals(Overlay.None, vm.state.value.overlay)
    }

    @Test
    fun dropIndexSkipsHiddenShortcuts() {
        val h1 = AppKey.shortcut("v", "h1")
        val h2 = AppKey.shortcut("v", "h2")
        val b = AppKey("b", "B")
        val x = AppKey("x", "X")
        appsFake.list.value = listOf(AppEntry(chrome, "Chrome"), AppEntry(b, "B"), AppEntry(x, "X"))
        folders.flow.value = Folders(byPanel = mapOf("p1" to Folder(listOf(h1, h2, chrome, b))), seeded = setOf("p1"))
        vm.onEvent(HomeEvent.Drop(x, fromPanel = null, target = DropTarget.Panel("p1", index = 1)))
        assertEquals(listOf(chrome, x, b), vm.state.value.shownAppsOf("p1"))
    }

    @Test
    fun shownAppsHideShortcutMissingFromList() {
        folders.flow.value = Folders(byPanel = mapOf("p1" to Folder(listOf(sc, chrome))), seeded = setOf("p1"))
        assertEquals(listOf(chrome), vm.state.value.shownAppsOf("p1"))
    }

    @Test
    fun directLaunchSkipsHiddenShortcut() {
        folders.flow.value = Folders(byPanel = mapOf("p1" to Folder(listOf(sc, chrome), directLaunch = true)), seeded = setOf("p1"))
        vm.onEvent(HomeEvent.TapPanel(PanelId("p1")))
        assertEquals(listOf(chrome), appsFake.launched)
    }

    @Test
    fun confirmRemoveAlsoClearsUnassigned() {
        withShortcutInTwoFolders()
        folders.flow.value = folders.flow.value.copy(unassigned = listOf(sc))
        vm.onEvent(HomeEvent.ConfirmRemoveShortcut(sc))
        assertEquals(emptyList(), folders.flow.value.unassigned)
    }

    @Test
    fun failedUnpinKeepsFoldersAndTellsUser() {
        withShortcutInTwoFolders()
        val before = folders.flow.value
        appsFake.unpinSucceeds = false
        vm.onEvent(HomeEvent.ConfirmRemoveShortcut(sc))
        assertEquals(before, folders.flow.value)
        assertEquals(Overlay.ConfirmRemove(sc, failed = true), vm.state.value.overlay)
    }
}
