package dev.furunov.komalauncher.model.layout

import dev.furunov.komalauncher.model.geometry.Point

/** 割る向き。横（右 → 左の線）と縦（上 → 下の線）。 */
enum class SplitDirection { HORIZONTAL, VERTICAL }

/** 線の 2 つの端。 */
enum class CutEnd { A, B }

/**
 * 編集画面の操作（純粋な関数）。式と振る舞いはモックアップ（split・merge・線の端のドラッグ・型）に合わせる。
 * version 1 のレイアウトは木を持たないので、木を変える操作は何もしない（編集画面は型から始め直す）。
 */
object LayoutEdits {
    /**
     * [panelId] のコマを、外接長方形の中心を通る線で 2 つに割る。[polygon] はそのコマの画面上の形（[LayoutResolver] の結果）。
     * 元のコマの中身（id・フォルダ・画像）は a の子（上または左）が引き継ぎ、b の子は空のフォルダにする。
     * 新しいコマの id を返す（割れなければ null）。
     */
    fun split(
        layout: Layout,
        panelId: PanelId,
        dir: SplitDirection,
        polygon: List<Point>,
    ): Pair<Layout, PanelId>? {
        val shape = layout.shape as? LayoutShape.Tree ?: return null
        if (polygon.size < 3) return null
        val path = pathOf(shape.root, panelId) ?: return null
        val leaf = nodeAt(shape.root, path) as? LayoutNode.Leaf ?: return null
        val l = polygon.minOf { it.x }
        val r = polygon.maxOf { it.x }
        val t = polygon.minOf { it.y }
        val b = polygon.maxOf { it.y }
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        // 横は右 → 左、縦は上 → 下の向きに引く。線の左側（a）が上または左のコマになる
        val (from, to) = if (dir == SplitDirection.HORIZONTAL) Point(r, cy) to Point(l, cy) else Point(cx, t) to Point(cx, b)
        // 名前は付けない。役割を決めるのは利用者なので、名前の無いコマには文字を表示しない
        val fresh = Panel(nextId(layout), PanelContent.Folder(emptyList()))
        val split = LayoutNode.Split(TreeGeometry.paramOf(polygon, from), TreeGeometry.paramOf(polygon, to), leaf, LayoutNode.Leaf(fresh))
        return Layout(shape.copy(root = replaceAt(shape.root, path, split))) to fresh.id
    }

    /** [panelId] のコマを残し、同じ線で割られた隣のコマを取り除く。隣が枝（さらに割られている）なら何もしない。 */
    fun merge(
        layout: Layout,
        panelId: PanelId,
    ): Layout {
        val shape = layout.shape as? LayoutShape.Tree ?: return layout
        val path = pathOf(shape.root, panelId) ?: return layout
        if (path.isEmpty()) return layout
        val parentPath = path.dropLast(1)
        val parent = nodeAt(shape.root, parentPath) as? LayoutNode.Split ?: return layout
        val self = if (path.last() == 'a') parent.a else parent.b
        val sibling = if (path.last() == 'a') parent.b else parent.a
        if (sibling !is LayoutNode.Leaf) return layout
        return Layout(shape.copy(root = replaceAt(shape.root, parentPath, self)))
    }

    /** [panelId] のコマを割っている線（親の線）の道筋。根のコマなら null。 */
    fun parentCutPath(
        layout: Layout,
        panelId: PanelId,
    ): String? {
        val shape = layout.shape as? LayoutShape.Tree ?: return null
        val path = pathOf(shape.root, panelId) ?: return null
        return if (path.isEmpty()) null else path.dropLast(1)
    }

    /** 道筋 [path] の線の端 [end] を、親のコマの縁の上の位置 [t]（周に対する割合）へ動かす。 */
    fun moveCut(
        layout: Layout,
        path: String,
        end: CutEnd,
        t: Float,
    ): Layout {
        val shape = layout.shape as? LayoutShape.Tree ?: return layout
        val node = nodeAt(shape.root, path) as? LayoutNode.Split ?: return layout
        val v = ((t % 1f) + 1f) % 1f
        val moved = if (end == CutEnd.A) node.copy(ta = v) else node.copy(tb = v)
        return Layout(shape.copy(root = replaceAt(shape.root, path, moved)))
    }

    fun updatePanel(
        layout: Layout,
        panelId: PanelId,
        transform: (Panel) -> Panel,
    ): Layout = layout.mapPanels { if (it.id == panelId) transform(it) else it }

    /** コマの画像を [art] に替える。暗さはコマの読みやすさの設定なので、前の画像のものを引き継ぐ。 */
    fun placeImage(
        layout: Layout,
        panelId: PanelId,
        art: PanelArt,
    ): Layout = updatePanel(layout, panelId) { it.copy(art = art.copy(dim = it.art?.dim ?: art.dim)) }

    /** コマの絵の暗さを変える（0〜1 に収める）。絵の無いコマでは何もしない。 */
    fun setDim(
        layout: Layout,
        panelId: PanelId,
        dim: Float,
    ): Layout = updatePanel(layout, panelId) { p -> p.copy(art = p.art?.copy(dim = dim.coerceIn(0f, 1f))) }

    /**
     * 種類を [target] にする。今と同じ種類なら何も変えない（フォルダの初期値などを消さないため）。
     * 編集画面は種類を全て並べて選ばせる。押すたびに切り替える形では、どんな種類があるのか前もって分からなかった（2026-11-22）。
     */
    fun setKind(
        layout: Layout,
        panelId: PanelId,
        target: PanelContent,
    ): Layout = updatePanel(layout, panelId) { p -> if (p.content::class == target::class) p else p.copy(content = target) }

