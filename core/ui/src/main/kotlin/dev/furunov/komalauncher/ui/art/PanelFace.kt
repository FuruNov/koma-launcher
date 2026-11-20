package dev.furunov.komalauncher.ui.art

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.furunov.komalauncher.model.geometry.Polygon
import dev.furunov.komalauncher.model.geometry.Rect
import dev.furunov.komalauncher.model.layout.Border
import dev.furunov.komalauncher.model.layout.BorderStyle
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.LayoutShape
import dev.furunov.komalauncher.model.layout.Look
import dev.furunov.komalauncher.model.layout.ToneMatrix
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** コマの見た目の指定（段階 3）。レイアウトと画面の幅から作る。 */
class PanelStyle(
    val look: Look,
    val border: Border,
    /** 画面の幅 / レイアウトの基準の幅。枠線の太さを画面に合わせるのに使う。 */
    val scale: Float,
) {
    companion object {
        val NONE = PanelStyle(Look(), Border(style = BorderStyle.NONE), 1f)

        fun of(
            layout: Layout,
            viewWidth: Float,
        ): PanelStyle {
            val tree = layout.shape as? LayoutShape.Tree ?: return NONE
            return PanelStyle(tree.look, tree.frame.border, viewWidth / tree.frame.refWidth)
        }
    }
}

/**
 * 1 つのコマの中身の描き方。ホーム（コマごとの層）と編集画面（1 枚のキャンバス）で同じ見た目にするため、
 * 座標はコマの外接長方形の左上を原点にして持つ。切り抜きは呼び出し側が行う（ホームは graphicsLayer の shape、
 * 編集画面は clipPath）。
 *
 * 描く順はモックアップと同じ: 絵 →（段階 4 の文字）→ 内側の影 → 光の反射 → 枠線。
 * 色の行列・影の Paint・反射のグラデーションは、ここで 1 回だけ作る。コマの形か見た目が変わったときだけ作り直す。
 */
