package dev.furunov.komalauncher.platform.apps

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import dev.furunov.komalauncher.model.apps.AppRole

/**
 * 各アプリの役割（AppRole）を、Intent を受けられるかで調べる。
 * マニフェストの <queries> で起動できる全アプリのパッケージが見えているので、その Activity への問い合わせに別の宣言は要らない。
 */
internal object AppRoleProbe {
    private fun view(
        uri: String,
        type: String? = null,
    ) = Intent(Intent.ACTION_VIEW).apply { if (type == null) data = Uri.parse(uri) else setDataAndType(Uri.parse(uri), type) }

    private val probes: List<Pair<AppRole, Intent>> =
        listOf(
            // 特定のサイトだけを開くアプリ（自分のドメインの URL を受けるもの）は、example.com には一致しない
            AppRole.BROWSER to view("https://example.com").addCategory(Intent.CATEGORY_BROWSABLE),
            AppRole.DIAL to Intent(Intent.ACTION_DIAL, Uri.parse("tel:0")),
            AppRole.MESSAGE to Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:0")),
            AppRole.MESSAGE to Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:a@example.com")),
            AppRole.MAPS to view("geo:0,0?q=tokyo"),
            AppRole.CAMERA to Intent(MediaStore.ACTION_IMAGE_CAPTURE),
            AppRole.IMAGE to view("content://probe/1", "image/jpeg"),
            AppRole.VIDEO to view("content://probe/1", "video/mp4"),
            AppRole.AUDIO to view("content://probe/1", "audio/mpeg"),
            // 実在しない種類。これを開けると言うアプリは、どんなファイルでも受ける
            AppRole.ANY_FILE to view("content://probe/1", "application/x-koma-probe"),
        )

    /** パッケージ名 → 役割。時間がかかるので IO のスレッドで呼ぶ。 */
    fun roles(pm: PackageManager): Map<String, Set<AppRole>> {
        val result = HashMap<String, MutableSet<AppRole>>()
        for ((role, intent) in probes) {
            val found = runCatching { pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())) }.getOrDefault(emptyList())
            found.forEach { result.getOrPut(it.activityInfo.packageName) { mutableSetOf() } += role }
        }
        return result
    }
}
