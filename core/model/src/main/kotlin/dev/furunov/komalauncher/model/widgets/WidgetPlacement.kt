package dev.furunov.komalauncher.model.widgets

import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.LayoutShape
import dev.furunov.komalauncher.model.layout.WidgetSpec

/** 自由に置くウィジェット（[WidgetSpec]）を、コマ割りに追加・削除する計算。 */
object WidgetPlacement {
    /** 画面に対する幅と高さの下限と上限。小さすぎると押せず、大きすぎると絵が見えない。 */
    private const val MIN = 0.1f
    private const val MAX = 0.92f

    /** コマの id とも、ほかのウィジェットの id とも重ならない id。割り当ての表（[WidgetAssignments.byPanel]）を共有するため。 */
    fun newId(layout: Layout): String {
        val used = layout.panels.map { it.id.value }.toSet() + widgetsOf(layout).map { it.id }
        return generateSequence(1) { it + 1 }.map { "widget-$it" }.first { it !in used }
    }

    /**
     * 新しく置くウィジェットの位置と大きさ。ウィジェットが求める大きさ（dp）を画面の割合にし、
     * 画面の中央に置く。前に置いたウィジェットと同じ位置なら、少しずつ下へずらす。
     */
    fun initial(
        layout: Layout,
        id: String,
        provider: String,
        widthDp: Float,
        heightDp: Float,
        screenWidthDp: Float,
        screenHeightDp: Float,
    ): WidgetSpec {
        val w = (widthDp / screenWidthDp).coerceIn(MIN, MAX)
        val h = (heightDp / screenHeightDp).coerceIn(MIN, MAX)
        val x = (1f - w) / 2f
        var y = ((1f - h) / 2f)
        val taken = widgetsOf(layout).map { it.y }
        while (taken.any { kotlin.math.abs(it - y) < 0.01f } && y + h + 0.05f <= 1f) y += 0.05f
        return WidgetSpec(id, provider, x, y, w, h)
    }

    fun add(
        layout: Layout,
        spec: WidgetSpec,
    ): Layout = edit(layout) { it + spec }

    fun remove(
        layout: Layout,
        id: String,
    ): Layout = edit(layout) { ws -> ws.filterNot { it.id == id } }

    /** 左上を ([x], [y]) へ動かす。画面からはみ出さないように収める。 */
    fun moveTo(
        layout: Layout,
        id: String,
        x: Float,
        y: Float,
    ): Layout = update(layout, id) { it.copy(x = x.coerceIn(0f, 1f - it.w), y = y.coerceIn(0f, 1f - it.h)) }

    /** 左上を保ったまま、幅と高さを変える。画面の右端と下端を越えないように収める。 */
    fun resize(
        layout: Layout,
        id: String,
        w: Float,
        h: Float,
    ): Layout = update(layout, id) { it.copy(w = w.coerceIn(MIN, maxOf(MIN, 1f - it.x)), h = h.coerceIn(MIN, maxOf(MIN, 1f - it.y))) }

    /** 掴んだときの位置と大きさ [start] を、中心を保ったまま [k] 倍にする（2 本指でつまむ操作）。 */
    fun scale(
        layout: Layout,
        start: WidgetSpec,
        k: Float,
    ): Layout {
        // 縦横比を保つため、両辺が範囲に収まる倍率に絞る
        val lo = maxOf(MIN / start.w, MIN / start.h)
        val hi = minOf(1f / start.w, 1f / start.h)
        val f = k.coerceIn(lo, maxOf(lo, hi))
        val w = start.w * f
        val h = start.h * f
        val cx = start.x + start.w / 2f
        val cy = start.y + start.h / 2f
        return update(layout, start.id) { it.copy(x = (cx - w / 2f).coerceIn(0f, 1f - w), y = (cy - h / 2f).coerceIn(0f, 1f - h), w = w, h = h) }
    }

    private fun update(
        layout: Layout,
        id: String,
        transform: (WidgetSpec) -> WidgetSpec,
    ): Layout = edit(layout) { ws -> ws.map { if (it.id == id) transform(it) else it } }

    fun widgetsOf(layout: Layout): List<WidgetSpec> = (layout.shape as? LayoutShape.Tree)?.widgets.orEmpty()

    /** version 1 のコマ割りには置けないので、そのまま返す。 */
    private fun edit(
        layout: Layout,
        transform: (List<WidgetSpec>) -> List<WidgetSpec>,
    ): Layout {
        val s = layout.shape as? LayoutShape.Tree ?: return layout
        return Layout(s.copy(widgets = transform(s.widgets)))
    }
}
