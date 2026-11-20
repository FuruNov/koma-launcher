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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import dev.furunov.komalauncher.core.ui.R
import dev.furunov.komalauncher.model.geometry.Rect
import dev.furunov.komalauncher.model.layout.LabelMode
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.LayoutShape
import dev.furunov.komalauncher.model.layout.MaskMath
import dev.furunov.komalauncher.model.layout.PanelId
import dev.furunov.komalauncher.model.layout.PanelText
import dev.furunov.komalauncher.model.layout.ResolvedPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import kotlin.math.roundToInt

/** 書体。どちらも SIL Open Font License（licenses/ に全文）。 */
object KomaFonts {
    /** 時計の数字と日本語の名前。 */
    val dela = FontFamily(Font(R.font.dela_gothic_one))

    /** 大見出しと英語の名前。可変の書体なので、太さ 800 を指定して読む。 */
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    val bodoniItalic =
        FontFamily(
            Font(
                R.font.bodoni_moda_italic,
                weight = FontWeight.ExtraBold,
                style = FontStyle.Italic,
                variationSettings = FontVariation.Settings(FontVariation.weight(800)),
            ),
        )
}

/** 人物の切り抜き。描くための画像と、名前の置き場所を探すための粗い地図（1 が人物）。 */
@Immutable
class PersonMask(
    val bitmap: ImageBitmap,
    val grid: FloatArray,
) {
    companion object {
        const val COLUMNS = 64
        const val ROWS = 36
    }
}

/** 切り抜きを読む。透明度だけを使うので、地図を作ってから GPU 側の画像にする。 */
@Composable
fun rememberMasks(
    paths: Map<String, String>,
    revision: Long,
): State<Map<String, PersonMask>> = produceState(emptyMap(), paths, revision) {
    value = withContext(Dispatchers.IO) { paths.mapNotNull { (id, path) -> decodeMask(path)?.let { id to it } }.toMap() }
}

private fun decodeMask(path: String): PersonMask? = try {
    val soft = ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
    val w = soft.width
    val h = soft.height
    val px = IntArray(w * h)
    soft.getPixels(px, 0, w, 0, 0, w, h)
    val keep = FloatArray(px.size) { ((px[it] ushr 24) and 0xFF) / 255f }
    val grid = MaskMath.coverGrid(keep, w, h, PersonMask.COLUMNS, PersonMask.ROWS)
    val hw = soft.copy(Bitmap.Config.HARDWARE, false)
    soft.recycle()
    PersonMask(hw.asImageBitmap(), grid)
} catch (e: Exception) {
    Log.w("PersonMask", "$path を読めない", e)
    null
}

/** コマの名前 1 つ。座標はコマの外接長方形の左上が原点。 */
@Immutable
data class LabelText(
    val text: String,
    val centerX: Float,
    val centerY: Float,
    val size: Float,
    val vertical: Boolean,
    val english: Boolean,
    /** 文字列の長さの上限（縦書きの英語で、コマに収まらないとき縮める）。 */
    val maxLength: Float,
)

/** 大見出し。幅を [width] に合わせて横に伸縮する。 */
@Immutable
data class MastheadText(
    val text: String,
    val centerX: Float,
    val centerY: Float,
    val size: Float,
    val width: Float,
)

/** ランチャーが描く時計。[k] は大きさ（モックアップの値 × 画面の幅 / 1220）。 */
@Immutable
data class ClockText(
    val centerX: Float,
    val centerY: Float,
    val k: Float,
)

/** 1 つのコマに描く文字。 */
@Immutable
data class PanelTextSpec(
    val labels: List<LabelText> = emptyList(),
    val masthead: MastheadText? = null,
    val clock: ClockText? = null,
) {
    val isEmpty: Boolean get() = labels.isEmpty() && masthead == null && clock == null
}

/** 文字の層の Paint。描くたびに作らないよう 1 つを使い回す（中身は変えない）。 */
private val layerPaint = Paint()

private val WEEKDAYS = listOf("月", "火", "水", "木", "金", "土", "日")

/** 時計の表示（モックアップの timeText）。 */
fun clockStrings(now: LocalDateTime): Pair<String, String> = "${now.hour}:${now.minute.toString().padStart(2, '0')}" to "${now.monthValue}月${now.dayOfMonth}日（${WEEKDAYS[now.dayOfWeek.value - 1]}）"

