package dev.furunov.komalauncher.feature.editor

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.furunov.komalauncher.model.geometry.Point
import dev.furunov.komalauncher.model.layout.ArtLimits
import dev.furunov.komalauncher.model.layout.CutEnd
import dev.furunov.komalauncher.model.layout.EditHistory
import dev.furunov.komalauncher.model.layout.Frame
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.LayoutEdits
import dev.furunov.komalauncher.model.layout.LayoutResolver
import dev.furunov.komalauncher.model.layout.LayoutShape
import dev.furunov.komalauncher.model.layout.Look
import dev.furunov.komalauncher.model.layout.PanelArt
import dev.furunov.komalauncher.model.layout.PanelContent
import dev.furunov.komalauncher.model.layout.PanelId
import dev.furunov.komalauncher.model.layout.PanelText
import dev.furunov.komalauncher.model.layout.ResolvedLayout
import dev.furunov.komalauncher.model.layout.ResolvedPanel
import dev.furunov.komalauncher.model.layout.SplitDirection
import dev.furunov.komalauncher.model.layout.TreeGeometry
import dev.furunov.komalauncher.model.ports.LayoutState
import dev.furunov.komalauncher.model.ports.LayoutStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class EditorUiState(
    val layout: Layout,
    /** 画像の id → ファイルの絶対パス。 */
    val images: Map<String, String> = emptyMap(),
    val imagesRevision: Long = 0L,
    val masks: Map<String, String> = emptyMap(),
    val selected: PanelId? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** 元に戻す・やり直すをした回数。入力欄が手元に持つ文字を、戻した内容で読み直す合図にする。 */
    val restored: Int = 0,
    /** 画像をインポートしている間は true。 */
    val busy: Boolean = false,
    /** 画面の下に出す短い知らせ。 */
    val message: String? = null,
) {
    /** version 1 のレイアウト（木を持たない）は、型を選ぶまで編集できない。 */
    val editable: Boolean get() = layout.shape is LayoutShape.Tree
}

/**
 * 編集画面の状態と操作。操作の中身は [LayoutEdits]（:core:model の純粋な関数）にあり、ここでは
 * 元に戻す履歴の記録と、保存（[LayoutStore.update]）だけをする。
 *
 * 編集の途中のレイアウトはここで持つ。LayoutStore の状態は保存と画像の確認を経て遅れて届くので、
 * ドラッグの途中で読むと 1 つ前の値になるため。
 */
