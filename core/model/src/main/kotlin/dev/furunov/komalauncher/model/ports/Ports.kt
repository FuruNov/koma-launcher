package dev.furunov.komalauncher.model.ports

import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.PanelArt
import dev.furunov.komalauncher.model.layout.PanelId
import dev.furunov.komalauncher.model.layout.Tilt
import dev.furunov.komalauncher.model.settings.LauncherAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** いま表示すべきレイアウト。 */
sealed interface LayoutState {
    val layout: Layout

    /** 画面に表示する知らせ（取り込みの失敗など）。null なら何も表示しない。 */
    val error: String?

    /**
     * 利用者のレイアウトを読めた。[images] は画像の id → 絵のファイルの絶対パス（ファイルがある画像だけ）。
     * [error] は、新しい layout.json の取り込みに失敗して、前のレイアウトを表示し続けているときの理由。
     * [revision] は画像を取り込んだ回数。画像のファイル名は変わらないので、読み直しの合図に使う。
     */
    data class Loaded(
        override val layout: Layout,
        val images: Map<String, String>,
        override val error: String? = null,
        val revision: Long = 0,
        /** 画像の id → 人物の切り抜きのファイルの絶対パス（切り抜きがある画像だけ）。 */
        val masks: Map<String, String> = emptyMap(),
    ) : LayoutState

    /**
     * 利用者のレイアウトが無いか壊れているので、見本を表示している。
     * [error] が null なら単に未配置。このときはフォルダとウィジェットの整理をしない。
     */
    data class Fallback(
        override val layout: Layout,
        override val error: String?,
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

/** レイアウトの書き換え。編集画面が使う。 */
interface LayoutStore : LayoutSource {
    /** レイアウトを書き換える。見本を表示しているときは、見本を元に書き換える（書いた時点で利用者のレイアウトになる）。 */
    fun update(transform: (Layout) -> Layout)

    /**
     * 利用者が選んだ画像（content:// の URI）を内部へ写し、コマ [panel] に置く。置いた画像を返す（読めなければ null）。
     * 写すことと置くことを 1 つの排他の中で行う。間に [pruneImages] が入ると、まだどのコマも使っていない新しい画像が消されるため。
     * 長辺 2048 以下に縮めるので、IO のスレッドで時間がかかる。
     */
    suspend fun addImage(
        uri: String,
        panel: PanelId,
    ): PanelArt?

    /**
     * 画像 [image] の人物を端末の中で切り抜き（ML Kit）、文字を人物の後ろに置けるようにする。
     * 失敗したら利用者に見せる理由を返す。
     */
    suspend fun cutOutPerson(image: String): String?

    /**
     * どのコマも使っていない画像を消す。元に戻す履歴が要らなくなってから（編集画面を閉じたときに）呼ぶ。
     * すぐに戻り、消すのは IO のスレッドで、取り込み・画像の追加と排他にして行う。
     */
    fun pruneImages()
}

/** 端末の傾き（視差に使う）。読むのはホームが見えている間だけにする（電池のため）。 */
interface TiltSource {
    /** 目標の傾き。画面はここへ少しずつ寄せて描く。 */
    val target: StateFlow<Tilt>

    fun start()

    fun stop()
}
