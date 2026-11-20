package dev.furunov.komalauncher.platform.files

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.InputStream

/**
 * 利用者が選んだフォルダの中のファイルを読むために開く。
 * Waydroid（Android 13）では、端末内の共有ストレージのファイルを "r" で開くと、MediaProvider の中で
 * NullPointerException になる（AppOpsService.checkPackage）。"rw" では開けるので、"r" が失敗したときだけ "rw" で開き直す。
 * pCloud などの独自のプロバイダは "r" で開けるので、"rw" は使わない（書き込みとみなしてアップロードし直す恐れがあるため）。
 */
internal fun ContentResolver.openForRead(uri: Uri): InputStream? = try {
    openInputStream(uri)
} catch (e: NullPointerException) {
    Log.w(TAG, "$uri を r で開けないので rw で開き直す", e)
    // 読む権限しか無いファイル（ファイルの選択画面で選んだもの）は rw で開けない。そのときは読めなかったとして null を返す
    try {
        openFileDescriptor(uri, "rw")?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
    } catch (e2: Exception) {
        Log.w(TAG, "$uri を rw でも開けない", e2)
        null
    }
}

private const val TAG = "Documents"
