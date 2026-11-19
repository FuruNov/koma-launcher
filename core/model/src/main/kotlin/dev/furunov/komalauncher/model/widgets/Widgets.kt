package dev.furunov.komalauncher.model.widgets

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * コマに置いたウィジェット。appWidgetId は端末ごとに違うので layout.json には書かず、
 * バックアップには [provider]（コンポーネント名）だけを書き出して、読み込み時に配置し直す。
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
    val byPanel: Map<String, WidgetRef> = emptyMap(),
)

interface WidgetStore {
    val assignments: StateFlow<WidgetAssignments>

    fun update(transform: (WidgetAssignments) -> WidgetAssignments)
}
