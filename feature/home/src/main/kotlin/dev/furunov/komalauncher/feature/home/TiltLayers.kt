package dev.furunov.komalauncher.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import dev.furunov.komalauncher.ui.art.DepthMaps
import dev.furunov.komalauncher.ui.art.DepthStyle
import dev.furunov.komalauncher.ui.art.PanelFace
import dev.furunov.komalauncher.ui.art.PersonMask
import dev.furunov.komalauncher.ui.art.drawPersonImage
import dev.furunov.komalauncher.ui.art.drawPersonOutline
import dev.furunov.komalauncher.ui.art.drawPersonShadow
import dev.furunov.komalauncher.ui.art.drawRimShape
import kotlin.math.ceil

/**
 * 傾きで位置だけが変わる層。中身は傾きを読まずに描くので、傾きが変わっても描画の命令を作り直さない。
 * [cache] が true なら、中身を GPU のテクスチャに保存し、傾きでは保存したテクスチャを動かすだけにする（重い中身に使う）。
 * テクスチャは層の大きさで切れるので、動かしても端が見えないよう、四方へ [pad] だけ大きく取る。
 * 座標は親（コマ）の左上が原点のまま描ける。
 */
@Composable
internal fun TiltLayer(
    offset: () -> Offset,
    pad: Float = 0f,
    cache: Boolean = false,
    blendMode: BlendMode = BlendMode.SrcOver,
    alpha: Float = 1f,
    draw: DrawScope.() -> Unit,
) {
    val p = ceil(pad).toInt()
    Box(
        Modifier
            .layout { m, c ->
                val placeable = m.measure(Constraints.fixed(c.maxWidth + 2 * p, c.maxHeight + 2 * p))
                layout(c.maxWidth, c.maxHeight) { placeable.place(-p, -p) }
            }.graphicsLayer {
                val o = offset()
                translationX = o.x
                translationY = o.y
                this.alpha = alpha
                this.blendMode = blendMode
                if (cache) compositingStrategy = CompositingStrategy.Offscreen
            }.drawBehind { translate(p.toFloat(), p.toFloat()) { draw() } },
    )
}

/**
 * 傾きで動かない層。動かすための描画の層（graphicsLayer）を作らず、親（コマ）の描画の命令に直接描く。
 * 層の数が減り、傾きが変わっても親の命令は作り直さない（動くのは子の層の位置だけ）。
 */
@Composable
internal fun StaticLayer(draw: DrawScope.() -> Unit) {
    Box(Modifier.fillMaxSize().drawBehind(draw))
}

/**
 * いくつかの層をまとめて 1 枚に合成してから重ねる入れ物（文字を人物の形で抜く、輪郭の光を合成するなど）。
 * 中の層が動くと、この入れ物は毎フレーム合成し直す。
 */
@Composable
internal fun LayerGroup(
    blendMode: BlendMode = BlendMode.SrcOver,
    alpha: Float = 1f,
    /** 四方へ広げる量。合成した結果は入れ物の大きさで切れるので、外へ描く分（はみ出し）だけ広げる。 */
    pad: Float = 0f,
    content: @Composable () -> Unit,
) {
    val p = ceil(pad).toInt()
    Box(
        Modifier
            .layout { m, c ->
                val placeable = m.measure(Constraints.fixed(c.maxWidth + 2 * p, c.maxHeight + 2 * p))
                layout(c.maxWidth, c.maxHeight) { placeable.place(-p, -p) }
            }.graphicsLayer {
                this.alpha = alpha
                this.blendMode = blendMode
                compositingStrategy = CompositingStrategy.Offscreen
            },
    ) {
        // 中の層の原点を、親（コマ）の左上に戻す
        Box(
            Modifier.layout { m, c ->
                val placeable = m.measure(Constraints.fixed(c.maxWidth - 2 * p, c.maxHeight - 2 * p))
                layout(c.maxWidth, c.maxHeight) { placeable.place(p, p) }
            },
        ) { content() }
    }
}

/**
 * 人物の前の効果（影・縁取り・人物の描き直し・輪郭の光）を、層に分けて描く。
 * 影は位置だけが変わり、縁取りと人物は絵と一緒に動くだけなので、保存したテクスチャを動かす。
 * 輪郭の光は 2 つの人物の形のずれが光の向きで変わるので、毎フレーム合成する（中身は保存したまま）。
 * [extra] は層を広げる量（はみ出しの層ではその分だけ外に描く）。
 */
@Composable
internal fun PersonLayers(
    face: PanelFace,
    art: ImageBitmap,
    person: PersonMask,
    maps: DepthMaps?,
    depth: DepthStyle,
    artShift: () -> Offset,
    light: () -> Offset,
    parallax: Float,
    extra: Float = 0f,
    /** はみ出しの層では、影を短く薄くする（枠と間隔は人物に近いので）。 */
    shadowScale: Float = 1f,
    shadowAlpha: Float = 1f,
) {
    val spec = depth.spec
    val off = HomeDebug.off
    if (spec.shadow.enabled && "shadow" !in off) {
        val d = depth.shadowDistance * shadowScale
        // 影は平行移動するだけなので、描いた結果を保存して動かす（毎フレーム描き直さない）
        TiltLayer({ artShift() - light() * d }, pad = parallax + extra + d + 2f, cache = true) { drawPersonShadow(face, maps, spec.shadow.alpha * shadowAlpha) }
    }
    if (spec.redrawsPerson && "outline" !in off) {
        TiltLayer(artShift, pad = parallax + extra + 2f, cache = true) {
            if (spec.outline.enabled) drawPersonOutline(face, maps, spec.outline.alpha)
            drawPersonImage(face, art, person)
        }
    }
    if (spec.rim.enabled && "rim" !in off) {
        LayerGroup(blendMode = BlendMode.Screen, alpha = spec.rim.alpha, pad = extra) {
            TiltLayer(artShift, pad = parallax + extra + 2f, cache = true) { drawRimShape(face, person, RIM_FILTER) }
            TiltLayer({ artShift() - light() * depth.rimWidth }, pad = parallax + extra + depth.rimWidth + 2f, cache = true, blendMode = BlendMode.DstOut) {
                drawRimShape(face, person, null)
            }
        }
    }
}

private val RIM_FILTER = ColorFilter.tint(androidx.compose.ui.graphics.Color(0xFFFFF8E4), BlendMode.SrcIn)
