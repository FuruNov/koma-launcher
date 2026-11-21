package dev.furunov.komalauncher.ui.art

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.furunov.komalauncher.model.geometry.Polygon
import dev.furunov.komalauncher.model.layout.BorderStyle
import dev.furunov.komalauncher.model.layout.DepthSpec
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.LayoutShape
import dev.furunov.komalauncher.model.layout.MaskMath
import dev.furunov.komalauncher.model.layout.Tilt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 前景の人物の立体感（[DepthSpec]）を、この画面の画素に直したもの。
 * 長さはレイアウトの基準の幅で持っているので、画面の幅に合わせる。
 */
@Immutable
class DepthStyle(
    val spec: DepthSpec,
    /** 画面の幅 / 基準の幅。 */
    scale: Float,
    /** 枠線の太さ（画面の画素）。はみ出した人物は、枠線の内側の縁から外に描く。 */
    val borderWidth: Float,
) {
    val shadowDistance = spec.shadow.distance * scale
    val shadowBlur = spec.shadow.blur * scale
    val rimWidth = spec.rim.width * scale
    val outlineWidth = spec.outline.width * scale
    val popWidth = spec.pop.width * scale

    companion object {
        val NONE = DepthStyle(DepthSpec(), 1f, 0f)

        fun of(
            layout: Layout,
            viewWidth: Float,
        ): DepthStyle {
            val tree = layout.shape as? LayoutShape.Tree ?: return NONE
            val scale = viewWidth / tree.frame.refWidth
            val border = tree.frame.border
            val line =
                when (border.style) {
                    BorderStyle.NONE -> 0f
                    BorderStyle.SINGLE -> border.width
                    BorderStyle.DOUBLE -> border.width + max(4f, border.width * 0.35f)
                }
            return DepthStyle(tree.depth, scale, line * scale)
        }

        /**
         * 光の来る向き（光源への単位ベクトル）。左上から当たり、傾けると回る。
         * 光の反射（PanelFace の sheen）と同じく、傾きと逆へ流れるようにする。
         */
        fun lightDirection(t: Tilt): Offset {
            val angle = -PI * 0.72 - t.x * 0.9 + t.y * 0.4
            return Offset(cos(angle).toFloat(), sin(angle).toFloat())
        }
    }
}

/** 影と縁取りの形。切り抜きから作り、値（ぼかしの量・縁の太さ）が変わったときだけ作り直す。 */
class DepthMaps(
    val shadow: ImageBitmap?,
    val outline: ImageBitmap?,
)

/** 影と縁取りの形の注文。どちらも切り抜きの画素で数える。0 なら作らない。 */
data class DepthRequest(
    val shadowRadius: Int,
    val outlineRadius: Float,
)

/**
 * コマごとの注文。画像の id → 影のぼかしと縁の太さを、切り抜きの画素に直したもの。
 * 絵を描く大きさはコマごとに違うので、画面の画素の値を、絵の長方形の幅で切り抜きの画素に直す。
 * 同じ画像のコマが複数あるときは、最も大きく描くコマに合わせる。
 */
fun depthRequests(
    style: DepthStyle,
    panels: List<Pair<String, Float>>,
    masks: Map<String, PersonMask>,
): Map<String, DepthRequest> {
    val spec = style.spec
    if (!spec.shadow.enabled && !spec.outline.enabled) return emptyMap()
    val out = HashMap<String, DepthRequest>()
    for ((image, artWidth) in panels.sortedBy { it.second }) {
        val mask = masks[image] ?: continue
        val k = mask.width / artWidth.coerceAtLeast(1f)
        out[image] =
            DepthRequest(
                shadowRadius = if (spec.shadow.enabled) (style.shadowBlur * k).roundToInt().coerceAtLeast(1) else 0,
                // 0.5 画素ごとに丸め、スライダーを動かしている間に作り直す回数を減らす
                outlineRadius = if (spec.outline.enabled) ((style.outlineWidth * k) * 2f).roundToInt() / 2f else 0f,
            )
    }
    return out
}