/**
 * 文字を描く。切り抜き [mask] があれば、文字だけの層を作り、人物の形を抜いた切り抜きで切ってから重ねる（人物の後ろに見える）。
 * 人物の画像を文字の上に重ねる作りにしないのは、切り抜きの縁の半透明から文字が透け、視差で動かしたときにちらつくため（モックアップと同じ判断）。
 * [maskOffset]・[maskSize] は絵と同じ置き場所（コマの左上が原点）。[textShift] は文字を動かす量（段階 5 の視差）。
 */
fun DrawScope.drawPanelTexts(
    spec: PanelTextSpec,
    /** コマの外接長方形の大きさ。 */
    area: Size,
    measurer: TextMeasurer,
    now: LocalDateTime,
    mask: ImageBitmap?,
    maskOffset: IntOffset?,
    maskSize: IntSize?,
    textShift: Offset = Offset.Zero,
    clockShift: Offset = Offset.Zero,
    /** 視差で絵を動かす量。切り抜きは絵と一緒に動かさないと、人物の形とずれる。 */
    artShift: Offset = Offset.Zero,
) {
    if (spec.isEmpty) return
    val behind = mask != null && maskOffset != null && maskSize != null
    // 層はコマの外接長方形の大きさ（編集画面では縮小図のキャンバスより大きいことがあるので、size は使わない）
    if (behind) drawIntoCanvas { it.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, area), layerPaint) }
    translate(textShift.x, textShift.y) {
        spec.labels.forEach { drawLabel(it, measurer) }
        spec.masthead?.let { drawMasthead(it, measurer) }
    }
    spec.clock?.let { translate(clockShift.x, clockShift.y) { drawClock(it, measurer, now) } }
    if (behind) {
        translate(artShift.x, artShift.y) { drawImage(mask!!, dstOffset = maskOffset!!, dstSize = maskSize!!, blendMode = BlendMode.DstIn) }
        drawIntoCanvas { it.restore() }
    }
}

/** モックアップの textStyle: 白い塗りに細い暗い縁と影。淡い絵の上でも読めるように。 */
private fun DrawScope.drawOutlined(
    layout: TextLayoutResult,
    topLeft: Offset,
    size: Float,
) {
    drawText(layout, color = Color.Black.copy(alpha = 0.45f), topLeft = topLeft, drawStyle = Stroke(width = maxOf(2f, size * 0.03f) * 2f), alpha = 0.88f)
    drawText(layout, color = Color.White, topLeft = topLeft, shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 4f), 10f), alpha = 0.88f)
}

private fun DrawScope.px(v: Float): TextUnit = v.toSp()

private fun DrawScope.drawLabel(
    l: LabelText,
    measurer: TextMeasurer,
) {
    val family = if (l.english) KomaFonts.bodoniItalic else KomaFonts.dela
    val style = TextStyle(fontFamily = family, fontSize = px(l.size), fontWeight = if (l.english) FontWeight.ExtraBold else null, fontStyle = if (l.english) FontStyle.Italic else null)
    if (l.vertical && !l.english) {
        // 日本語の縦書きは 1 文字ずつ縦に積む
        val chars = l.text.map { it.toString() }
        val y0 = l.centerY - l.size * 1.05f * (chars.size - 1) / 2f
        chars.forEachIndexed { i, ch ->
            val m = measurer.measure(ch, style)
            drawOutlined(m, Offset(l.centerX - m.size.width / 2f, y0 + i * l.size * 1.05f - m.size.height / 2f), l.size)
        }
        return
    }
    val m = measurer.measure(l.text, style)
    val w = m.size.width.toFloat()
    // 長すぎるときだけ文字の並びの向きに縮める（SVG の textLength と同じ）
    val squeeze = if (w > l.maxLength && w > 0f) l.maxLength / w else 1f
    val center = Offset(l.centerX, l.centerY)
    val draw: DrawScope.() -> Unit = {
        scale(squeeze, 1f, pivot = center) { drawOutlined(m, Offset(l.centerX - w / 2f, l.centerY - m.size.height / 2f), l.size) }
    }
    if (l.vertical) rotate(90f, pivot = center, block = draw) else draw()
}

private fun DrawScope.drawMasthead(
    t: MastheadText,
    measurer: TextMeasurer,
) {
    val m = measurer.measure(t.text, TextStyle(fontFamily = KomaFonts.bodoniItalic, fontSize = px(t.size), fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic))
    val w = m.size.width.toFloat().coerceAtLeast(1f)
    // 書体ごとに字の幅が違うので、文字列の幅をコマの幅の 9 割に合わせる
    scale(t.width / w, 1f, pivot = Offset(t.centerX, t.centerY)) {
        drawOutlined(m, Offset(t.centerX - w / 2f, t.centerY - m.size.height / 2f), t.size)
    }
}