    /** 種類を フォルダ → アプリドロワー の順に切り替える。 */
    fun cycleKind(
        layout: Layout,
        panelId: PanelId,
    ): Layout = updatePanel(layout, panelId) { p ->
        val next =
            when (p.content) {
                is PanelContent.Folder -> PanelContent.Drawer
                PanelContent.Drawer -> PanelContent.Folder(emptyList())
            }
        p.copy(content = next)
    }

    fun setFrame(
        layout: Layout,
        transform: (Frame) -> Frame,
    ): Layout {
        val shape = layout.shape as? LayoutShape.Tree ?: return layout
        return Layout(shape.copy(frame = transform(shape.frame)))
    }

    fun setLook(
        layout: Layout,
        transform: (Look) -> Look,
    ): Layout {
        val shape = layout.shape as? LayoutShape.Tree ?: return layout
        return Layout(shape.copy(look = transform(shape.look)))
    }

    /** 文字・時計など、木以外の項目の変更。 */
    fun setTree(
        layout: Layout,
        transform: (LayoutShape.Tree) -> LayoutShape.Tree,
    ): Layout {
        val shape = layout.shape as? LayoutShape.Tree ?: return layout
        return Layout(transform(shape))
    }

    /** 型。利用者が 11/20 に残すと決めたのは大ゴマと 1 コマ。 */
    enum class Template(
        val label: String,
    ) {
        HERO("大ゴマ"),
        ONE("1 コマ"),
    }

    /**
     * 型から木を作る。id は [base] の id と重ならない番号から振る（フォルダの中身が別のコマへ移らないように）。
     * 大ゴマの線の位置は、利用者がモックアップで作ったコマ割り（db の editor/current）と同じ。
     */
    fun template(
        template: Template,
        base: Layout,
    ): Layout {
        var n = maxNumber(base)
        fun leaf(content: PanelContent = PanelContent.Folder(emptyList())) = LayoutNode.Leaf(Panel(PanelId("p${++n}"), content))
        val root =
            when (template) {
                Template.ONE -> leaf()

                Template.HERO ->
                    LayoutNode.Split(
                        0.3246f,
                        0.7999f,
                        leaf(),
                        LayoutNode.Split(
                            0.1477f,
                            0.6503f,
                            LayoutNode.Split(0.9004f, 0.3905f, leaf(), leaf()),
                            LayoutNode.Split(0.8774f, 0.3196f, leaf(), leaf(PanelContent.Drawer)),
                        ),
                    )
            }
        // フチ・見た目・文字・時計は今のものを引き継ぐ（型を選び直しても、合わせた見た目が戻らないように）。
        // 時計と大見出しのコマは id が変わるので、いちばん大きいコマに置き直す
        val old = base.shape as? LayoutShape.Tree ?: return Layout(LayoutShape.Tree(root))
        return Layout(old.copy(root = root, clock = old.clock.copy(panel = null), text = old.text.copy(mastheadPanel = null)))
    }

    /** `p` + 番号の id のうち最大の番号 + 1。モックアップの採番と同じ形にする。 */
    fun nextId(layout: Layout): PanelId = PanelId("p${maxNumber(layout) + 1}")

    private fun maxNumber(layout: Layout): Int = layout.panels.mapNotNull { ID.matchEntire(it.id.value)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0

    private val ID = Regex("p(\\d+)")

    /** 根から [panelId] の葉までの道筋（"a"・"b" の並び）。 */
    fun pathOf(
        root: LayoutNode,
        panelId: PanelId,
    ): String? = when (root) {
        is LayoutNode.Leaf -> if (root.panel.id == panelId) "" else null
        is LayoutNode.Split -> pathOf(root.a, panelId)?.let { "a$it" } ?: pathOf(root.b, panelId)?.let { "b$it" }
    }

    fun nodeAt(
        root: LayoutNode,
        path: String,
    ): LayoutNode? {
        var node = root
        for (c in path) {
            node = (node as? LayoutNode.Split)?.let { if (c == 'a') it.a else it.b } ?: return null
        }
        return node
    }

    private fun replaceAt(
        root: LayoutNode,
        path: String,
        replacement: LayoutNode,
    ): LayoutNode {
        if (path.isEmpty()) return replacement
        val split = root as? LayoutNode.Split ?: return root
        val rest = path.substring(1)
        return if (path[0] == 'a') split.copy(a = replaceAt(split.a, rest, replacement)) else split.copy(b = replaceAt(split.b, rest, replacement))
    }
}

/**
 * 元に戻す・やり直すための履歴。モックアップと同じく、元に戻す側は 50 件まで持つ。
 * 新しい操作を積むと（[push]・[dropRedo]）、やり直す側は空にする。
 */
class EditHistory<T>(
    private val limit: Int = 50,
) {
    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** 操作の前の状態を積む。 */
    fun push(state: T) {
        undoStack.addLast(state)
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    /** 1 つ前の状態を返し、今の状態 [current] をやり直す側に積む。 */
    fun undo(current: T): T? {
        val prev = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        return prev
    }

    /** 元に戻した状態を返し、今の状態 [current] を元に戻す側に積む。 */
    fun redo(current: T): T? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        return next
    }

    /** 履歴に積まない変更（スライダーなど）をしたとき。元に戻した先で変えたので、やり直す先はもう無い。 */
    fun dropRedo() = redoStack.clear()

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