/** [requests] の形を作る。作り終えるまでは前の形を使う（効果が一瞬消えないように）。 */
@Composable
fun rememberDepthMaps(
    masks: Map<String, PersonMask>,
    requests: Map<String, DepthRequest>,
): State<Map<String, DepthMaps>> = produceState(emptyMap(), masks, requests) {
    val made = HashMap(value.filterKeys { it in requests })
    for ((id, r) in requests) {
        val mask = masks[id] ?: continue
        made[id] =
            withContext(Dispatchers.Default) {
                DepthMaps(
                    shadow = if (r.shadowRadius > 0) alphaBitmap(MaskMath.boxBlur(mask.alpha, mask.width, mask.height, r.shadowRadius), mask.width, mask.height) else null,
                    outline = if (r.outlineRadius > 0f) alphaBitmap(MaskMath.dilate(mask.alpha, mask.width, mask.height, r.outlineRadius), mask.width, mask.height) else null,
                )
            }
        value = HashMap(made)
    }
    value = made
}

/** 白で、透明度が [alpha] の画像（GPU 側に置く）。 */
internal fun alphaBitmap(
    alpha: ByteArray,
    w: Int,
    h: Int,
): ImageBitmap {
    val px = IntArray(w * h) { ((alpha[it].toInt() and 0xFF) shl 24) or 0xFFFFFF }
    val soft = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    val hw = soft.copy(Bitmap.Config.HARDWARE, false)
    soft.recycle()
    return hw.asImageBitmap()
}

/**
 * ぼかした絵（立体感の C）。元の画像を 8 分の 1 の大きさで読み、箱形のぼかしを掛ける。
 * 描くときに絵の大きさへ広げるので、小さく読んでも見た目は変わらない。
 */
@Composable
fun rememberBlurredArts(
    paths: Map<String, String>,
    /** ぼかす画像の id。空なら何も読まない（C を切っているとき）。 */
    ids: Set<String>,
    revision: Long,
): State<Map<String, ImageBitmap>> = produceState(emptyMap(), paths, ids, revision) {
    val loaded = HashMap(value.filterKeys { it in ids })
    for (id in ids) {
        if (id in loaded) continue
        val path = paths[id] ?: continue
        val bitmap = withContext(Dispatchers.IO) { decodeBlurred(path) } ?: continue
        loaded[id] = bitmap
        value = HashMap(loaded)
    }
    value = loaded
}

private fun decodeBlurred(path: String): ImageBitmap? = try {
    val soft =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            d.setTargetSize((info.size.width / BLUR_DOWNSCALE).coerceAtLeast(8), (info.size.height / BLUR_DOWNSCALE).coerceAtLeast(8))
        }
    val w = soft.width
    val h = soft.height
    val px = IntArray(w * h)
    soft.getPixels(px, 0, w, 0, 0, w, h)
    soft.recycle()
    // 色ごとにぼかす（透明度はそのまま）
    val channels = (0..2).map { c -> MaskMath.boxBlur(ByteArray(px.size) { (px[it] ushr (16 - 8 * c)).toByte() }, w, h, BLUR_RADIUS) }
    val out = IntArray(px.size) { (px[it] and 0xFF000000.toInt()) or ((channels[0][it].toInt() and 0xFF) shl 16) or ((channels[1][it].toInt() and 0xFF) shl 8) or (channels[2][it].toInt() and 0xFF) }
    val blurred = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    val hw = blurred.copy(Bitmap.Config.HARDWARE, false)
    blurred.recycle()
    hw.asImageBitmap()
} catch (e: Exception) {
    Log.w("DepthFx", "$path を読めない", e)
    null
}

/** 元の画像に対する縮小の割合と、縮小した画像でのぼかしの半径。合わせて元の画像で約 20 画素のぼけになる。 */
private const val BLUR_DOWNSCALE = 8
private const val BLUR_RADIUS = 2

/** かすみの色（ToneMatrix の紙の色と同じ）。 */
private val HAZE = Color(0xFFF6F2E9)

/** 輪郭の光の色。わずかに暖かい白。 */
private val RIM = Color(0xFFFFF8E4)

private val tintBlack = ColorFilter.tint(Color.Black, BlendMode.SrcIn)
private val tintRim = ColorFilter.tint(RIM, BlendMode.SrcIn)

/**
 * 立体感の C のかすみ。背景（人物を描き直す前の絵）の上に紙の色を薄く塗る。
 * 座標はコマの外接長方形の左上が原点（PanelFace と同じ）。
 */
