package dev.furunov.komalauncher.model.layout

import dev.furunov.komalauncher.model.geometry.Point
import kotlin.math.abs

/** ホームの「配置を調整」で指で扱うコンポーネント。1 本指で動かし（名前は動かさない）、2 本指でそのコンポーネントだけ拡大縮小する。 */
sealed interface Part {
    /** コマ [panel] のアイコンのまとまり。[center] は掴んだときのまとまりの中心（画面の座標）。 */
    data class Icons(
        val panel: PanelId,
        val center: Point,
    ) : Part

    /** 時計。[center] は掴んだときの中心（画面の座標）。 */
    data class Clock(
        val center: Point,
    ) : Part

    /** 大見出し。[center] は掴んだときの中心（画面の座標）。 */
    data class Masthead(
        val center: Point,
    ) : Part

    /** コマ [panel] の名前。 */
    data class Label(
        val panel: PanelId,
    ) : Part
}

/**
 * コンポーネントを動かす・拡大縮小する編集。すべて純粋な関数で、画面はこれを呼んで結果を保存するだけにする。
 * [panels] は画面の座標に置いたコマ（LayoutResolver の結果）、[viewWidth] は画面の幅。
 */
object PartEdits {
    /** 名前とアイコンと大見出しの倍率の範囲。時計は「サイズ」のスライダーと同じ範囲。 */
    private val SCALE = 0.5f..2f
    private val CLOCK_SIZE = 0.5f..1.6f

    /** 時計の中心（画面の座標）。時計を出していなければ null。 */
    fun clockCenter(
        layout: Layout,
        panels: List<ResolvedPanel>,
    ): Point? {
        val tree = layout.shape as? LayoutShape.Tree ?: return null
        if (!tree.clock.enabled) return null
        val b = panels.firstOrNull { it.panel.id == PanelText.clockPanel(panels, tree.clock) }?.polygon?.bounds ?: return null
        return Point(b.left + tree.clock.x * b.width, b.top + tree.clock.y * b.height)
    }

    /** 大見出しの中心（画面の座標）。大見出しが空なら null。 */
    fun mastheadCenter(
        layout: Layout,
        panels: List<ResolvedPanel>,
    ): Point? {
        val tree = layout.shape as? LayoutShape.Tree ?: return null
        val id = PanelText.mastheadPanel(panels, tree.text, tree.clock) ?: return null
        val b = panels.firstOrNull { it.panel.id == id }?.polygon?.bounds ?: return null
        return Point(b.left + b.width / 2f, b.top + tree.text.mastheadY * b.height)
    }

    /** 画面の点 [p] にある時計か大見出し。時計が上に重なるので先に調べる。どちらでもなければ null。 */
    fun textAt(
        layout: Layout,
        panels: List<ResolvedPanel>,
        viewWidth: Float,
        p: Point,
    ): Part? {
        val tree = layout.shape as? LayoutShape.Tree ?: return null
        clockCenter(layout, panels)?.let { c ->
            val k = tree.clock.size * viewWidth / tree.frame.refWidth
            if (abs(p.x - c.x) < 450f * k && p.y - c.y in -240f * k..170f * k) return Part.Clock(c)
        }
        val id = PanelText.mastheadPanel(panels, tree.text, tree.clock) ?: return null
        val b = panels.firstOrNull { it.panel.id == id }?.polygon?.bounds ?: return null
        val c = mastheadCenter(layout, panels) ?: return null
        val half = PanelText.mastheadSize(b) * tree.text.mastheadScale * 0.6f
        return if (abs(p.x - c.x) < b.width * 0.45f && abs(p.y - c.y) < half) Part.Masthead(c) else null
    }

    /**
     * コンポーネントの中心を、掴んだときの中心から ([dx], [dy]) 画素動かす。名前は動かさない。
     * - アイコン: 同じコマの中の割合で持つ（アイコンはそのコマのフォルダのものなので）
     * - 時計・大見出し: 中心の下のコマに置く（人物の後ろに回す処理がコマごとなので）。大見出しは横の中央に置くので、縦の位置だけを変える
     */
    fun move(
        layout: Layout,
        panels: List<ResolvedPanel>,
        part: Part,
        dx: Float,
        dy: Float,
    ): Layout = when (part) {
        is Part.Icons -> {
            val b = panels.firstOrNull { it.panel.id == part.panel }?.polygon?.bounds
            if (b == null) {
                layout
            } else {
                val at = Point(((part.center.x + dx - b.left) / b.width).coerceIn(0f, 1f), ((part.center.y + dy - b.top) / b.height).coerceIn(0f, 1f))
                LayoutEdits.updatePanel(layout, part.panel) { it.copy(iconAt = at) }
            }
        }

        is Part.Clock -> moveText(layout, panels, clock = true, Point(part.center.x + dx, part.center.y + dy))

        is Part.Masthead -> moveText(layout, panels, clock = false, Point(part.center.x + dx, part.center.y + dy))

        is Part.Label -> layout
    }

