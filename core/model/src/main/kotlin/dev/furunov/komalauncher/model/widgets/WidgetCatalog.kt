package dev.furunov.komalauncher.model.widgets

import dev.furunov.komalauncher.model.apps.AppSearch

/** ウィジェットの選択画面に並べる 1 つ。[key] は provider（"パッケージ名/クラス名"）。 */
data class WidgetEntry(
    val key: String,
    val label: String,
    val appLabel: String,
    val packageName: String,
)

/** アプリごとのまとまり。 */
data class WidgetGroup(
    val packageName: String,
    val appLabel: String,
    val widgets: List<WidgetEntry>,
)

object WidgetCatalog {
    /**
     * アプリごとにまとめ、アプリの名前の順に並べる。[query] が空でなければ、ウィジェットの名前かアプリの名前で絞る。
     * アプリの名前で一致したら、そのアプリのウィジェットをすべて残す（「天気」のアプリを探す使い方のため）。
     */
    fun group(
        entries: List<WidgetEntry>,
        query: String,
    ): List<WidgetGroup> = entries
        .groupBy { it.packageName }
        .map { (pkg, ws) -> WidgetGroup(pkg, ws.first().appLabel, ws.sortedBy { it.label }) }
        .mapNotNull { g ->
            when {
                query.isBlank() || AppSearch.match(query, g.appLabel) != null -> g
                else -> g.widgets.filter { AppSearch.match(query, it.label) != null }.takeIf { it.isNotEmpty() }?.let { g.copy(widgets = it) }
            }
        }.sortedBy { it.appLabel.lowercase() }

    /**
     * ウィジェットの大きさ（dp）を、ホーム画面の格子のマス数にする。Android の旧来の目安（1 マス 70dp、余白 30dp）に従う。
     * Android 12 以降でアプリが決めたマス数（targetCellWidth など）があれば、そちらを使う。
     */
    fun cells(
        widthDp: Float,
        heightDp: Float,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
    ): Pair<Int, Int> {
        fun of(dp: Float) = ((dp + 30f) / 70f).toInt().coerceAtLeast(1)
        return (if (targetWidth > 0) targetWidth else of(widthDp)) to (if (targetHeight > 0) targetHeight else of(heightDp))
    }
}
