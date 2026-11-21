package dev.furunov.komalauncher.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.furunov.komalauncher.model.apps.AppKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

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

    /**
     * 固定したショートカットのアイコンが作ったアプリの側で変わるたびに増える数。[AppIcon] はショートカットのときだけこれを見て読み直す。
     * アプリのアイコンには使わない（アプリの更新では一覧ごと作り直されるため）。
     */
    val shortcutRevision: StateFlow<Long> get() = NO_REVISION

    /** アイコンの形・縁・影の設定が変わるたびに増える数。[AppIcon] とコマのアイコンは、これが変わったら読み直す。 */
    val styleRevision: StateFlow<Long> get() = NO_REVISION

    /** 影の画像（保持していれば）。影の無い設定なら null。大きさは一辺 × (1 + 2 × [SHADOW_PAD])。 */
    fun cachedShadow(
        key: AppKey,
        sizePx: Int,
    ): ImageBitmap? = null

    /** 影の画像を読む。影の無い設定なら null。 */
    suspend fun loadShadow(
        key: AppKey,
        sizePx: Int,
    ): ImageBitmap? = null

    fun trim()
}

/** 機能のモジュールは実装を知らずに、この CompositionLocal から受け取る。 */
val LocalIconLoader = staticCompositionLocalOf<IconLoader> { error("IconLoader が渡されていない") }

@Composable
fun AppIcon(
    key: AppKey,
    size: Dp,
    modifier: Modifier = Modifier,
    /** アイコンに掛ける色の変換（コマの色調に合わせるときなど）。 */
    colorFilter: ColorFilter? = null,
) {
    val loader = LocalIconLoader.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val revision by loader.shortcutRevision.collectAsState()
    val styleRevision by loader.styleRevision.collectAsState()
    // アプリのアイコンはショートカットの revision で読み直さない（読み直す必要のあるショートカットだけが変わったとき、全てのアイコンを読み直さないように）
    val rev = (if (key.isShortcut) revision else 0L) to styleRevision
    var bitmap by remember(key, px, rev) { mutableStateOf(loader.cached(key, px)) }
    var shadow by remember(key, px, rev) { mutableStateOf(loader.cachedShadow(key, px)) }
    LaunchedEffect(key, px, rev) {
        if (bitmap == null) bitmap = loader.load(key, px)
        if (shadow == null) shadow = loader.loadShadow(key, px)
    }
    val b = bitmap
    if (b != null) {
        Box(modifier.size(size)) {
            // 影は傾きに追従させず、光が左上から当たる向き（右下）に固定する。大きさは変えず、アイコンの下に重ねる
            shadow?.let { sh ->
                Canvas(Modifier.matchParentSize()) {
                    val pad = this.size.width * SHADOW_PAD
                    val d = this.size.width * SHADOW_SHIFT
                    drawImage(
                        sh,
                        dstOffset = IntOffset((d * 0.64f - pad).roundToInt(), (d * 0.77f - pad).roundToInt()),
                        dstSize = IntSize((this.size.width + pad * 2).roundToInt(), (this.size.height + pad * 2).roundToInt()),
                        alpha = SHADOW_ALPHA,
                    )
                }
            }
            Image(b, contentDescription = null, modifier = Modifier.matchParentSize(), colorFilter = colorFilter)
        }
    } else {
        // 読み込み中は同じ大きさの影を置き、並びがずれないようにする
        Box(modifier.size(size).background(Color(0x33FFFFFF), RoundedCornerShape(size / 4)))
    }
}

val DefaultIconSize = 52.dp

private val NO_REVISION: StateFlow<Long> = MutableStateFlow(0L)

/** 影の画像の、ぼかしがはみ出す分の余白（アイコンの一辺に対する割合）。影はこの分だけ四方に広げて描く。 */
const val SHADOW_PAD = 0.1f

/** 影をずらす量（アイコンの一辺に対する割合）と不透明度。2026-11-21 に実機で見比べた試作の値。 */
const val SHADOW_SHIFT = 0.1f
const val SHADOW_ALPHA = 0.7f