class PanelFace(
    /** 画面座標のコマの形。 */
    polygon: Polygon,
    /** 画面座標の、画像を描く長方形。null なら仮の色を塗る。 */
    art: Rect?,
    val placeholder: Color,
    style: PanelStyle = PanelStyle.NONE,
    /** 絵の上に重ねる黒の不透明度（PanelArt.dim）。文字より前に塗るので、名前・時計は暗くならない。 */
    private val dim: Float = 0f,
) {
    val bounds: Rect = polygon.bounds

    /** [bounds] の左上を原点にしたコマの形。 */
    val path: Path =
        Path().apply {
            polygon.points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x - bounds.left, p.y - bounds.top) else lineTo(p.x - bounds.left, p.y - bounds.top) }
            close()
        }

    private val artOffset = art?.let { IntOffset((it.left - bounds.left).roundToInt(), (it.top - bounds.top).roundToInt()) }
    private val artSize = art?.let { IntSize(it.width.roundToInt().coerceAtLeast(1), it.height.roundToInt().coerceAtLeast(1)) }

    /** 淡い色合い。GPU が画素ごとに掛けるので、画像を作り直さない。 */
    private val toneFilter: ColorFilter? = ToneMatrix.of(style.look)?.let { ColorFilter.colorMatrix(ColorMatrix(it)) }

    /**
     * 内側の影。モックアップは SVG のぼかし（標準偏差 = 太さ × 0.45）を掛けた線をコマの形で切り抜く。
     * ここでは BlurMaskFilter で同じぼかしを掛ける（半径と標準偏差の関係は Skia の σ = 0.57735 r + 0.5）。
     */
    private val shadowPaint: Paint? =
        style.look.inner.takeIf { it > 0f }?.let { inner ->
            val sz = min(bounds.width, bounds.height) * 0.12f
            val sigma = sz * 0.45f
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.style = Paint.Style.STROKE
                strokeWidth = sz
                color = Color.Black.copy(alpha = (0.55f * inner).coerceIn(0f, 1f)).toArgb()
                if (sigma > 0.5f) maskFilter = BlurMaskFilter((sigma - 0.5f) / 0.57735f, BlurMaskFilter.Blur.NORMAL)
            }
        }
    private val androidPath = path.asAndroidPath()

    /** 光の反射。外接長方形の対角線に沿った細い帯を、画面の明るさに足す（スクリーン）。 */
    private val sheenBrush: Brush? =
        style.look.sheen.takeIf { it > 0f }?.let { s ->
            Brush.linearGradient(
                0f to Color.Transparent,
                0.38f to Color.Transparent,
                0.5f to Color.White.copy(alpha = 0.55f * s),
                0.62f to Color.Transparent,
                1f to Color.Transparent,
                start = Offset.Zero,
                end = Offset(bounds.width, bounds.height),
            )
        }

    /** 枠線はコマの内側に引く。線を 2 倍の太さで描き、コマの形で切り抜いて内側の半分だけを残す。 */
    private val borderWidth = if (style.border.style == BorderStyle.NONE) 0f else style.border.width * style.scale
    private val borderColor = Color(style.border.color)

    /** 二重の枠の内側の白線。モックアップと同じく、黒い線より max(4, 太さ × 0.35) だけ太く白を引く。 */
    private val innerWhiteWidth =
        if (style.border.style == BorderStyle.DOUBLE && borderWidth > 0f) (style.border.width + max(4f, style.border.width * 0.35f)) * style.scale else 0f

    /**
     * 画像（無ければ仮の色）を描く。絵の長方形はコマの外へはみ出すので、呼び出し側で切り抜いておく。
     * [shift] は視差で絵を動かす量。絵は動かす量の分だけ大きく置いてある（LayoutResolver の parallax）。
     */
    fun DrawScope.drawArt(
        bitmap: ImageBitmap?,
        shift: Offset = Offset.Zero,
    ) {
        if (bitmap != null && artOffset != null && artSize != null) {
            translate(shift.x, shift.y) { drawImage(bitmap, dstOffset = artOffset, dstSize = artSize, colorFilter = toneFilter) }
            // 絵の移動（視差）の外で塗る。動かしても暗い部分がずれないように
            if (dim > 0f) drawRect(Color.Black.copy(alpha = dim), size = Size(bounds.width, bounds.height))
        } else {
            drawRect(placeholder)
        }
    }

    /** 絵の上に重ねるもの（内側の影・光の反射・枠線）。[sheenShift] は光の反射をずらす量（段階 5 の視差）。 */
    fun DrawScope.drawOverlay(sheenShift: Offset = Offset.Zero) {
        shadowPaint?.let { p -> drawIntoCanvas { it.nativeCanvas.drawPath(androidPath, p) } }
        sheenBrush?.let { brush ->
            // グラデーションは座標に固定されるので、座標ごとずらして流す。途切れないよう、コマより大きい長方形に塗る
            translate(sheenShift.x, sheenShift.y) {
                drawRect(brush, topLeft = Offset(-bounds.width, -bounds.height), size = Size(bounds.width * 3f, bounds.height * 3f), blendMode = BlendMode.Screen)
            }
        }
        if (innerWhiteWidth > 0f) drawPath(path, Color.White, style = Stroke(width = innerWhiteWidth * 2f, join = StrokeJoin.Miter))
        if (borderWidth > 0f) drawPath(path, borderColor, style = Stroke(width = borderWidth * 2f, join = StrokeJoin.Miter))
    }

    /** 絵と重ねるものをまとめて描く。 */
    fun DrawScope.drawContent(bitmap: ImageBitmap?) {
        drawArt(bitmap)
        drawOverlay()
    }

    companion object {
        val placeholderColors: List<Color> =
            listOf(0xFFE57373, 0xFF64B5F6, 0xFF81C784, 0xFFFFB74D, 0xFFBA68C8, 0xFF4DB6AC, 0xFFF06292, 0xFF9575CD).map { Color(it) }

        fun placeholderFor(index: Int): Color = placeholderColors[index % placeholderColors.size]
    }
}
