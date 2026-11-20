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
    /** 画像を取り込んでいる間は true。 */
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
        // まだ何も保存していない間は、保存側の変更（取り込みの完了など）に合わせる。1 度でも保存したら手元の状態を正とする
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

    fun cycleKind() {
        val id = local.value.selected ?: return
        edit { LayoutEdits.cycleKind(it, id) }
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

    /** 利用者が選んだ画像を取り込み、選んでいるコマに置く。 */
    fun setImage(uri: String) {
        val id = local.value.selected ?: return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            val before = local.value.layout
            // 保存側が画像を写してコマに置くところまでを済ませる。ここでは同じ変更を手元の状態に写し、元に戻せるようにする
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

    /** 画面の点 [p] にある、指で動かせる文字（時計か大見出し）。時計が上に重なるので先に調べる。 */
    fun textAt(p: Point): MovableText? {
        val tree = local.value.layout.shape as? LayoutShape.Tree ?: return null
        val panels = resolve().panels
        if (tree.clock.enabled) {
            val c = textCenter(MovableText.CLOCK) ?: return null
            val k = tree.clock.size * width / tree.frame.refWidth
            if (kotlin.math.abs(p.x - c.x) < 450f * k && p.y - c.y in -240f * k..170f * k) return MovableText.CLOCK
        }
        val id = PanelText.mastheadPanel(panels, tree.text, tree.clock) ?: return null
        val b = panels.firstOrNull { it.panel.id == id }?.polygon?.bounds ?: return null
        val c = textCenter(MovableText.MASTHEAD) ?: return null
        val half = PanelText.mastheadSize(b) * 0.6f
        return if (kotlin.math.abs(p.x - c.x) < b.width * 0.45f && kotlin.math.abs(p.y - c.y) < half) MovableText.MASTHEAD else null
    }

    /** 文字の中心（画面の座標）。 */
    fun textCenter(t: MovableText): Point? {
        val tree = local.value.layout.shape as? LayoutShape.Tree ?: return null
        val panels = resolve().panels
        return when (t) {
            MovableText.CLOCK -> {
                val b = panels.firstOrNull { it.panel.id == PanelText.clockPanel(panels, tree.clock) }?.polygon?.bounds ?: return null
                Point(b.left + tree.clock.x * b.width, b.top + tree.clock.y * b.height)
            }

            MovableText.MASTHEAD -> {
                val b = panels.firstOrNull { it.panel.id == PanelText.mastheadPanel(panels, tree.text, tree.clock) }?.polygon?.bounds ?: return null
                Point(b.left + b.width / 2f, b.top + tree.text.mastheadY * b.height)
            }
        }
    }

    /**
     * 文字の中心を画面の点 [center] へ動かす。置くコマは [center] の下のコマで、位置はそのコマの中の割合で持つ
     * （人物の後ろに回す処理がコマごとなので）。大見出しは横の中央に置くので、縦の位置だけを変える。
     * ドラッグの間に呼ぶ（始まりで [beginGesture]）。
     */
    fun moveText(
        t: MovableText,
        center: Point,
    ) {
        val panels = resolve().panels
        val hit = panels.lastOrNull { it.polygon.bounds.contains(center) && it.polygon.contains(center) } ?: return
        // 置き場所を決めていない大見出しは時計のコマに付いていくので、時計を動かす前に今のコマへ固定する
        val tree = local.value.layout.shape as? LayoutShape.Tree ?: return
        val mastheadId = tree.text.mastheadPanel ?: PanelText.mastheadPanel(panels, tree.text, tree.clock)
        val b = hit.polygon.bounds
        val x = ((center.x - b.left) / b.width).coerceIn(0f, 1f)
        val y = ((center.y - b.top) / b.height).coerceIn(0f, 1f)
        commit(
            LayoutEdits.setTree(local.value.layout) {
                when (t) {
                    MovableText.CLOCK -> it.copy(clock = it.clock.copy(panel = hit.panel.id, x = x, y = y), text = it.text.copy(mastheadPanel = mastheadId))
                    MovableText.MASTHEAD -> it.copy(text = it.text.copy(mastheadPanel = hit.panel.id, mastheadY = y))
                }
            },
        )
    }

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

/** 縮小図の上で指で動かせる文字。 */
enum class MovableText { CLOCK, MASTHEAD }
