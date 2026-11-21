package dev.furunov.komalauncher.platform.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import dev.furunov.komalauncher.model.apps.AppCatalog
import dev.furunov.komalauncher.model.apps.AppEntry
import dev.furunov.komalauncher.model.apps.AppKey
import dev.furunov.komalauncher.model.apps.AppShortcut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.concurrent.atomic.AtomicLong

/**
 * LauncherApps でアプリを列挙する。PackageManager の全件の問い合わせと違い、
 * ホームアプリに許された範囲で、ランチャーに出る Activity だけが返る。
 * 仕事用プロファイルは扱わない（この端末では使っていない）。
 */
class LauncherAppsCatalog(
    private val context: Context,
    private val scope: CoroutineScope,
) : AppCatalog {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val user: UserHandle = Process.myUserHandle()
    private val state = MutableStateFlow<List<AppEntry>>(emptyList())

    // 一覧の作り直しは IO で並行に走るので、後に始めたものの結果だけを残す（先に始めたものが後に終わっても、古い一覧で上書きしない）
    private val generation = AtomicLong()

    // 前回の作り直しのときに、固定したショートカットを取得できたか。既定のホームアプリに戻ったことを refresh で知るのに使う
    @Volatile private var hadShortcutAccess = false
    override val apps: StateFlow<List<AppEntry>> = state

    init {
        reload()
        launcherApps.registerCallback(
            object : LauncherApps.Callback() {
                override fun onPackageRemoved(
                    packageName: String,
                    user: UserHandle,
                ) = reload()

                override fun onPackageAdded(
                    packageName: String,
                    user: UserHandle,
                ) = reload()

                override fun onPackageChanged(
                    packageName: String,
                    user: UserHandle,
                ) = reload()

                override fun onPackagesAvailable(
                    packageNames: Array<out String>,
                    user: UserHandle,
                    replacing: Boolean,
                ) = reload()

                override fun onPackagesUnavailable(
                    packageNames: Array<out String>,
                    user: UserHandle,
                    replacing: Boolean,
                ) = reload()

                // 固定したショートカットの追加・解除・アプリによる無効化に追従する
                override fun onShortcutsChanged(
                    packageName: String,
                    shortcuts: MutableList<ShortcutInfo>,
                    user: UserHandle,
                ) = reload()
            },
            Handler(Looper.getMainLooper()),
        )
    }

    private fun reload() {
        val gen = generation.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            hadShortcutAccess = launcherApps.hasShortcutHostPermission()
            val collator = Collator.getInstance()
            // 自分のパッケージを除くのは Activity だけ（debug の DEBUG_PIN で固定した自分のショートカットは並べる）
            val activities =
                launcherApps
                    .getActivityList(null, user)
                    .filter { it.componentName.packageName != context.packageName }
                    .map { AppEntry(AppKey(it.componentName.packageName, it.componentName.className), it.label.toString()) }
            val list = (activities + pinnedShortcuts()).sortedWith(compareBy(collator) { it.label })
            if (gen == generation.get()) state.value = list
        }
    }

    override fun resolve(packageName: String): AppKey? = state.value.firstOrNull { it.key.packageName == packageName && !it.key.isShortcut }?.key
        ?: launcherApps.getActivityList(packageName, user).firstOrNull()?.componentName?.let { AppKey(it.packageName, it.className) }

    override fun launch(key: AppKey) {
        // ショートカットは必ず id で起動し、作ったアプリ（ブラウザ）の起動の命令をそのまま使う。URL を取り出して開くと、PWA がタブで開くことがある
        val id = key.shortcutId
        if (id != null) {
            try {
                launcherApps.startShortcut(key.packageName, id, null, null, user)
            } catch (_: Exception) {
                // ショートカットが消えていた。何もしない
            }
            return
        }
        launcherApps.startMainActivity(key.component(), user, null, null)
    }

    override fun openAppInfo(key: AppKey) {
        if (key.isShortcut) return
        launcherApps.startAppDetailsActivity(key.component(), user, null, null)
    }

    override fun requestUninstall(key: AppKey) {
        if (key.isShortcut) return
        context.startActivity(
            Intent(Intent.ACTION_DELETE, Uri.fromParts("package", key.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun shortcuts(key: AppKey): List<AppShortcut> {
        if (key.isShortcut || !launcherApps.hasShortcutHostPermission()) return emptyList()
        val query =
            LauncherApps
                .ShortcutQuery()
                .setPackage(key.packageName)
                .setActivity(key.component())
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
                )
        return try {
            launcherApps
                .getShortcuts(query, user)
                .orEmpty()
                .sortedBy { it.rank }
                .map { AppShortcut(it.`package`, it.id, (it.shortLabel ?: it.longLabel ?: it.id).toString()) }
        } catch (e: Exception) {
            // ロック中や仕事用プロファイルの停止中は SecurityException / IllegalStateException になる
            emptyList()
        }
    }

    override fun allShortcuts(): List<AppShortcut> {
        if (!launcherApps.hasShortcutHostPermission()) return emptyList()
        val installed = state.value.mapTo(HashSet()) { it.key.packageName }
        // パッケージを指定しない問い合わせで 1 回にまとめる。使えない端末では、アプリごとに問い合わせる
        val all =
            try {
                val query =
                    LauncherApps
                        .ShortcutQuery()
                        .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
                launcherApps
                    .getShortcuts(query, user)
                    .orEmpty()
                    .sortedWith(compareBy({ it.`package` }, { it.rank }))
                    .map { AppShortcut(it.`package`, it.id, (it.shortLabel ?: it.longLabel ?: it.id).toString()) }
            } catch (e: Exception) {
                null
            }
        val shortcuts = all?.takeIf { it.isNotEmpty() } ?: state.value.flatMap { shortcuts(it.key) }
        return shortcuts.filter { it.packageName in installed }.distinctBy { it.packageName to it.id }
    }

    override fun launchShortcut(shortcut: AppShortcut) {
        try {
            launcherApps.startShortcut(shortcut.packageName, shortcut.id, null, null, user)
        } catch (_: Exception) {
            // ショートカットが消えていた。何もしない
        }
    }

    // 既定のホームアプリでない間は固定したショートカットを取得できず、戻っても通知が来ないので、取れるようになったときに作り直す
    override fun refresh() {
        if (!hadShortcutAccess && launcherApps.hasShortcutHostPermission()) reload()
    }

    override suspend fun unpin(key: AppKey): Boolean {
        val id = key.shortcutId ?: return false
        return withContext(Dispatchers.IO) {
            try {
                val remaining = pinned(key.packageName).map { it.id }.filter { it != id }
                launcherApps.pinShortcuts(key.packageName, remaining, user)
                true
            } catch (_: Exception) {
                // ロック中などで解除できない。呼び出し側が利用者に知らせる
                false
            }
        }
    }

    /**
     * 固定したショートカット（このランチャーが固定を受け付けたもの）。既定のホームアプリでなければ空。
     * ロック中などで問い合わせが失敗したら空を返し、Activity だけで一覧を作る（フォルダのデータには触れない）。
     */
    private fun pinnedShortcuts(): List<AppEntry> = try {
        if (!launcherApps.hasShortcutHostPermission()) {
            emptyList()
        } else {
            pinned(null).filter { it.isEnabled }.map { AppEntry(AppKey.shortcut(it.`package`, it.id), (it.shortLabel ?: it.longLabel ?: it.id).toString()) }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun pinned(packageName: String?): List<ShortcutInfo> {
        val query = LauncherApps.ShortcutQuery().setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        if (packageName != null) query.setPackage(packageName)
        return launcherApps.getShortcuts(query, user).orEmpty()
    }

    private fun AppKey.component() = ComponentName(packageName, className)
}
