package dev.furunov.komalauncher.platform.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import dev.furunov.komalauncher.model.apps.AppCatalog
import dev.furunov.komalauncher.model.apps.AppEntry
import dev.furunov.komalauncher.model.apps.AppKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.Collator

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
            },
            Handler(Looper.getMainLooper()),
        )
    }

    private fun reload() {
        scope.launch(Dispatchers.IO) {
            val collator = Collator.getInstance()
            state.value =
                launcherApps
                    .getActivityList(null, user)
                    .filter { it.componentName.packageName != context.packageName }
                    .map { AppEntry(AppKey(it.componentName.packageName, it.componentName.className), it.label.toString()) }
                    .sortedWith(compareBy(collator) { it.label })
        }
    }

    override fun resolve(packageName: String): AppKey? = state.value.firstOrNull { it.key.packageName == packageName }?.key
        ?: launcherApps.getActivityList(packageName, user).firstOrNull()?.componentName?.let { AppKey(it.packageName, it.className) }

    override fun launch(key: AppKey) {
        launcherApps.startMainActivity(key.component(), user, null, null)
    }

    override fun openAppInfo(key: AppKey) {
        launcherApps.startAppDetailsActivity(key.component(), user, null, null)
    }

    override fun requestUninstall(key: AppKey) {
        context.startActivity(
            Intent(Intent.ACTION_DELETE, Uri.fromParts("package", key.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun AppKey.component() = ComponentName(packageName, className)
}
