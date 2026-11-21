package dev.furunov.komalauncher.platform.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Process
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.furunov.komalauncher.model.apps.AppKey
import dev.furunov.komalauncher.ui.icons.IconLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * アイコンを表示する大きさで 1 回だけ Bitmap にし、最近使ったものだけを保持する。
 * 上限はバイト数で決める（48〜52 dp のアイコンで約 64 個分）。全アプリ一覧は見えている分しか頼まないので、
 * 一覧を開いてもこの上限を超えて増えない。
 */
class LauncherIconLoader(
    context: Context,
    maxBytes: Int = 4 * 1024 * 1024,
) : IconLoader {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val density = context.resources.displayMetrics.densityDpi
    private val user = Process.myUserHandle()
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
    ) = "${key.packageName}/${key.className}/${key.shortcutId.orEmpty()}@$sizePx"

    override fun cached(
        key: AppKey,
        sizePx: Int,
    ): ImageBitmap? = cache.get(cacheKey(key, sizePx))

    override suspend fun load(
        key: AppKey,
        sizePx: Int,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        cached(key, sizePx)?.let { return@withContext it }
        val drawable = (if (key.isShortcut) shortcutIcon(key) else activityIcon(key)) ?: return@withContext null
        val bitmap =
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also {
                drawable.setBounds(0, 0, sizePx, sizePx)
                drawable.draw(Canvas(it))
            }.asImageBitmap()
        cache.put(cacheKey(key, sizePx), bitmap)
        bitmap
    }

    override fun trim() = cache.evictAll()

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
        launcherApps.getShortcuts(query, user)?.firstOrNull()?.let { launcherApps.getShortcutIconDrawable(it, density) }
    } catch (_: Exception) {
        null
    }
}
