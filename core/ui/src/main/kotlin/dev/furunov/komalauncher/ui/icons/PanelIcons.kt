package dev.furunov.komalauncher.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import dev.furunov.komalauncher.model.apps.AppKey

/**
 * コマに置くアイコンの画像。[keys] はアプリ（null なら全アプリ一覧の記号）と一辺の画素の組。
 * キャッシュに無いものは読み込んで、読み込めたら差し替える。ホームと編集画面の縮小図で使う。
 */
@Composable
fun rememberIconBitmaps(keys: List<Pair<AppKey?, Int>>): State<List<ImageBitmap?>> {
    val loader = LocalIconLoader.current
    // 固定したショートカットを含むときだけ、アイコンの変更（shortcutRevision）で読み直す
    val revision by loader.shortcutRevision.collectAsState()
    val rev = if (keys.any { it.first?.isShortcut == true }) revision else 0L
    val state =
        remember(keys, rev) {
            mutableStateOf(
                keys.map { (app, px) ->
                    when {
                        px <= 0 -> null
                        app == null -> drawerGlyph(px)
                        else -> loader.cached(app, px)
                    }
                },
            )
        }
    LaunchedEffect(keys, rev) {
        keys.forEachIndexed { i, (app, px) ->
            if (state.value[i] == null && app != null && px > 0) {
                val bitmap = loader.load(app, px)
                state.value = state.value.toMutableList().also { it[i] = bitmap }
            }
        }
    }
    return state
}

/**
 * 全アプリ一覧を表す記号（明るい丸の中に 3×3 の点）。多くのランチャーで使われている一般的な形を、このアプリで描く。
 * 色はアプリのアイコンと同じく、描くときにコマの色調を掛ける。
 */
fun drawerGlyph(px: Int): ImageBitmap {
    val bitmap = ImageBitmap(px, px)
    val canvas = Canvas(bitmap)
    val c = px / 2f
    canvas.drawCircle(Offset(c, c), px * 0.48f, Paint().apply { color = Color(0xFFF1F3F4) })
    val dot = Paint().apply { color = Color(0xFF5F6368) }
    val step = px * 0.2f
    for (i in -1..1) {
        for (j in -1..1) canvas.drawCircle(Offset(c + i * step, c + j * step), px * 0.055f, dot)
    }
    return bitmap
}
