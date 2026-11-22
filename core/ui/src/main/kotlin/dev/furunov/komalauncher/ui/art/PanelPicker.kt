package dev.furunov.komalauncher.ui.art

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.furunov.komalauncher.model.geometry.Point
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.LayoutResolver
import dev.furunov.komalauncher.model.layout.LayoutShape
import dev.furunov.komalauncher.model.layout.PanelContent
import dev.furunov.komalauncher.model.layout.PanelId

/**
 * コマ割りのプレビューから、フォルダのコマを 1 つ選ぶ。ホーム画面への追加の依頼を受けたときに、入れるコマを選ぶのに使う。
 * 描き方は編集画面のプレビューと同じ（PanelFace の絵かトーンと枠）。文字・時計・アイコンは描かない。
 * フォルダ以外のコマは押せないので、暗くして示す。選んだコマにはマゼンタの枠を引く。
 * [images] と [revision] は LayoutState.Loaded の画像の場所とリビジョン。[screenWidth]・[screenHeight] はホームの画面の大きさ。
 */
@Composable
fun PanelPicker(
    layout: Layout,
    images: Map<String, String>,
    revision: Long,
    defaultGutter: Long,
    screenWidth: Float,
    screenHeight: Float,
    selected: PanelId?,
    onSelect: (PanelId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resolved = remember(layout, screenWidth, screenHeight) { LayoutResolver.resolve(layout, screenWidth, screenHeight) }
    val faces =
        remember(resolved, layout, defaultGutter) {
            val style = PanelStyle.of(layout, screenWidth, defaultGutter)
            resolved.panels.map { PanelFace(it.polygon, it.art, it.index, style, it.panel.art?.dim ?: 0f) }
        }
    val sizes =
        remember(resolved) {
            resolved.panels.mapNotNull { p -> p.art?.let { a -> p.panel.art?.image?.let { it to Size(a.width, a.height) } } }.toMap()
        }
    val arts by rememberArts(images, sizes, revision)
    val gutter = Color((layout.shape as? LayoutShape.Tree)?.frame?.gutterColor ?: defaultGutter)
    Canvas(
        modifier
            .aspectRatio(screenWidth / screenHeight)
            .pointerInput(resolved) {
                detectTapGestures { o ->
                    val k = screenWidth / size.width
                    val p = Point(o.x * k, o.y * k)
                    resolved.panels.lastOrNull { it.panel.content is PanelContent.Folder && it.polygon.contains(p) }?.let { onSelect(it.panel.id) }
                }
            },
    ) {
        val s = size.width / screenWidth
        drawRect(gutter)
        scale(s, pivot = Offset.Zero) {
            resolved.panels.forEachIndexed { i, p ->
                val face = faces[i]
                translate(face.bounds.left, face.bounds.top) {
                    clipPath(face.path) {
                        with(face) { drawArt(p.panel.art?.image?.let { arts[it] }) }
                        // フォルダ以外のコマには入れられないので暗くする
                        if (p.panel.content !is PanelContent.Folder) drawRect(Color.Black.copy(alpha = 0.65f), size = Size(face.bounds.width, face.bounds.height))
                    }
                    with(face) { drawBorder() }
                    if (p.panel.id == selected) {
                        // プレビューの上で 3dp の太さになるよう、縮小の分だけ太く引く。線の内側の半分だけを残す
                        clipPath(face.path) { drawPath(face.path, SELECTED, style = Stroke(width = 3.dp.toPx() / s * 2f)) }
                    }
                }
            }
        }
    }
}

private val SELECTED = Color(0xFFE0447A)