    private fun moveText(
        layout: Layout,
        panels: List<ResolvedPanel>,
        clock: Boolean,
        center: Point,
    ): Layout {
        val tree = layout.shape as? LayoutShape.Tree ?: return layout
        val hit = panels.lastOrNull { it.polygon.bounds.contains(center) && it.polygon.contains(center) } ?: return layout
        // 置き場所を決めていない大見出しは時計のコマに付いていくので、時計を動かすときは今のコマへ固定する
        val mastheadId = tree.text.mastheadPanel ?: PanelText.mastheadPanel(panels, tree.text, tree.clock)
        val b = hit.polygon.bounds
        val x = ((center.x - b.left) / b.width).coerceIn(0f, 1f)
        val y = ((center.y - b.top) / b.height).coerceIn(0f, 1f)
        return LayoutEdits.setTree(layout) {
            if (clock) {
                it.copy(clock = it.clock.copy(panel = hit.panel.id, x = x, y = y), text = it.text.copy(mastheadPanel = mastheadId))
            } else {
                it.copy(text = it.text.copy(mastheadPanel = hit.panel.id, mastheadY = y))
            }
        }
    }

    /** コンポーネントの今の大きさの倍率（時計は「サイズ」の値）。 */
    fun scaleOf(
        layout: Layout,
        part: Part,
    ): Float {
        val tree = layout.shape as? LayoutShape.Tree ?: return 1f
        return when (part) {
            is Part.Icons -> layout.panel(part.panel)?.iconScale ?: 1f
            is Part.Label -> layout.panel(part.panel)?.labelScale ?: 1f
            is Part.Clock -> tree.clock.size
            is Part.Masthead -> tree.text.mastheadScale
        }
    }

    /** コンポーネントの大きさの倍率を [value] にする。範囲に収める。 */
    fun setScale(
        layout: Layout,
        part: Part,
        value: Float,
    ): Layout = when (part) {
        is Part.Icons -> LayoutEdits.updatePanel(layout, part.panel) { it.copy(iconScale = value.coerceIn(SCALE)) }
        is Part.Label -> LayoutEdits.updatePanel(layout, part.panel) { it.copy(labelScale = value.coerceIn(SCALE)) }
        is Part.Clock -> LayoutEdits.setTree(layout) { it.copy(clock = it.clock.copy(size = value.coerceIn(CLOCK_SIZE))) }
        is Part.Masthead -> LayoutEdits.setTree(layout) { it.copy(text = it.text.copy(mastheadScale = value.coerceIn(SCALE))) }
    }

    /** コンポーネントの位置と大きさを自動（既定）に戻す。時計と大見出しは位置を持ち続けるので、大きさだけを戻す。 */
    fun reset(
        layout: Layout,
        part: Part,
    ): Layout = when (part) {
        is Part.Icons -> LayoutEdits.updatePanel(layout, part.panel) { it.copy(iconAt = null, iconScale = 1f) }
        is Part.Label -> LayoutEdits.updatePanel(layout, part.panel) { it.copy(labelScale = 1f) }
        is Part.Clock -> LayoutEdits.setTree(layout) { it.copy(clock = it.clock.copy(size = ClockSpec().size)) }
        is Part.Masthead -> LayoutEdits.setTree(layout) { it.copy(text = it.text.copy(mastheadScale = 1f)) }
    }

    /** [part] が属するコマと、重ね順の上での種類。時計と大見出しは、置いてあるコマのもの。 */
    fun layerOf(
        layout: Layout,
        panels: List<ResolvedPanel>,
        part: Part,
    ): Pair<PanelId, LayerKind>? {
        val tree = layout.shape as? LayoutShape.Tree ?: return null
        return when (part) {
            is Part.Icons -> part.panel to LayerKind.ICONS
            is Part.Label -> part.panel to LayerKind.LABEL
            is Part.Clock -> PanelText.clockPanel(panels, tree.clock)?.let { it to LayerKind.CLOCK }
            is Part.Masthead -> PanelText.mastheadPanel(panels, tree.text, tree.clock)?.let { it to LayerKind.MASTHEAD }
        }
    }

    /**
     * [part] の重ね順を [move] の向きへ変える。[present] はコマごとの、そこにあるものの集まり（無いものは飛ばす）。
     * 既定の順と同じになったら null に戻し、layout.json に書かないようにする。
     */
    fun reorder(
        layout: Layout,
        panels: List<ResolvedPanel>,
        part: Part,
        move: LayerOrder.Move,
        present: (PanelId) -> Set<LayerKind>,
    ): Layout {
        val (id, layer) = layerOf(layout, panels, part) ?: return layout
        return LayoutEdits.updatePanel(layout, id) { p ->
            val next = LayerOrder.move(p.order, layer, move, present(id))
            p.copy(order = next.takeIf { it != LayerOrder.DEFAULT })
        }
    }
}
