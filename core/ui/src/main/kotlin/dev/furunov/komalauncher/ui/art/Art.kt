package dev.furunov.komalauncher.ui.art

import android.graphics.ImageDecoder
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * コマの画像を、画面に描く大きさちょうどに縮小して読む。画像ごとに 1 回だけ読み、同じ画像のコマで共有する。
 * ALLOCATOR_HARDWARE にすると画素は GPU 側のメモリに置かれ、アプリの Java ヒープを使わない。
 * 元の画像より大きくは読まない（拡大してもきれいにならず、メモリだけが増えるため）。
 * 合計は画面 1 枚分（1220×2712 で約 13 MB）に近い。コマが重ならず、絵は各コマの外接長方形を少し超える程度のため。
 */
@Composable
fun rememberArts(
    /** 画像の id → ファイルの絶対パス。 */
    paths: Map<String, String>,
    /** 画像の id → 描く大きさ（画素）。 */
    sizes: Map<String, Size>,
    /** 画像をインポートし直した回数。同じファイル名のまま中身が変わったときに読み直すため。 */
    revision: Long,
): State<Map<String, ImageBitmap>> = produceState(emptyMap(), paths, sizes, revision) {
    // 前の画像を残したまま読み直す。読み終えるまでの間、仮の色に戻って見えないように
    val loaded = HashMap(value.filterKeys { it in sizes })
    for ((id, size) in sizes) {
        val path = paths[id] ?: continue
        val bitmap = withContext(Dispatchers.IO) { decode(path, size) } ?: continue
        loaded[id] = bitmap
        value = HashMap(loaded)
    }
    value = loaded
}

private fun decode(
    path: String,
    size: Size,
): ImageBitmap? = try {
    ImageDecoder
        .decodeBitmap(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
            // 描く長方形は画像と同じ縦横比なので、幅と高さの比はほぼ同じ。大きいほうに合わせ、端数で粗くならないようにする
            val scale = min(1f, max(size.width / info.size.width, size.height / info.size.height))
            decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
        }.asImageBitmap()
} catch (e: Exception) {
    Log.w("Art", "$path を読めない", e)
    null
}
