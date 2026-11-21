package dev.furunov.komalauncher.model.apps

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** 起動できるアプリ 1 つ（パッケージと Activity の組）。1 つのパッケージが複数の入口を持つことがあるため、パッケージ名だけにしない。 */
@Serializable
data class AppKey(
    val packageName: String,
    val className: String,
    /** 固定したショートカット（ブラウザの PWA など）なら、その id。null ならアプリ（Activity）。ショートカットの className は空文字列。 */
    val shortcutId: String? = null,
) {
    /** 固定したショートカットなら true。起動・アイコン・長押しのメニューで分岐する。 */
    val isShortcut: Boolean get() = shortcutId != null

    companion object {
        fun shortcut(
            packageName: String,
            id: String,
        ): AppKey = AppKey(packageName, "", id)
    }
}

/** アプリが公開しているショートカット（長押しのメニューの項目）。 */
data class AppShortcut(
    val packageName: String,
    val id: String,
    val label: String,
)

data class AppEntry(
    val key: AppKey,
    val label: String,
)

/** 端末に入っているアプリの一覧と起動。実装は LauncherApps を包む。 */
interface AppCatalog {
    /** ラベル順の一覧。アプリの追加・削除で更新される。 */
    val apps: StateFlow<List<AppEntry>>

    /** layout.json の初期値（パッケージ名だけ）から、起動できる入口を探す。 */
    fun resolve(packageName: String): AppKey?

    fun launch(key: AppKey)

    fun openAppInfo(key: AppKey)

    fun requestUninstall(key: AppKey)

    /** ホームアプリに設定されていないと取得できないので、そのときは空を返す。 */
    fun shortcuts(key: AppKey): List<AppShortcut>

    fun launchShortcut(shortcut: AppShortcut)

    /** 固定したショートカット [key] の固定を解除する。解除できれば true。アプリなら何もせず false。問い合わせを伴うので、裏のスレッドで行う。 */
    suspend fun unpin(key: AppKey): Boolean

    /** ホームが前面に戻ったときに呼ぶ。既定のホームアプリに戻った後など、取り直しが要るときだけ一覧を作り直す。 */
    fun refresh() = Unit

    /** 検索のための、全アプリのショートカット。時間がかかるので IO のスレッドで呼ぶ。ホームアプリでないときは空。 */
    fun allShortcuts(): List<AppShortcut>
}
