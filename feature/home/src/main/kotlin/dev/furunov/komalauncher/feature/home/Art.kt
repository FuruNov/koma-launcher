package dev.furunov.komalauncher.feature.home

import android.graphics.ImageDecoder
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * コマの絵を、画面に描く大きさちょうどに縮小して読む。
 * ALLOCATOR_HARDWARE にすると画素は GPU 側のメモリに置かれ、アプリの Java ヒープを使わない。
 * 1220×2712 で約 13 MB。元の画像が大きくても、この大きさを超えて持たない。
 */
@Composable
fun rememberArt(
    path: String?,
    width: Float,
    height: Float,
): State<ImageBitmap?> = produceState<ImageBitmap?>(null, path, width, height) {
    value = null
    if (path == null || width <= 0f || height <= 0f) return@produceState
    value =
        withContext(Dispatchers.IO) {
            try {
                val source = ImageDecoder.createSource(File(path))
                ImageDecoder
                    .decodeBitmap(source) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
                        decoder.setTargetSize(width.roundToInt(), height.roundToInt())
                    }.asImageBitmap()
            } catch (e: Exception) {
                Log.w("Art", "$path を読めない", e)
                null
            }
        }
}
