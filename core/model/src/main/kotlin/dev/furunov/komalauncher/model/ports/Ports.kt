package dev.furunov.komalauncher.model.ports

import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.settings.LauncherAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** いま表示すべきレイアウト。 */
sealed interface LayoutState {
    val layout: Layout

    /** 利用者の layout.json を読めた。[imagePath] は絵のファイルの絶対パス（無ければ仮の絵）。 */
    data class Loaded(
        override val layout: Layout,
        val imagePath: String?,
    ) : LayoutState

    /**
     * 利用者の layout.json が無いか壊れているので、見本を表示している。
     * [error] が null なら単に未配置。このときはフォルダとウィジェットの整理をしない。
     */
    data class Fallback(
        override val layout: Layout,
        val error: String?,
    ) : LayoutState
}

interface LayoutSource {
    val state: StateFlow<LayoutState>
}

/** 他の画面へ渡す操作の実行。ホーム画面の中で完結する操作（フォルダを開くなど）は ViewModel が先に処理する。 */
interface ActionPerformer {
    /** 実行できたら true。権限が無いなどで実行できなければ false。 */
    fun perform(action: LauncherAction): Boolean
}

/** 通知センターとクイック設定を開く。 */
interface SystemShade {
    fun expandNotifications(): Boolean

    fun expandQuickSettings(): Boolean
}

sealed interface ScreenEvent {
    data object ScreenOff : ScreenEvent

    data object UserPresent : ScreenEvent
}

/** 画面の消灯とロック解除。Activity が止まっている間も受け取れるよう、Application の寿命で登録する。 */
interface ScreenEvents {
    val events: Flow<ScreenEvent>

    /** 画面が消えてから、まだロック解除のアニメーションを再生していなければ true。 */
    val unlockPending: StateFlow<Boolean>

    fun consumeUnlock()
}
