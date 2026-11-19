package dev.furunov.komalauncher.model.apps

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** 起動できるアプリ 1 つ（パッケージと Activity の組）。1 つのパッケージが複数の入口を持つことがあるため、パッケージ名だけにしない。 */
@Serializable
data class AppKey(
    val packageName: String,
    val className: String,
)

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
}
