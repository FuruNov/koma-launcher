package dev.furunov.komalauncher.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlinx.coroutines.delay

/** 先に作ったスナップショットを、使われなければ捨てるまでの時間。上へスワイプしてから一覧が開くまでは 0.2 秒ほど。 */
private const val PREPARED_KEEP_MS = 1_500L

/**
 * [frozen] の間は、中身を描かずに、スナップショットの画像を描く。
 *
 * 全アプリ一覧の背景は 95% 不透明で、開いている間も後ろのホームを毎フレーム描いていた。
 * ホームを描くだけで GPU が 1 フレームに 17ms ほどかかり（コマの絵・人物の切り抜き・層の重ね合わせ。POCO X7 Pro、2026-11-22）、
 * 120Hz の 8ms に収まらず、開く動きやキーボードの動きがカクついた。スナップショットの画像を 1 枚描くだけなら、この時間はほぼ無くなる。
 * スナップショットの間は、ホームの動き（傾きの立体感、ビジュアライザー）は止まる。一覧にほぼ隠れているので見えない。
 *
 * スナップショットを作る（toImageBitmap）と UI スレッドが 30ms ほど止まる。一覧が開いてから作ると、開く動きの最初が引っかかるので、
 * 上へスワイプし始めた時点（[prepare] が変わったとき）に先に作る。指を動かしている間は画面が止まっているので、引っかかりは見えない。
 *
 * 普段は中身を GraphicsLayer に記録してから描き、スナップショットを作るときはその記録から画像を作る（もう一度描き直さないため）。
 */
@Composable
internal fun Modifier.freezable(
    frozen: Boolean,
    prepare: Long,
): Modifier {
    val layer = rememberGraphicsLayer()
    var still by remember { mutableStateOf<ImageBitmap?>(null) }
    val isFrozen by rememberUpdatedState(frozen)
    LaunchedEffect(prepare) {
        if (prepare == 0L) return@LaunchedEffect
        still = layer.toImageBitmap()
        // スワイプしても一覧を開かなかったら（下へ戻したなど）、スナップショットを捨てる（画面 1 枚ぶん、十数 MB あるため）
        delay(PREPARED_KEEP_MS)
        if (!isFrozen) still = null
    }
    LaunchedEffect(frozen) {
        if (frozen) {
            // スワイプせずに開いたとき（ジェスチャー以外から開いたとき）は、ここで作る
            if (still == null) still = layer.toImageBitmap()
        } else {
            still = null
        }
    }
    return drawWithContent {
        val image = still
        if (frozen && image != null) {
            drawImage(image)
        } else {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }
    }
}
