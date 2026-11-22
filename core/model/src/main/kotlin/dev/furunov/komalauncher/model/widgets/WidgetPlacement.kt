package dev.furunov.komalauncher.model.widgets

import dev.furunov.komalauncher.model.layout.LayerOrder
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.LayoutShape
import dev.furunov.komalauncher.model.layout.WidgetSpec
import dev.furunov.komalauncher.model.layout.WidgetStyle

/** 自由に置くウィジェット（[WidgetSpec]）を、コマ割りに追加・削除する計算。 */
object WidgetPlacement {
    /** 画面に対する幅と高さの下限と上限。小さすぎると押せず、大きすぎると絵が見えない。 */
    private const val MIN = 0.1f

    /** 動かしたり大きさを変えたりするときの下限。内蔵のテキストは画面の 1 割より低いので、置いた後はここまで小さくできる。 */
    const val MIN_EDIT = 0.03f
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
        /** 幅と高さの下限。内蔵のウィジェットは押す対象ではないので、小さくてよい。 */
        min: Float = MIN,
    ): WidgetSpec {
        val w = (widthDp / screenWidthDp).coerceIn(min, MAX)
        val h = (heightDp / screenHeightDp).coerceIn(min, MAX)
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

    /** 内蔵のウィジェットの見た目を [style] にする。 */
    fun setStyle(
        layout: Layout,
        id: String,
        style: WidgetStyle,
    ): Layout = update(layout, id) { it.copy(style = style) }

    /** 位置と大きさを保ったまま、中身を [provider] のウィジェットに入れ替える。 */
    fun replace(
        layout: Layout,
        id: String,
        provider: String,
    ): Layout = update(layout, id) { it.copy(provider = provider) }

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
    ): Layout = update(layout, id) { it.copy(w = w.coerceIn(MIN_EDIT, maxOf(MIN_EDIT, 1f - it.x)), h = h.coerceIn(MIN_EDIT, maxOf(MIN_EDIT, 1f - it.y))) }

    /** 掴んだときの位置と大きさ [start] を、中心を保ったまま [k] 倍にする（2 本指でつまむ操作）。 */
    fun scale(
        layout: Layout,
        start: WidgetSpec,
        k: Float,
    ): Layout {
        // 縦横比を保つため、両辺が範囲に収まる倍率に絞る
        val lo = maxOf(MIN_EDIT / start.w, MIN_EDIT / start.h)
        val hi = minOf(1f / start.w, 1f / start.h)
        val f = k.coerceIn(lo, maxOf(lo, hi))
        val w = start.w * f
        val h = start.h * f
        val cx = start.x + start.w / 2f
        val cy = start.y + start.h / 2f
        return update(layout, start.id) { it.copy(x = (cx - w / 2f).coerceIn(0f, 1f - w), y = (cy - h / 2f).coerceIn(0f, 1f - h), w = w, h = h) }
    }

    /**
     * ウィジェット [id] の重ね順を変える。人物の後ろのウィジェット・人物・人物の前のウィジェットを 1 列に並べ、その中で動かす。
     * 人物を越えたら、人物の前と後ろが入れ替わる。
     */
    fun reorder(
        layout: Layout,
        id: String,
        move: LayerOrder.Move,
    ): Layout = edit(layout) { ws ->
        val target = ws.firstOrNull { it.id == id } ?: return@edit ws
        // null が人物
        val line: MutableList<WidgetSpec?> = (ws.filter { !it.front } + listOf(null) + ws.filter { it.front }).toMutableList()
        val from = line.indexOf(target)
        line.removeAt(from)
        val to =
            when (move) {
                LayerOrder.Move.BACK -> 0
                LayerOrder.Move.BACKWARD -> (from - 1).coerceAtLeast(0)
                LayerOrder.Move.FORWARD -> (from + 1).coerceAtMost(line.size)
                LayerOrder.Move.FRONT -> line.size
            }
        line.add(to, target)
        val person = line.indexOf(null)
        line.mapIndexedNotNull { i, w -> w?.copy(front = i > person) }
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
