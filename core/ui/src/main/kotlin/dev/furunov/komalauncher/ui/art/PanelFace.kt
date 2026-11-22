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

/** コマの見た目の指定。レイアウトと画面の幅から作る。 */
class PanelStyle(
    val look: Look,
    val border: Border,
    /** 画面の幅 / レイアウトの基準の幅。枠線の太さを画面に合わせるのに使う。 */
    val scale: Float,
    /** 枠線の色。自動のときはすき間の色から決めてある。 */
    val borderColor: Long = border.color ?: Border.BLACK,
    /** 絵の無いコマの塗り方。すき間の色から決めてある。 */
    val blank: BlankPage = BlankPage.DARK,
) {
    companion object {
        val NONE = PanelStyle(Look(), Border(style = BorderStyle.NONE), 1f)

        /** [defaultGutter] はレイアウトにすき間の色が無いときの色（設定の値）。枠の色を自動で決めるのに使う。 */
        fun of(
            layout: Layout,
            viewWidth: Float,
            defaultGutter: Long,
        ): PanelStyle {
            val tree = layout.shape as? LayoutShape.Tree ?: return NONE
            val frame = tree.frame
            val gutter = frame.gutterColor ?: defaultGutter
            return PanelStyle(tree.look, frame.border, viewWidth / frame.refWidth, frame.border.colorOn(gutter), BlankPage.on(gutter))
        }
    }
}

/**
 * 1 つのコマの中身の描き方。ホーム（コマごとの層）と編集画面（1 枚のキャンバス）で同じ見た目にするため、
 * 座標はコマの外接長方形の左上を原点にして持つ。切り抜きは呼び出し側が行う（ホームは graphicsLayer の shape、
 * 編集画面は clipPath）。
 *
 * 描く順はモックアップと同じ: 絵 →（文字）→ 内側の影 → 光の反射 → 枠線。
 * 色の行列・影の Paint・反射のグラデーションは、ここで 1 回だけ作る。コマの形か見た目が変わったときだけ作り直す。
 */