private fun DrawScope.drawClock(
    c: ClockText,
    measurer: TextMeasurer,
    now: LocalDateTime,
) {
    val (hm, date) = clockStrings(now)
    val time =
        measurer.measure(
            hm,
            TextStyle(fontFamily = KomaFonts.dela, fontSize = px(330f * c.k), letterSpacing = px(-6f * c.k), color = Color.White),
        )
    val day = measurer.measure(date, TextStyle(fontSize = px(54f * c.k), fontWeight = FontWeight.Bold, color = Color.White))
    drawText(day, topLeft = Offset(c.centerX - day.size.width / 2f, c.centerY - 210f * c.k - day.size.height / 2f), shadow = Shadow(Color.Black.copy(alpha = 0.4f), Offset(0f, 3f * c.k), 6f * c.k))
    drawText(time, topLeft = Offset(c.centerX - time.size.width / 2f, c.centerY - time.size.height / 2f), shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 6f * c.k), 10f * c.k))
}

/** 名前の置き場所を探すときに使う、人物の割合の関数。地図か絵の置き場所が無ければ null。 */
fun coverFunction(
    mask: PersonMask?,
    art: Rect?,
): ((Rect) -> Float)? {
    if (mask == null || art == null) return null
    return { r -> PanelText.cover(mask.grid, PersonMask.COLUMNS, PersonMask.ROWS, art, r) }
}

/** [IntOffset] と [IntSize] を、画面座標の長方形からコマの左上を原点にして作る。 */
fun localPlacement(
    bounds: Rect,
    art: Rect,
): Pair<IntOffset, IntSize> = IntOffset((art.left - bounds.left).roundToInt(), (art.top - bounds.top).roundToInt()) to IntSize(art.width.roundToInt().coerceAtLeast(1), art.height.roundToInt().coerceAtLeast(1))

/**
 * コマごとに描く文字を決める（モックアップの render の文字の部分）。
 * - 名前: フォルダと全アプリ一覧のコマ。時計のコマには出さない（数字と重なるため）
 * - 大見出し: 指定のコマ（無ければ時計のコマ）
 * - 時計: 指定のコマ（無ければいちばん大きいコマ）
 * 座標はコマの外接長方形の左上が原点。[names] は利用者が付けたフォルダの名前。
 */
fun buildPanelTexts(
    layout: Layout,
    panels: List<ResolvedPanel>,
    viewWidth: Float,
    names: Map<String, String>,
    masks: Map<String, PersonMask>,
): Map<PanelId, PanelTextSpec> {
    val tree = layout.shape as? LayoutShape.Tree ?: return emptyMap()
    val text = tree.text
    val clockId = PanelText.clockPanel(panels, tree.clock)
    val mastheadId = PanelText.mastheadPanel(panels, text, tree.clock)
    val k = tree.clock.size * viewWidth / tree.frame.refWidth
    return panels.associate { p ->
        val b = p.polygon.bounds
        val labels =
            if (text.label == LabelMode.NONE || p.panel.id == clockId) {
                emptyList()
            } else {
                val label = PanelText.labelOf(p.panel, names[p.panel.id.value], text.english)
                if (label == null) {
                    emptyList()
                } else {
                    // 斜めのコマでも名前がコマの外に出ないよう、内接する長方形を基準にする
                    val box = p.polygon.largestInscribedRect(32)
                    val vertical = text.label == LabelMode.VERTICAL
                    val mask = p.panel.art?.image?.let(masks::get)
                    val pl = PanelText.placeLabel(box, label.length, vertical, text.english, coverFunction(mask, p.art))
                    val maxLength = if (vertical) box.height * 0.7f else box.width * 0.8f
                    listOf(LabelText(label, pl.centerX - b.left, pl.centerY - b.top, pl.size, vertical, text.english, maxLength))
                }
            }
        val masthead =
            if (p.panel.id == mastheadId) {
                MastheadText(text.masthead.trim(), b.width / 2f, text.mastheadY * b.height, PanelText.mastheadSize(b), b.width * 0.9f)
            } else {
                null
            }
        val clock = if (p.panel.id == clockId) ClockText(tree.clock.x * b.width, tree.clock.y * b.height, k) else null
        p.panel.id to PanelTextSpec(labels, masthead, clock)
    }
}
