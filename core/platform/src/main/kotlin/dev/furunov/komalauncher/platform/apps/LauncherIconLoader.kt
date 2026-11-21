package dev.furunov.komalauncher.platform.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.furunov.komalauncher.model.apps.AppKey
import dev.furunov.komalauncher.model.settings.IconStyle
import dev.furunov.komalauncher.model.settings.Settings
import dev.furunov.komalauncher.ui.icons.IconLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * アイコンを表示する大きさで 1 回だけ Bitmap にし、最近使ったものだけを保持する。
 * 上限はバイト数で決める（48〜52 dp のアイコンで約 64 個分）。全アプリ一覧は見えている分しか頼まないので、
 * 一覧を開いてもこの上限を超えて増えない。
 */
class LauncherIconLoader(
    context: Context,
    settings: StateFlow<Settings>,
    scope: CoroutineScope,
    maxBytes: Int = 4 * 1024 * 1024,
) : IconLoader {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val density = context.resources.displayMetrics.densityDpi
    private val user = Process.myUserHandle()

    // 読み込んだショートカットのアイコンの更新時刻（「パッケージ/id」から ShortcutInfo.lastChangedTimestamp）。変わったものだけを読み直す
    private val stamps = ConcurrentHashMap<String, Long>()
    private val revision = MutableStateFlow(0L)
    override val shortcutRevision: StateFlow<Long> = revision

    // アイコンの形・縁・影。変わったらキャッシュを全て消し、styleRevision を増やして表示中のアイコンを読み直させる
    @Volatile private var style: IconStyle = settings.value.appearance.iconStyle
    private val styleRev = MutableStateFlow(0L)

    // 影の画像は形と大きさだけで決まり、アイコンの中身に依らないので、形と大きさごとに 1 枚だけ持つ（アイコンのキャッシュを圧迫しないように）
    private val shadows = ConcurrentHashMap<String, ImageBitmap>()
    override val styleRevision: StateFlow<Long> = styleRev

    init {
        scope.launch {
            settings.map { it.appearance.iconStyle }.distinctUntilChanged().collect { s ->
                if (s == style) return@collect
                style = s
                cache.evictAll()
                shadows.clear()
                styleRev.value++
            }
        }
        launcherApps.registerCallback(
            object : LauncherApps.Callback() {
                override fun onPackageRemoved(packageName: String, user: UserHandle) = Unit

                override fun onPackageAdded(packageName: String, user: UserHandle) = Unit

                override fun onPackageChanged(packageName: String, user: UserHandle) = Unit

                override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = Unit

                override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = Unit

                // この通知はどのアプリが動的なショートカットを更新しても届くので、読み込んだ固定のショートカットの時刻が変わったときだけ読み直す
                override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<ShortcutInfo>, user: UserHandle) {
                    val changed = shortcuts.filter { s -> stamps["$packageName/${s.id}"]?.let { it != s.lastChangedTimestamp } == true }
                    if (changed.isEmpty()) return
                    changed.forEach { s ->
                        stamps.remove("$packageName/${s.id}")
                        val prefix = "$packageName//${s.id}@"
                        cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach(cache::remove)
                    }
                    revision.value++
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }
    private val cache =
        object : LruCache<String, ImageBitmap>(maxBytes) {
            override fun sizeOf(
                key: String,
                value: ImageBitmap,
            ) = value.width * value.height * 4
        }

    private fun cacheKey(
        key: AppKey,
        sizePx: Int,
        st: IconStyle = style,
    ) = "${key.packageName}/${key.className}/${key.shortcutId.orEmpty()}@$sizePx#$st"

    override fun cached(
        key: AppKey,
        sizePx: Int,
    ): ImageBitmap? = cache.get(cacheKey(key, sizePx))

    override suspend fun load(
        key: AppKey,
        sizePx: Int,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        // 設定は 1 回だけ読む。描く間に設定が変わっても、古い形の画像を新しいキーで残さないように
        val st = style
        cache.get(cacheKey(key, sizePx, st))?.let { return@withContext it }
        val drawable = (if (key.isShortcut) shortcutIcon(key) else activityIcon(key)) ?: return@withContext null
        val bitmap = IconRenderer.icon(drawable, sizePx, st).asImageBitmap()
        cache.put(cacheKey(key, sizePx, st), bitmap)
        bitmap
    }

    override fun cachedShadow(
        key: AppKey,
        sizePx: Int,
    ): ImageBitmap? = style.let { st -> if (st.shadow) shadows["${st.shape}@$sizePx"] else null }

    override suspend fun loadShadow(
        key: AppKey,
        sizePx: Int,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        val st = style
        if (!st.shadow) return@withContext null
        val k = "${st.shape}@$sizePx"
        shadows[k]?.let { return@withContext it }
        val bitmap = IconRenderer.shadow(sizePx, st)?.asImageBitmap() ?: return@withContext null
        shadows[k] = bitmap
        bitmap
    }

    override fun trim() {
        cache.evictAll()
        shadows.clear()
    }

    private fun activityIcon(key: AppKey): Drawable? = launcherApps
        .resolveActivity(Intent(Intent.ACTION_MAIN).setComponent(ComponentName(key.packageName, key.className)), user)
        ?.getBadgedIcon(density)

    /** 固定したショートカットのアイコン。ショートカットが消えていたり、ロック中で問い合わせられなければ null。 */
    private fun shortcutIcon(key: AppKey): Drawable? = try {
        val query =
            LauncherApps
                .ShortcutQuery()
                .setPackage(key.packageName)
                .setShortcutIds(listOfNotNull(key.shortcutId))
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        launcherApps.getShortcuts(query, user)?.firstOrNull()?.let { info ->
            stamps["${key.packageName}/${info.id}"] = info.lastChangedTimestamp
            launcherApps.getShortcutIconDrawable(info, density)
        }
    } catch (_: Exception) {
        null
    }
}
