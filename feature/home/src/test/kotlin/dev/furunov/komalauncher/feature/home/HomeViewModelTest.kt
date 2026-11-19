package dev.furunov.komalauncher.feature.home

import dev.furunov.komalauncher.model.apps.AppCatalog
import dev.furunov.komalauncher.model.apps.AppEntry
import dev.furunov.komalauncher.model.apps.AppKey
import dev.furunov.komalauncher.model.folders.FolderEdits
import dev.furunov.komalauncher.model.folders.FolderStore
import dev.furunov.komalauncher.model.folders.Folders
import dev.furunov.komalauncher.model.geometry.Point
import dev.furunov.komalauncher.model.geometry.Polygon
import dev.furunov.komalauncher.model.layout.Layout
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
    private val tri = Polygon(listOf(Point(0f, 0f), Point(1f, 0f), Point(1f, 1f)))
    private val chrome = AppKey("com.android.chrome", "Main")

    private fun layout(vararg ids: String) = Layout(1, null, 1f, ids.map { Panel(PanelId(it), tri, if (it.startsWith("w")) PanelContent.Widget else PanelContent.Folder(listOf("com.android.chrome"))) })

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

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        layoutSource = FakeLayout(LayoutState.Loaded(layout("p1", "w1"), null))
        folders = FakeFolders()
        appsFake = FakeApps()
        actions = FakeActions()
        vm = HomeViewModel(layoutSource, folders, FakeSettings(), appsFake, actions)
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
}