class EditorViewModel(
    private val store: LayoutStore,
) : ViewModel() {
    private val history = EditHistory<Layout>()
    private val local =
        MutableStateFlow(
            EditorUiState(
                layout = store.state.value.layout,
                message = if (store.state.value.layout.shape is LayoutShape.Fixed) "旧形式のレイアウトです。テンプレートを選ぶと編集を開始できます" else null,
            ),
        )

    val state: StateFlow<EditorUiState> =
        combine(local, store.state) { s, l ->
            s.copy(images = (l as? LayoutState.Loaded)?.images.orEmpty(), imagesRevision = (l as? LayoutState.Loaded)?.revision ?: 0L, masks = (l as? LayoutState.Loaded)?.masks.orEmpty())
        }.stateIn(viewModelScope, SharingStarted.Eagerly, local.value)

    /** 1 度でも保存したか。 */
    private var committed = false

    init {
        // まだ何も保存していない間は、保存側の変更（インポートの完了など）に合わせる。1 度でも保存したら手元の状態を正とする
        // （保存側の状態は遅れて届くので、保存の後に合わせると、届いた古い値で手元の変更を戻してしまう）
        viewModelScope.launch {
            store.state.collect { l -> if (!committed && l.layout != local.value.layout) local.update { it.copy(layout = l.layout) } }
        }
    }

    /** 画面の大きさ（画素）。割る線の位置と、絵を動かせる範囲を決めるのに使う。ホームと同じ大きさを渡す。 */
    private var width = 1f
    private var height = 1f

    fun setScreen(
        w: Float,
        h: Float,
    ) {
        width = w
        height = h
    }

    /** 視差で絵を動かす最大の量（画素）。ホームと同じく、その分だけ絵を大きく置き、動かせる範囲を狭める。 */
    private fun parallax(layout: Layout): Float = (layout.shape as? LayoutShape.Tree)?.let { it.fx.artShift() * width / it.frame.refWidth } ?: 0f

    fun resolve(layout: Layout = local.value.layout): ResolvedLayout = LayoutResolver.resolve(layout, width, height, parallax(layout))

    private fun resolvedPanel(id: PanelId): ResolvedPanel? = resolve().panels.firstOrNull { it.panel.id == id }

    fun select(id: PanelId?) = local.update { it.copy(selected = id, message = null) }

    /** ドラッグやスライダーの始まり。続く変更をまとめて 1 回の「元に戻す」にする。 */
    fun beginGesture() = record()

    fun applyTemplate(t: LayoutEdits.Template) = edit { LayoutEdits.template(t, it) }.also { local.update { it.copy(selected = null, message = null) } }

    fun split(dir: SplitDirection) {
        val id = local.value.selected ?: return
        val poly = resolvedPanel(id)?.polygon?.points ?: return
        val (after, fresh) = LayoutEdits.split(local.value.layout, id, dir, poly) ?: return
        record()
        commit(after)
        local.update { it.copy(selected = fresh) }
    }

    fun merge() {
        val id = local.value.selected ?: return
        val after = LayoutEdits.merge(local.value.layout, id)
        if (after === local.value.layout) {
            local.update { it.copy(message = "隣のコマが分割されているため結合できません。先に隣のコマを結合してください") }
            return
        }
        record()
        commit(after)
    }

    fun setKind(target: PanelContent) {
        val id = local.value.selected ?: return
        edit { LayoutEdits.setKind(it, id, target) }
    }

    /** 選んでいるコマを割っている線の道筋。根のコマ（線が無い）なら null。 */
    fun selectedCutPath(): String? = local.value.selected?.let { LayoutEdits.parentCutPath(local.value.layout, it) }

    /** 線の端を、画面の点 [p] に最も近い親の縁の上へ動かす。ドラッグの間に呼ぶ（始まりで [beginGesture]）。 */
    fun moveCutEnd(
        path: String,
        end: CutEnd,
        p: Point,
    ) {
        val cut = resolve().cuts.firstOrNull { it.path == path } ?: return
        commit(LayoutEdits.moveCut(local.value.layout, path, end, TreeGeometry.paramOf(cut.parent, p)))
    }

    /** 選んでいるコマの絵を、[start] から画面上で ([dx], [dy]) 画素動かし、拡大率を [scale] 倍にする。端を見せない範囲に収める。 */
    fun moveArt(
        start: PanelArt,
        dx: Float,
        dy: Float,
        scale: Float,
    ) {
        val id = local.value.selected ?: return
        val b = resolvedPanel(id)?.polygon?.bounds ?: return
        val moved = start.copy(dx = start.dx + dx / b.width, dy = start.dy + dy / b.height, zoom = start.zoom * scale)
        commit(LayoutEdits.updatePanel(local.value.layout, id) { it.copy(art = ArtLimits.clamp(b, moved, parallax(local.value.layout))) })
    }

    fun selectedArt(): PanelArt? = local.value.selected?.let { local.value.layout.panel(it) }?.art

    /** 利用者が選んだ画像をインポート、選んでいるコマに置く。 */
    fun setImage(uri: String) {
        val id = local.value.selected ?: return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            val before = local.value.layout
            // 保存側が画像をコピーしてコマに置くところまでを済ませる。ここでは同じ変更を手元の状態に反映し、元に戻せるようにする
            val art = store.addImage(uri, id)
            local.update { it.copy(busy = false, message = if (art == null) "画像を読み込めませんでした" else null) }
            if (art != null) {
                committed = true
                history.push(before)
                local.update { s -> s.copy(layout = LayoutEdits.placeImage(s.layout, id, art), canUndo = true, canRedo = false) }
            }
        }
    }

    /** 選んでいるコマの絵の暗さ（0〜1）。スライダーから呼ぶ。スライダーの変更は「元に戻す」の履歴に積まない。 */
    fun setDim(value: Float) {
        val id = local.value.selected ?: return
        commit(LayoutEdits.setDim(local.value.layout, id, value))
    }

    /** 選んでいるコマの人物を、枠の外へはみ出させるか（立体感の E）。 */
    fun setPop(value: Boolean) {
        val id = local.value.selected ?: return
        edit { l -> LayoutEdits.updatePanel(l, id) { it.copy(pop = value) } }
    }

    fun removeImage() {
        val id = local.value.selected ?: return
        edit { l -> LayoutEdits.updatePanel(l, id) { it.copy(art = null) } }
    }

    /** フチとコマの間の変更。スライダーからは [record] を false にして呼び、履歴に積まない。 */
    fun setFrame(
        record: Boolean = true,
        transform: (Frame) -> Frame,
    ) {
        if (record) record()
        commit(LayoutEdits.setFrame(local.value.layout, transform))
    }

    /** 見た目の変更。使い方は [setFrame] と同じ。 */
    fun setLook(
        record: Boolean = true,
        transform: (Look) -> Look,
    ) {
        if (record) record()
        commit(LayoutEdits.setLook(local.value.layout, transform))
    }

    /** 文字と時計の変更。使い方は [setFrame] と同じ。 */
    fun setTree(
        record: Boolean = true,
        transform: (LayoutShape.Tree) -> LayoutShape.Tree,
    ) {
        if (record) record()
        commit(LayoutEdits.setTree(local.value.layout, transform))
    }

    /** 選んでいるコマの名前と英語の名前。入力の 1 文字ごとに呼ぶので、履歴に積まない（大見出しと同じ）。 */
    fun setLabels(
        label: String,
        labelEn: String,
    ) {
        val id = local.value.selected ?: return
        commit(LayoutEdits.updatePanel(local.value.layout, id) { it.copy(label = label.takeIf { s -> s.isNotBlank() }, labelEn = labelEn.takeIf { s -> s.isNotBlank() }) })
    }

    /** コマ [id] のアイコンの位置と大きさを自動に戻す（ホームの「配置を調整」で決めたもの）。 */
    fun resetIcons(id: PanelId) = edit { l -> LayoutEdits.updatePanel(l, id) { it.copy(iconAt = null, iconScale = 1f) } }

    fun undo() = restore(history.undo(local.value.layout))

    fun redo() = restore(history.redo(local.value.layout))

    private fun restore(layout: Layout?) {
        if (layout == null) return
        local.update { s -> s.copy(selected = s.selected?.takeIf { layout.panel(it) != null }, restored = s.restored + 1) }
        commit(layout, fromHistory = true)
    }

    fun dismissMessage() = local.update { it.copy(message = null) }

    /** 編集画面を閉じる。どのコマも使っていない画像を消す（履歴が無くなるので、もう戻せない）。 */
    fun close() {
        history.clear()
        store.pruneImages()
    }

    private fun edit(transform: (Layout) -> Layout) {
        val after = transform(local.value.layout)
        if (after == local.value.layout) return
        record()
        commit(after)
    }

    private fun record() {
        history.push(local.value.layout)
        local.update { it.copy(canUndo = true, canRedo = false) }
    }

    /** [fromHistory] は元に戻す・やり直すによる変更。それ以外の変更をしたら、やり直す先は無くなる。 */
    private fun commit(
        layout: Layout,
        fromHistory: Boolean = false,
    ) {
        committed = true
        if (!fromHistory) history.dropRedo()
        local.update { it.copy(layout = layout, canUndo = history.canUndo, canRedo = history.canRedo) }
        store.update { layout }
    }
}