class PanelFace(
    /** 画面座標のコマの形。 */
    polygon: Polygon,
    /** 画面座標の、画像を描く長方形。null なら絵の無いコマとして、紙かベタにトーンを描く。 */
    art: Rect?,
    /** コマの番号。トーンの濃淡の向きと、マゼンタにする最初のコマを決める。 */
    index: Int,
    style: PanelStyle = PanelStyle.NONE,
    /** 絵の上に重ねる黒の不透明度（PanelArt.dim）。文字より前に塗るので、名前・時計は暗くならない。 */
    val dim: Float = 0f,
) {
    val bounds: Rect = polygon.bounds

    /** [bounds] の左上を原点にしたコマの形。 */
    val path: Path =
        Path().apply {
            polygon.points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x - bounds.left, p.y - bounds.top) else lineTo(p.x - bounds.left, p.y - bounds.top) }
            close()
        }

    /** 絵を描く長方形（[bounds] の左上が原点）。null なら絵を描かない。 */
    val artOffset = art?.let { IntOffset((it.left - bounds.left).roundToInt(), (it.top - bounds.top).roundToInt()) }
    val artSize = art?.let { IntSize(it.width.roundToInt().coerceAtLeast(1), it.height.roundToInt().coerceAtLeast(1)) }

    /** 淡い色合い。GPU が画素ごとに掛けるので、画像を作り直さない。コマに置くアプリのアイコンにも同じものを掛け、絵の中で浮かないようにする。色調が無ければ null。 */
    val toneFilter: ColorFilter? = ToneMatrix.of(style.look)?.let { ColorFilter.colorMatrix(ColorMatrix(it)) }

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
    private val borderColor = Color(style.borderColor)

    /** 二重の枠の内側の線の色。枠の色と反対の明るさにする（白い枠なら黒、黒い枠なら白）。 */
    private val innerLineColor = if (Border.isDark(style.borderColor)) Color.White else Color(Border.BLACK)

    /** 二重の枠の内側の線。モックアップと同じく、枠の線より max(4, 太さ × 0.35) だけ太く引く。 */
    private val innerLineWidth =
        if (style.border.style == BorderStyle.DOUBLE && borderWidth > 0f) (style.border.width + max(4f, style.border.width * 0.35f)) * style.scale else 0f

    /** 絵の無いコマの塗り方。 */
    val blank: BlankPage = style.blank

    /** 絵の無い白い紙のコマなら true。名前・時計を黒で描く。 */
    val inkText: Boolean = art == null && blank.light

    /** トーン。このオブジェクトはコマの形か見た目が変わると作り直されるので、ここで 1 回だけ作る。 */
    private val dots: BlankPage.Dots? = if (art == null) blank.dots(index, bounds.width, bounds.height, DOT_STEP * style.scale) else null

    /**
     * 画像（無ければ紙かベタとトーン）を描く。絵の長方形はコマの外へはみ出すので、呼び出し側で切り抜いておく。
     * [shift] は視差で絵を動かす量。絵は動かす量の分だけ大きく置いてある（LayoutResolver の parallax）。
     */
    fun DrawScope.drawArt(
        bitmap: ImageBitmap?,
        shift: Offset = Offset.Zero,
        /** ぼかした絵（立体感の C）。[blurAlpha] の割合で重ねる。暗さより前に描き、ぼかしても暗さが変わらないようにする。 */
        blurred: ImageBitmap? = null,
        blurAlpha: Float = 0f,
        /** false なら暗さを塗らない（ホームでは絵だけを動く層に描き、暗さは動かない層に塗る）。 */
        withDim: Boolean = true,
    ) {
        if (bitmap != null && artOffset != null && artSize != null) {
            translate(shift.x, shift.y) {
                drawImage(bitmap, dstOffset = artOffset, dstSize = artSize, colorFilter = toneFilter)
                if (blurred != null && blurAlpha > 0f) drawImage(blurred, dstOffset = artOffset, dstSize = artSize, alpha = blurAlpha, colorFilter = toneFilter)
            }
            // 絵の移動（視差）の外で塗る。動かしても暗い部分がずれないように
            if (withDim && dim > 0f) drawRect(Color.Black.copy(alpha = dim), size = Size(bounds.width, bounds.height))
        } else {
            // 絵の無いコマか、絵を読み込んでいる間
            drawRect(blank.paper)
            dots?.let { with(it) { draw() } }
        }
    }

    /** 絵の上に重ねるもの（内側の影・光の反射・枠線）。[sheenShift] は光の反射をずらす量（視差）。 */
    fun DrawScope.drawOverlay(sheenShift: Offset = Offset.Zero) {
        drawInnerShadow()
        translate(sheenShift.x, sheenShift.y) { drawSheen() }
        drawBorder()
    }

    /** 暗さ（PanelArt.dim）だけを塗る。 */
    fun DrawScope.drawDim() {
        if (dim > 0f) drawRect(Color.Black.copy(alpha = dim), size = Size(bounds.width, bounds.height))
    }

    fun DrawScope.drawInnerShadow() {
        shadowPaint?.let { p -> drawIntoCanvas { it.nativeCanvas.drawPath(androidPath, p) } }
    }

    /** 光の反射。ずらすときは呼び出し側で座標ごと動かす。 */
    fun DrawScope.drawSheen() {
        sheenBrush?.let { brush ->
            // グラデーションは座標に固定されるので、座標ごとずらして流す。途切れないよう、コマより大きい長方形に塗る
            drawRect(brush, topLeft = Offset(-bounds.width, -bounds.height), size = Size(bounds.width * 3f, bounds.height * 3f), blendMode = BlendMode.Screen)
        }
    }

    /** 枠の線だけを描く。コマの上に重ねる層（音楽プレイヤーの棒など）を、枠の後ろに見せたいときに、その層の上から描き直す。 */
    fun DrawScope.drawBorder() {
        if (innerLineWidth > 0f) drawPath(path, innerLineColor, style = Stroke(width = innerLineWidth * 2f, join = StrokeJoin.Miter))
        if (borderWidth > 0f) drawPath(path, borderColor, style = Stroke(width = borderWidth * 2f, join = StrokeJoin.Miter))
    }

    /** 絵と重ねるものをまとめて描く。 */
    fun DrawScope.drawContent(bitmap: ImageBitmap?) {
        drawArt(bitmap)
        drawOverlay()
    }

    companion object {
        /** トーンの間隔（基準の幅 1220 のときの画素）。 */
        private const val DOT_STEP = 16f
    }
}