fun DrawScope.drawHaze(
    face: PanelFace,
    style: DepthStyle,
) {
    val b = style.spec.blur
    if (b.enabled && b.haze > 0f) drawRect(HAZE.copy(alpha = b.haze), size = Size(face.bounds.width, face.bounds.height))
}

/**
 * 人物の前の効果を描く。描く順は、影 → 縁取り → 人物の描き直し → 輪郭の光。
 * 影・ぼかし・かすみは背景と文字の上に掛けたので、人物を描き直して人物から外す。
 * 文字（時計・大見出し・名前）を描いた後、内側の影と枠線より前に呼ぶ。
 */
fun DrawScope.drawPersonFront(
    face: PanelFace,
    art: ImageBitmap,
    mask: PersonMask,
    maps: DepthMaps?,
    style: DepthStyle,
    shift: Offset,
    light: Offset,
) {
    val o = face.artOffset ?: return
    val s = face.artSize ?: return
    val spec = style.spec
    if (spec.shadow.enabled) drawShadow(maps?.shadow, o, s, shift - light * style.shadowDistance, spec.shadow.alpha)
    if (spec.outline.enabled) maps?.outline?.let { translate(shift.x, shift.y) { drawImage(it, dstOffset = o, dstSize = s, alpha = spec.outline.alpha) } }
    if (spec.redrawsPerson) drawPerson(face, art, mask, o, s, shift, androidx.compose.ui.geometry.Rect(0f, 0f, face.bounds.width, face.bounds.height))
    if (spec.rim.enabled) drawRim(mask, o, s, shift, light, style.rimWidth, spec.rim.alpha, Size(face.bounds.width, face.bounds.height))
}

/**
 * 枠の外へはみ出した人物（立体感の E）。枠線の内側の縁から、外へ [DepthStyle.popWidth] までの帯に描く。
 * 帯の外（コマの中）は [drawPersonFront] で描き終えている。座標はコマの外接長方形の左上が原点。
 */
fun DrawScope.drawPersonPop(
    face: PanelFace,
    polygon: Polygon,
    art: ImageBitmap,
    mask: PersonMask,
    maps: DepthMaps?,
    style: DepthStyle,
    shift: Offset,
    light: Offset,
) {
    val o = face.artOffset ?: return
    val s = face.artSize ?: return
    if (style.popWidth <= 0f) return
    val b = face.bounds
    val ring =
        Path().apply {
            fillType = PathFillType.EvenOdd
            addPolygon(polygon.offset(style.popWidth), b.left, b.top)
            addPolygon(polygon.offset(-style.borderWidth), b.left, b.top)
        }
    val spec = style.spec
    clipPath(ring) {
        // 絵の外には人物が無いので、絵の長方形でも切る（絵の端の外に影だけが出ないように）
        clipRect(o.x + shift.x, o.y + shift.y, o.x + s.width + shift.x, o.y + s.height + shift.y) {
            // 枠と間隔に落ちる影は、コマの中より近い（人物と枠の間が狭い）ので、短く薄くする
            if (spec.shadow.enabled) drawShadow(maps?.shadow, o, s, shift - light * style.shadowDistance * 0.6f, spec.shadow.alpha * 0.8f)
            if (spec.outline.enabled) maps?.outline?.let { translate(shift.x, shift.y) { drawImage(it, dstOffset = o, dstSize = s, alpha = spec.outline.alpha) } }
            drawPerson(face, art, mask, o, s, shift, androidx.compose.ui.geometry.Rect(-style.popWidth, -style.popWidth, b.width + style.popWidth, b.height + style.popWidth))
            if (spec.rim.enabled) drawRim(mask, o, s, shift, light, style.rimWidth, spec.rim.alpha, Size(b.width + 2 * style.popWidth, b.height + 2 * style.popWidth), Offset(-style.popWidth, -style.popWidth))
        }
    }
}

private fun Path.addPolygon(
    p: Polygon,
    originX: Float,
    originY: Float,
) {
    p.points.forEachIndexed { i, pt -> if (i == 0) moveTo(pt.x - originX, pt.y - originY) else lineTo(pt.x - originX, pt.y - originY) }
    close()
}

private fun DrawScope.drawShadow(
    shadow: ImageBitmap?,
    o: IntOffset,
    s: IntSize,
    at: Offset,
    alpha: Float,
) {
    shadow ?: return
    translate(at.x, at.y) { drawImage(shadow, dstOffset = o, dstSize = s, alpha = alpha, colorFilter = tintBlack) }
}

