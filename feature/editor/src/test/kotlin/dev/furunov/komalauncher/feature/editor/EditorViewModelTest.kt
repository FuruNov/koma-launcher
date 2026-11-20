package dev.furunov.komalauncher.feature.editor

import dev.furunov.komalauncher.model.geometry.Point
import dev.furunov.komalauncher.model.layout.CutEnd
import dev.furunov.komalauncher.model.layout.Frame
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.LayoutEdits
import dev.furunov.komalauncher.model.layout.LayoutNode
import dev.furunov.komalauncher.model.layout.LayoutShape
import dev.furunov.komalauncher.model.layout.Panel
import dev.furunov.komalauncher.model.layout.PanelArt
import dev.furunov.komalauncher.model.layout.PanelContent
import dev.furunov.komalauncher.model.layout.PanelId
import dev.furunov.komalauncher.model.layout.SplitDirection
import dev.furunov.komalauncher.model.ports.LayoutState
import dev.furunov.komalauncher.model.ports.LayoutStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {
    private class FakeStore(
        layout: Layout,
    ) : LayoutStore {
        val flow = MutableStateFlow<LayoutState>(LayoutState.Loaded(layout, emptyMap()))
        override val state: StateFlow<LayoutState> = flow
        var pruned = 0
        var nextImage: PanelArt? = PanelArt("img-1", 1.5f)

        override fun update(transform: (Layout) -> Layout) {
            flow.value = LayoutState.Loaded(transform(flow.value.layout), emptyMap())
        }

        override suspend fun addImage(
            uri: String,
            panel: PanelId,
        ): PanelArt? = nextImage?.also { a -> update { l -> LayoutEdits.placeImage(l, panel, a) } }

        override suspend fun cutOutPerson(image: String): String? = null

        override fun pruneImages() {
            pruned++
        }
    }

    private val one = Layout(LayoutShape.Tree(LayoutNode.Leaf(Panel(PanelId("p1"), PanelContent.Folder(emptyList()))), Frame(refWidth = 1000f, gutter = 0f, bleed = true)))
    private lateinit var store: FakeStore
    private lateinit var vm: EditorViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        store = FakeStore(one)
        vm = EditorViewModel(store)
        vm.setScreen(1000f, 2000f)
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun splitSelectsNewPanelAndSaves() {
        vm.select(PanelId("p1"))
        vm.split(SplitDirection.HORIZONTAL)
        assertEquals(PanelId("p2"), vm.state.value.selected)
        assertEquals(2, store.flow.value.layout.panels.size)
        assertTrue(vm.state.value.canUndo)
    }

    @Test
    fun undoRestoresAndClearsMissingSelection() {
        vm.select(PanelId("p1"))
        vm.split(SplitDirection.HORIZONTAL)
        vm.undo()
        assertEquals(one, store.flow.value.layout)
        assertEquals(null, vm.state.value.selected)
        assertFalse(vm.state.value.canUndo)
    }

    @Test
    fun dragCountsAsOneUndo() {
        vm.select(PanelId("p1"))
        vm.split(SplitDirection.HORIZONTAL)
        val afterSplit = store.flow.value.layout
        vm.select(PanelId("p2"))
        val path = assertNotNull(vm.selectedCutPath())
        vm.beginGesture()
        vm.moveCutEnd(path, CutEnd.A, Point(1000f, 600f))
        vm.moveCutEnd(path, CutEnd.A, Point(1000f, 700f))
        assertNotEquals(afterSplit, store.flow.value.layout)
        vm.undo()
        assertEquals(afterSplit, store.flow.value.layout)
    }

    @Test
    fun imageIsPlacedAndArtStaysCovering() {
        vm.select(PanelId("p1"))
        vm.setImage("content://x")
        val art = assertNotNull(store.flow.value.layout.panels[0].art)
        assertEquals("img-1", art.image)
        vm.beginGesture()
        // 大きく動かしても、端を見せない範囲で止まる。1000×2000 のコマに縦横比 1.5 の絵は幅 3000 で置かれ、
        // 中心を左右に (3000 - 1000) / 2 = 1000 画素（コマの幅の 1 倍）まで動かせる
        vm.moveArt(art, 100000f, 0f, 1f)
        val moved = assertNotNull(store.flow.value.layout.panels[0].art)
        assertEquals(1f, moved.dx, 1e-4f)
    }

    @Test
    fun unreadableImageShowsMessage() {
        store.nextImage = null
        vm.select(PanelId("p1"))
        vm.setImage("content://broken")
        assertEquals(null, store.flow.value.layout.panels[0].art)
        assertNotNull(vm.state.value.message)
    }

    @Test
    fun mergeRefusedWhenSiblingIsSplit() {
        val hero = LayoutEdits.template(LayoutEdits.Template.HERO, one)
        store.update { hero }
        val vm2 = EditorViewModel(store).also { it.setScreen(1000f, 2000f) }
        vm2.select(hero.panels[0].id)
        vm2.merge()
        assertEquals(hero, store.flow.value.layout)
        assertNotNull(vm2.state.value.message)
    }

    @Test
    fun closePrunesImages() {
        vm.close()
        assertEquals(1, store.pruned)
    }

    @Test
    fun setDimChangesSelectedArt() {
        vm.select(PanelId("p1"))
        vm.setImage("content://x")
        vm.beginGesture()
        vm.setDim(0.3f)
        assertEquals(0.3f, store.flow.value.layout.panels[0].art?.dim)
        vm.undo()
        assertEquals(0f, store.flow.value.layout.panels[0].art?.dim)
    }

    @Test
    fun replacingImageKeepsDim() {
        vm.select(PanelId("p1"))
        vm.setImage("content://x")
        vm.setDim(0.3f)
        store.nextImage = PanelArt("img-2", 1.5f)
        vm.setImage("content://y")
        assertEquals("img-2", store.flow.value.layout.panels[0].art?.image)
        assertEquals(0.3f, store.flow.value.layout.panels[0].art?.dim)
    }
}
