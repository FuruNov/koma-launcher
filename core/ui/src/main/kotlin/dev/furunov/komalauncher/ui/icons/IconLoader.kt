package dev.furunov.komalauncher.ui.icons

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.furunov.komalauncher.model.apps.AppKey

/** アイコンを表示する大きさで読む。実装は少数だけを保持し、メモリが逼迫したら [trim] で捨てる。 */
interface IconLoader {
    /** 保持していればすぐ返す。無ければ null（[load] で読む）。 */
    fun cached(
        key: AppKey,
        sizePx: Int,
    ): ImageBitmap?

    suspend fun load(
        key: AppKey,
        sizePx: Int,
    ): ImageBitmap?

    fun trim()
}

/** 機能のモジュールは実装を知らずに、この CompositionLocal から受け取る。 */
val LocalIconLoader = staticCompositionLocalOf<IconLoader> { error("IconLoader が渡されていない") }

@Composable
fun AppIcon(
    key: AppKey,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val loader = LocalIconLoader.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    var bitmap by remember(key, px) { mutableStateOf(loader.cached(key, px)) }
    LaunchedEffect(key, px) {
        if (bitmap == null) bitmap = loader.load(key, px)
    }
    val b = bitmap
    if (b != null) {
        Image(b, contentDescription = null, modifier = modifier.size(size))
    } else {
        // 読み込み中は同じ大きさの影を置き、並びがずれないようにする
        Box(modifier.size(size).background(Color(0x33FFFFFF), RoundedCornerShape(size / 4)))
    }
}

val DefaultIconSize = 52.dp