/** 人物だけを描き直す。絵を描き、切り抜きの「残す」形で人物以外を消す。暗さ（dim）も絵と同じに掛ける。 */
private fun DrawScope.drawPerson(
    face: PanelFace,
    art: ImageBitmap,
    mask: PersonMask,
    o: IntOffset,
    s: IntSize,
    shift: Offset,
    /** 描く範囲（コマの外接長方形の左上が原点）。絵の長方形はコマより大きいので、層をこの範囲に狭めて軽くする。 */
    limit: androidx.compose.ui.geometry.Rect,
) {
    val area = androidx.compose.ui.geometry.Rect(o.x + shift.x, o.y + shift.y, o.x + s.width + shift.x, o.y + s.height + shift.y).intersect(limit)
    if (area.isEmpty) return
    drawIntoCanvas { it.saveLayer(area, Paint()) }
    translate(shift.x, shift.y) {
        drawImage(art, dstOffset = o, dstSize = s, colorFilter = face.toneFilter)
        if (face.dim > 0f) drawRect(Color.Black.copy(alpha = face.dim), topLeft = Offset(o.x.toFloat(), o.y.toFloat()), size = Size(s.width.toFloat(), s.height.toFloat()))
        drawImage(mask.bitmap, dstOffset = o, dstSize = s, blendMode = BlendMode.DstOut)
    }
    drawIntoCanvas { it.restore() }
}

/**
 * 輪郭の光。人物の形から、光の側へ [width] だけずらした人物の形を消すと、光に向いた輪郭だけが残る。
 * 残った帯を、明るくする合成（スクリーン）で重ねる。[area] は層の大きさ、[origin] はその左上。
 */
private fun DrawScope.drawRim(
    mask: PersonMask,
    o: IntOffset,
    s: IntSize,
    shift: Offset,
    light: Offset,
    width: Float,
    alpha: Float,
    area: Size,
    origin: Offset = Offset.Zero,
) {
    val layer = Paint().apply {
        blendMode = BlendMode.Screen
        this.alpha = alpha
    }
    drawIntoCanvas { it.saveLayer(androidx.compose.ui.geometry.Rect(origin, area), layer) }
    translate(shift.x, shift.y) { drawImage(mask.person, dstOffset = o, dstSize = s, colorFilter = tintRim) }
    val back = shift - light * width
    translate(back.x, back.y) { drawImage(mask.person, dstOffset = o, dstSize = s, blendMode = BlendMode.DstOut) }
    drawIntoCanvas { it.restore() }
}

// ここから下は、ホームの層ごとの描き方（傾きを読まず、ずらしは層の位置で付ける）。座標はコマの外接長方形の左上が原点

/** 人物の影（黒）を、絵と同じ置き場所に描く。 */
fun DrawScope.drawPersonShadow(
    face: PanelFace,
    maps: DepthMaps?,
    alpha: Float,
) {
    val o = face.artOffset ?: return
    val s = face.artSize ?: return
    drawShadow(maps?.shadow, o, s, Offset.Zero, alpha)
}

/** 人物の縁取り（白）。 */
fun DrawScope.drawPersonOutline(
    face: PanelFace,
    maps: DepthMaps?,
    alpha: Float,
) {
    val o = face.artOffset ?: return
    val s = face.artSize ?: return
    maps?.outline?.let { drawImage(it, dstOffset = o, dstSize = s, alpha = alpha) }
}

/** 人物だけの絵。 */
fun DrawScope.drawPersonImage(
    face: PanelFace,
    art: ImageBitmap,
    mask: PersonMask,
) {
    val o = face.artOffset ?: return
    val s = face.artSize ?: return
    drawPerson(face, art, mask, o, s, Offset.Zero, androidx.compose.ui.geometry.Rect(o.x.toFloat(), o.y.toFloat(), (o.x + s.width).toFloat(), (o.y + s.height).toFloat()))
}

/** 人物の形。[filter] で色を付ける（輪郭の光に使う）。 */
fun DrawScope.drawRimShape(
    face: PanelFace,
    mask: PersonMask,
    filter: ColorFilter?,
) {
    val o = face.artOffset ?: return
    val s = face.artSize ?: return
    drawImage(mask.person, dstOffset = o, dstSize = s, colorFilter = filter)
}
