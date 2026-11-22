package dev.furunov.komalauncher.model.widgets

import dev.furunov.komalauncher.model.layout.Layout
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * コマに置いたウィジェット。appWidgetId は端末ごとに違うので layout.json には書かず、
 * バックアップには [provider]（コンポーネント名）だけを書き込んで、読み込み時に配置し直す。
 */
@Serializable
data class WidgetRef(
    val appWidgetId: Int,
    /** ComponentName.flattenToString() の形。 */
    val provider: String,
)

@Serializable
data class WidgetAssignments(
    val version: Int = 1,
    /** キーは自由に置くウィジェット（WidgetSpec）の id。廃止したウィジェットのコマの id だった名残で byPanel と呼ぶ（保存した設定の互換のため名前は変えない）。 */
    val byPanel: Map<String, WidgetRef> = emptyMap(),
)

interface WidgetStore {
    val assignments: StateFlow<WidgetAssignments>

    fun update(transform: (WidgetAssignments) -> WidgetAssignments)
}

object WidgetEdits {
    /**
     * layout.json の自由に置くウィジェットに無い割り当て（id）。廃止したウィジェットのコマの割り当ても、ここで外れる。
     * 利用者の layout.json を正しく読めたときだけ使うこと。サンプルに戻っているとき（JSON の誤り）に使うと、
     * 書き損じ 1 つで全部のウィジェットを外してしまうため。
     */
    fun orphans(
        assignments: WidgetAssignments,
        layout: Layout,
    ): Set<String> {
        val live = WidgetPlacement.widgetsOf(layout).map { it.id }.toSet()
        return assignments.byPanel.keys - live
    }
}
