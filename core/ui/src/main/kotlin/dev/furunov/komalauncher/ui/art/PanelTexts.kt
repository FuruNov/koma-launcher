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
import dev.furunov.komalauncher.model.geometry.Point
import dev.furunov.komalauncher.model.geometry.Polygon
import dev.furunov.komalauncher.model.geometry.Rect
import dev.furunov.komalauncher.model.layout.BuiltinWidget
import dev.furunov.komalauncher.model.layout.DateFormat
import dev.furunov.komalauncher.model.layout.LabelMode
import dev.furunov.komalauncher.model.layout.LayerKind
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.LayoutShape
import dev.furunov.komalauncher.model.layout.MaskMath
import dev.furunov.komalauncher.model.layout.PanelId
import dev.furunov.komalauncher.model.layout.PanelText
import dev.furunov.komalauncher.model.layout.ResolvedPanel
import dev.furunov.komalauncher.model.layout.TextColor
import dev.furunov.komalauncher.model.layout.WidgetFonts
import dev.furunov.komalauncher.model.layout.WidgetStyle
import dev.furunov.komalauncher.model.widgets.BuiltinWidgets
import dev.furunov.komalauncher.ui.icons.SHADOW_ALPHA
import dev.furunov.komalauncher.ui.icons.SHADOW_PAD
import dev.furunov.komalauncher.ui.icons.SHADOW_SHIFT
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
    /** 「残す」形（白で、透明度が人物以外の 1）。文字を人物の後ろに回すのに使う。 */
    val bitmap: ImageBitmap,
    val grid: FloatArray,
    /** 人物の形（白で、透明度が人物の 1）。立体感の効果（輪郭の光など）に使う。 */
    val person: ImageBitmap,
    /** 人物の濃さ（0〜255、[width]×[height]）。影と縁取りの形を作るのに使う。 */
    val alpha: ByteArray,
    val width: Int,
    val height: Int,
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
    val alpha = MaskMath.personFromKeep(ByteArray(px.size) { (px[it] ushr 24).toByte() })
    PersonMask(hw.asImageBitmap(), grid, alphaBitmap(alpha, w, h), alpha, w, h)
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
    /** 内蔵のテキストのウィジェットの見た目。既定は以前の大見出しと同じ。 */
    val style: WidgetStyle = WidgetStyle(),
)

/** ランチャーが描く時計。[k] は大きさ（モックアップの値 × 画面の幅 / 1220）。 */
@Immutable
data class ClockText(
    val centerX: Float,
    val centerY: Float,
    val k: Float,
    /** はみ出さずに描ける範囲。収まらなければ、中心を保ったまま縮めて描く。null なら縮めない。 */
    val limit: Rect? = null,
    /** 内蔵の時計のウィジェットの見た目。既定は以前の時計と同じ。 */
    val style: WidgetStyle = WidgetStyle(),
)

/** 1 つのコマに描く文字。 */
@Immutable
data class PanelTextSpec(
    val labels: List<LabelText> = emptyList(),
    /** 大見出し（内蔵のテキストのウィジェットのうち、このコマにかかるもの）。 */
    val mastheads: List<MastheadText> = emptyList(),
    /** 時計（内蔵の時計のウィジェットのうち、このコマにかかるもの）。 */
    val clocks: List<ClockText> = emptyList(),
    /** フォルダの先頭から順に並べるアイコンの場所。座標はコマの外接長方形の左上が原点。 */
    val icons: List<Rect> = emptyList(),
) {
    /** 文字が無いか（アイコンは文字の層では描かないので数えない）。 */
    val isEmpty: Boolean get() = labels.isEmpty() && mastheads.isEmpty() && clocks.isEmpty()
}

/** [kind] の部分だけを残した指定。重ね順どおりに 1 つずつ描くのに使う。 */
fun PanelTextSpec.only(kind: LayerKind): PanelTextSpec = when (kind) {
    LayerKind.ICONS -> PanelTextSpec(icons = icons)
    LayerKind.LABEL -> PanelTextSpec(labels = labels)
    LayerKind.MASTHEAD -> PanelTextSpec(mastheads = mastheads)
    LayerKind.CLOCK -> PanelTextSpec(clocks = clocks)
    LayerKind.PERSON -> PanelTextSpec()
}

/** 文字の層の Paint。描くたびに作らないよう 1 つを使い回す（中身は変えない）。 */
private val layerPaint = Paint()

private val WEEKDAYS = listOf("月", "火", "水", "木", "金", "土", "日")

/** 時計の表示（モックアップの timeText）。既定の [style] では「9:05」と「11月22日（日）」。 */
fun clockStrings(
    now: LocalDateTime,
    style: WidgetStyle = WidgetStyle(),
): Pair<String, String> {
    val hour = if (style.h24) now.hour else (now.hour % 12).let { if (it == 0) 12 else it }
    val time = "$hour:${now.minute.toString().padStart(2, '0')}" + if (style.seconds) ":${now.second.toString().padStart(2, '0')}" else ""
    val ampm = if (style.h24) {
        ""
    } else if (now.hour < 12) {
        "午前"
    } else {
        "午後"
    }
    val week = WEEKDAYS[now.dayOfWeek.value - 1]
    val date =
        when (style.date) {
            DateFormat.LONG -> "${now.monthValue}月${now.dayOfMonth}日（$week）"
            DateFormat.SHORT -> "${now.monthValue}/${now.dayOfMonth} $week"
            DateFormat.NONE -> ""
        }
    return time to listOf(ampm, date).filter { it.isNotEmpty() }.joinToString(" ")
}

/** 内蔵のウィジェットの書体。端末の書体は、端末によって形が違う。 */
fun widgetFontFamily(id: String): FontFamily = when (id) {
    WidgetFonts.BODONI -> KomaFonts.bodoniItalic
    WidgetFonts.SANS -> FontFamily.SansSerif
    WidgetFonts.SERIF -> FontFamily.Serif
    WidgetFonts.MONO -> FontFamily.Monospace
    WidgetFonts.CONDENSED -> FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL))
    else -> KomaFonts.dela
}

/** 選べる色のうちのアクセントの色（ランチャーの画面の強調の色と同じ）。 */
private val ACCENT = Color(0xFFFFD54F)

/** 文字の色。自動なら、白い紙のコマの上では墨、それ以外は白（以前の時計と大見出しと同じ）。 */
private fun WidgetStyle.textColor(ink: Boolean): Color = when (color) {
    TextColor.AUTO -> if (ink) BlankPage.INK else Color.White
    TextColor.WHITE -> Color.White
    TextColor.BLACK -> BlankPage.INK
    TextColor.ACCENT -> ACCENT
}

/** 影を付けるか。墨の文字には付けない（以前と同じく、白い紙の上の文字には影を付けない）。 */
private fun WidgetStyle.withShadow(ink: Boolean): Boolean = shadow && textColor(ink) != BlankPage.INK

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
    /** 大見出しとアイコンを動かす量（視差）。指定が無ければ名前と同じ量。 */
    mastheadShift: Offset = textShift,
    iconShift: Offset = textShift,
    /** 視差で絵を動かす量。切り抜きは絵と一緒に動かさないと、人物の形とずれる。 */
    artShift: Offset = Offset.Zero,
    /** アイコンの画像（[PanelTextSpec.icons] と同じ順。読み込み中は null）と、掛ける色調。文字と同じ層で描き、人物の後ろに回す。 */
    icons: List<ImageBitmap?> = emptyList(),
    iconFilter: ColorFilter? = null,
    /** アイコンの影の画像（[icons] と同じ順）と、影をずらす向き（光の向きと反対の単位ベクトル）。影の無い設定なら空。 */
    iconShadows: List<ImageBitmap?> = emptyList(),
    shadowDir: Offset = Offset(0.64f, 0.77f),
    /** true なら白い紙のコマ（BlankPage.LIGHT）の上なので、名前・大見出し・時計を墨で描く。 */
    ink: Boolean = false,
) {
    val shownIcons = spec.icons.zip(icons).withIndex().filter { it.value.second != null }
    if (spec.isEmpty && shownIcons.isEmpty()) return
    val behind = mask != null && maskOffset != null && maskSize != null
    // 層はコマの外接長方形の大きさ（編集画面では縮小図のキャンバスより大きいことがあるので、size は使わない）
    if (behind) drawIntoCanvas { it.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, area), layerPaint) }
    translate(iconShift.x, iconShift.y) {
        shownIcons.forEach { (i, pair) ->
            val (r, bitmap) = pair
            // 影はアイコンの直前に描く（人物の後ろに回すときも、アイコンと一緒に抜かれる）
            iconShadows.getOrNull(i)?.let { sh ->
                val pad = r.width * SHADOW_PAD
                val d = r.width * SHADOW_SHIFT
                drawImage(
                    sh,
                    dstOffset = IntOffset((r.left - pad + shadowDir.x * d).roundToInt(), (r.top - pad + shadowDir.y * d).roundToInt()),
                    dstSize = IntSize((r.width + pad * 2).roundToInt(), (r.height + pad * 2).roundToInt()),
                    alpha = SHADOW_ALPHA,
                )
            }
            drawImage(
                bitmap!!,
                dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()),
                alpha = ICON_ALPHA,
                colorFilter = iconFilter,
            )
        }
    }
    translate(textShift.x, textShift.y) { spec.labels.forEach { drawLabel(it, measurer, ink) } }
    spec.mastheads.forEach { translate(mastheadShift.x, mastheadShift.y) { drawMasthead(it, measurer, ink) } }
    spec.clocks.forEach { translate(clockShift.x, clockShift.y) { drawClock(it, measurer, now, ink) } }
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
    ink: Boolean,
) {
    if (ink) {
        // 白い紙の上では、縁と影を付けずに墨で描く
        drawText(layout, color = BlankPage.INK, topLeft = topLeft)
        return
    }
    drawText(layout, color = Color.Black.copy(alpha = 0.45f), topLeft = topLeft, drawStyle = Stroke(width = maxOf(2f, size * 0.03f) * 2f), alpha = 0.88f)
    drawText(layout, color = Color.White, topLeft = topLeft, shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 4f), 10f), alpha = 0.88f)
}

/** [drawOutlined] の色を選べる形。[outline] が false なら縁と影を付けずに塗るだけ。 */
private fun DrawScope.drawOutlined(
    layout: TextLayoutResult,
    topLeft: Offset,
    size: Float,
    color: Color,
    outline: Boolean,
) {
    if (!outline) {
        drawText(layout, color = color, topLeft = topLeft)
        return
    }
    drawText(layout, color = Color.Black.copy(alpha = 0.45f), topLeft = topLeft, drawStyle = Stroke(width = maxOf(2f, size * 0.03f) * 2f), alpha = 0.88f)
    drawText(layout, color = color, topLeft = topLeft, shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 4f), 10f), alpha = 0.88f)
}

private fun DrawScope.px(v: Float): TextUnit = v.toSp()

private fun DrawScope.drawLabel(
    l: LabelText,
    measurer: TextMeasurer,
    ink: Boolean,
) {
    val family = if (l.english) KomaFonts.bodoniItalic else KomaFonts.dela
    val style = TextStyle(fontFamily = family, fontSize = px(l.size), fontWeight = if (l.english) FontWeight.ExtraBold else null, fontStyle = if (l.english) FontStyle.Italic else null)
    if (l.vertical && !l.english) {
        // 日本語の縦書きは 1 文字ずつ縦に積む
        val chars = l.text.map { it.toString() }
        val y0 = l.centerY - l.size * 1.05f * (chars.size - 1) / 2f
        chars.forEachIndexed { i, ch ->
            val m = measurer.measure(ch, style)
            drawOutlined(m, Offset(l.centerX - m.size.width / 2f, y0 + i * l.size * 1.05f - m.size.height / 2f), l.size, ink)
        }
        return
    }
    val m = measurer.measure(l.text, style)
    val w = m.size.width.toFloat()
    // 長すぎるときだけ文字の並びの向きに縮める（SVG の textLength と同じ）
    val squeeze = if (w > l.maxLength && w > 0f) l.maxLength / w else 1f
    val center = Offset(l.centerX, l.centerY)
    val draw: DrawScope.() -> Unit = {
        scale(squeeze, 1f, pivot = center) { drawOutlined(m, Offset(l.centerX - w / 2f, l.centerY - m.size.height / 2f), l.size, ink) }
    }
    if (l.vertical) rotate(90f, pivot = center, block = draw) else draw()
}

private fun DrawScope.drawMasthead(
    t: MastheadText,
    measurer: TextMeasurer,
    ink: Boolean,
) {
    val s = t.style
    val font = s.font ?: WidgetFonts.BODONI
    // 既定は以前の大見出しと同じ（Bodoni Moda の斜体、太さ 800）
    val weight = if (WidgetFonts.hasWeights(font)) FontWeight(s.weight ?: 800) else FontWeight.ExtraBold
    val italic = s.italic ?: (font == WidgetFonts.BODONI)
    val m = measurer.measure(t.text, TextStyle(fontFamily = widgetFontFamily(font), fontSize = px(t.size), fontWeight = weight, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal))
    val w = m.size.width.toFloat().coerceAtLeast(1f)
    // 書体ごとに字の幅が違うので、文字列の幅を枠の幅に合わせる
    scale(t.width / w, 1f, pivot = Offset(t.centerX, t.centerY)) {
        val topLeft = Offset(t.centerX - w / 2f, t.centerY - m.size.height / 2f)
        if (s.color == TextColor.AUTO && s.shadow) {
            drawOutlined(m, topLeft, t.size, ink)
        } else {
            drawOutlined(m, topLeft, t.size, color = s.textColor(ink), outline = s.withShadow(ink))
        }
    }
}

private fun DrawScope.drawClock(
    c: ClockText,
    measurer: TextMeasurer,
    now: LocalDateTime,
    ink: Boolean,
) {
    val st = c.style
    val (hm, date) = clockStrings(now, st)
    // 既定では、白い紙の上では墨で描き、影を付けない
    val color = st.textColor(ink)
    val shadow = st.withShadow(ink)
    val font = st.font ?: WidgetFonts.DELA
    val weight = if (WidgetFonts.hasWeights(font)) FontWeight(st.weight ?: 700) else null
    // 秒を出すときは文字が増えるので、時計の幅に収まるよう小さくする
    val timeSize = if (st.seconds) 250f else 330f
    val time =
        measurer.measure(
            hm,
            TextStyle(fontFamily = widgetFontFamily(font), fontSize = px(timeSize * c.k), fontWeight = weight, letterSpacing = px(-6f * c.k), color = color),
        )
    val day = date.takeIf { it.isNotEmpty() }?.let { measurer.measure(it, TextStyle(fontSize = px(54f * c.k), fontWeight = FontWeight.Bold, color = color)) }
    // 範囲に収まらなければ、中心を保ったまま縮める（コマの縁にかからないように）
    val s = c.limit?.let { fitScale(c, it, time.size.width / 2f, (day?.size?.width ?: 0) / 2f, time.size.height, day?.size?.height ?: 0) } ?: 1f
    scale(s, pivot = Offset(c.centerX, c.centerY)) {
        day?.let { drawText(it, topLeft = Offset(c.centerX - it.size.width / 2f, c.centerY - 210f * c.k - it.size.height / 2f), shadow = if (shadow) Shadow(Color.Black.copy(alpha = 0.4f), Offset(0f, 3f * c.k), 6f * c.k) else null) }
        drawText(time, topLeft = Offset(c.centerX - time.size.width / 2f, c.centerY - time.size.height / 2f), shadow = if (shadow) Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 6f * c.k), 10f * c.k) else null)
    }
}

/**
 * 時計を範囲 [limit] に収める縮小率（1 以下）。時刻は中心の上下に、日付は時刻の上（中心から 210k）に描く。
 * 文字の行の高さには上下の余白が含まれるので、字の高さは行の高さの 4 割として数える。
 */
private fun fitScale(
    c: ClockText,
    limit: Rect,
    timeHalfWidth: Float,
    dayHalfWidth: Float,
    timeHeight: Int,
    dayHeight: Int,
): Float {
    val halfWidth = maxOf(timeHalfWidth, dayHalfWidth).coerceAtLeast(1f)
    val up = maxOf(timeHeight * 0.4f, 210f * c.k + dayHeight * 0.4f).coerceAtLeast(1f)
    val down = (timeHeight * 0.4f).coerceAtLeast(1f)
    val s =
        minOf(
            (c.centerX - limit.left) / halfWidth,
            (limit.right - c.centerX) / halfWidth,
            (c.centerY - limit.top) / up,
            (limit.bottom - c.centerY) / down,
            1f,
        )
    // 縁に寄せすぎて小さくなりすぎないよう、下限を置く
    return s.coerceAtLeast(0.3f)
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
    /** 画面の高さ。内蔵のウィジェット（時計・テキスト）の置き場所を、画面の割合から画素にするのに使う。 */
    viewHeight: Float,
    names: Map<String, String>,
    masks: Map<String, PersonMask>,
    /** アイコンを置くコマと、置けるアイコンの数（フォルダのアプリの数。全アプリ一覧の記号は 1）。 */
    iconPanels: Map<String, Int> = emptyMap(),
): Map<PanelId, PanelTextSpec> {
    val tree = layout.shape as? LayoutShape.Tree ?: return emptyMap()
    val text = tree.text
    // 内蔵のウィジェットと、画面の座標の長方形。テキストは文字が空なら描かない
    val builtins =
        tree.widgets.filter { it.builtin == BuiltinWidget.CLOCK || (it.builtin == BuiltinWidget.TEXT && it.style.text.isNotBlank()) }
            .map { it to Rect(it.x * viewWidth, it.y * viewHeight, (it.x + it.w) * viewWidth, (it.y + it.h) * viewHeight) }
    val clockId = PanelText.clockPanel(panels, tree.clock)
    val mastheadId = PanelText.mastheadPanel(panels, text, tree.clock)
    val scale = viewWidth / tree.frame.refWidth
    val k = tree.clock.size * scale
    // 文字は、枠の内側からさらに余白を空けた範囲に収める
    val inset = PanelText.textInset(tree.frame, scale)
    return panels.associate { p ->
        val b = p.polygon.bounds
        val poly = p.polygon
        val cover = coverFunction(p.panel.art?.image?.let(masks::get), p.art)
        // 位置は外接長方形の全体から探し、余白を含めてコマに収まる位置だけを使う（斜めの辺の近くにも置けるように）
        val fits = { r: Rect -> listOf(Point(r.left - inset, r.top - inset), Point(r.right + inset, r.top - inset), Point(r.left - inset, r.bottom + inset), Point(r.right + inset, r.bottom + inset)).all(poly::contains) }
        // 斜めのコマでも文字がコマの外に出ないよう、内接する長方形を基準にし、枠と余白の分だけ内側へ寄せる
        val box by lazy { poly.largestInscribedRect(32).inset(inset) }
        val labels =
            // 時計のあるコマには名前を出さない（数字と重なるため）
            if (text.label == LabelMode.NONE || p.panel.id == clockId || builtins.any { (spec, r) -> spec.builtin == BuiltinWidget.CLOCK && poly.contains(BuiltinWidgets.clockIn(r).first) }) {
                emptyList()
            } else {
                val label = PanelText.labelOf(p.panel, names[p.panel.id.value], text.english)
                if (label == null) {
                    emptyList()
                } else {
                    val vertical = text.label == LabelMode.VERTICAL
                    val pl = PanelText.placeLabel(box, label.length, vertical, text.english, cover, area = b.inset(inset), fits = fits, scale = p.panel.labelScale)
                    // 置き場所を決めたときの箱の長さに収める。書体の字の幅が見積もりより広くても、箱の外へ出ないように
                    val maxLength = if (vertical) pl.boxHeight else pl.boxWidth
                    listOf(LabelText(label, pl.centerX - b.left, pl.centerY - b.top, pl.size, vertical, text.english, maxLength))
                }
            }
        // 内蔵のウィジェットのうち、このコマにかかるもの。コマの文字の層で描くので、コマの形で切り取られ、コマと一緒に動く
        val here = builtins.filter { (_, r) -> r.left < b.right && b.left < r.right && r.top < b.bottom && b.top < r.bottom }
        val mastheads =
            listOfNotNull(
                // 書き換える前の大見出し（ホームで開くと内蔵のウィジェットに書き換える）
                if (p.panel.id == mastheadId) {
                    val (c, size, width) = BuiltinWidgets.mastheadOf(p.polygon, text.mastheadY, inset, text.mastheadScale)
                    MastheadText(text.masthead.trim(), c.x - b.left, c.y - b.top, size, width)
                } else {
                    null
                },
            ) +
                here.filter { it.first.builtin == BuiltinWidget.TEXT }.map { (spec, r) ->
                    val (size, width) = BuiltinWidgets.textIn(r)
                    MastheadText(spec.style.text.trim(), r.center.x - b.left, r.center.y - b.top, size, width, spec.style)
                }
        val clocks =
            listOfNotNull(if (p.panel.id == clockId) clockOf(p.polygon, Point(b.left + tree.clock.x * b.width, b.top + tree.clock.y * b.height), k, inset) else null) +
                here.filter { it.first.builtin == BuiltinWidget.CLOCK }.map { (spec, r) ->
                    val (c, ck) = BuiltinWidgets.clockIn(r)
                    // 中心があるコマでは、以前と同じくコマの縁にかからないよう縮める。ほかのコマには、はみ出した分だけを描く
                    if (poly.contains(c)) clockOf(p.polygon, c, ck, inset, spec.style) else ClockText(c.x - b.left, c.y - b.top, ck, null, spec.style)
                }
        val icons =
            if (text.icon && (iconPanels[p.panel.id.value] ?: 0) > 0) {
                // 名前・大見出し・時計と重ならない、人物のいない場所を探す（座標は画面。返すときにコマの左上を原点にする）
                val avoid =
                    labels.map { Rect(b.left + it.centerX - it.boxHalfWidth(), b.top + it.centerY - it.boxHalfHeight(), b.left + it.centerX + it.boxHalfWidth(), b.top + it.centerY + it.boxHalfHeight()) } +
                        mastheads.map { BuiltinWidgets.textRect(Point(b.left + it.centerX, b.top + it.centerY), it.size, it.width) } +
                        clocks.map { BuiltinWidgets.clockRect(Point(b.left + it.centerX, b.top + it.centerY), it.k) }
                // 編集画面で位置を決めたコマは、その位置を中心に並べる
                val fixed = p.panel.iconAt?.let { Point(b.left + it.x * b.width, b.top + it.y * b.height) }
                iconSlots(b, box, iconPanels.getValue(p.panel.id.value), viewWidth, inset, cover, avoid, fits, fixed, p.panel.iconScale)
            } else {
                emptyList()
            }
        p.panel.id to PanelTextSpec(labels, mastheads, clocks, icons)
    }
}

/**
 * アイコンを置く場所（1 個ずつの正方形。コマの左上が原点）。[available] はフォルダのアプリの数（全アプリ一覧の記号なら 1）。
 * 大きさはコマの広さ（内接する長方形 [box] の面積の平方根）に合わせ、画面の幅に対する範囲に収める。
 * 個数は、内接する長方形の長い辺の 7 割に並べられるだけ（最大 [MAX_ICONS]）。並べる向きは、内接する長方形が横長なら横、縦長なら縦。
 * 複数並べるときは 1 個のときより少し小さくする。まとまり全体の置き場所が見つからなければ、個数を減らして探し直す。
 */
private fun iconSlots(
    b: Rect,
    box: Rect,
    available: Int,
    viewWidth: Float,
    inset: Float,
    cover: ((Rect) -> Float)?,
    avoid: List<Rect>,
    fits: (Rect) -> Boolean,
    /** 編集画面で決めた、まとまりの中心（画面の座標）。null なら空いた場所を探す。 */
    fixed: Point? = null,
    /** 大きさの倍率（編集画面のピンチで決める）。 */
    scale: Float = 1f,
): List<Rect> {
    val fitted = (kotlin.math.sqrt(box.width * box.height) * ICON_PER_PANEL).coerceIn(viewWidth * ICON_MIN_SCALE, viewWidth * ICON_MAX_SCALE)
    // 倍率を掛けたうえで、内接する長方形に収まる大きさまでにする
    val single = minOf(fitted * scale, box.width * ICON_BOX_LIMIT * maxOf(1f, scale), box.height * ICON_BOX_LIMIT * maxOf(1f, scale), box.width * 0.95f, box.height * 0.95f)
    if (single < viewWidth * ICON_MIN_SCALE * 0.8f * minOf(1f, scale)) return emptyList()
    val horizontal = box.width >= box.height
    val long = if (horizontal) box.width else box.height
    val multi = single * ICON_MULTI_SCALE
    val gap = multi * ICON_GAP
    val most = minOf(available, MAX_ICONS, ((long * 0.7f + gap) / (multi + gap)).toInt()).coerceAtLeast(1)
    // まず文字と重ならない置き場所を、個数を減らしながら探す。どこも重なるなら、1 個だけを重なりの少ない所に置く
    val tries = (most downTo 1).map { it to true } + (1 to false)
    for ((n, clear) in tries) {
        val size = if (n == 1) single else multi
        val length = n * size + (n - 1) * gap
        val w = if (horizontal) length else size
        val h = if (horizontal) size else length
        val group =
            if (fixed != null) {
                // 指定の位置に並べる。コマからはみ出すなら、コマの中心へ少しずつ寄せて、最初に収まった位置に置く
                // （縁の近くまで動かしたときに、自動の位置へ戻らないように）。どこでも収まらなければ個数を減らす
                (0..10).asSequence().map { k ->
                    val f = k / 10f
                    val cx = fixed.x + (box.center.x - fixed.x) * f
                    val cy = fixed.y + (box.center.y - fixed.y) * f
                    Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
                }.firstOrNull(fits) ?: continue
            } else {
                PanelText.placeIcon(b.inset(inset), w, h, cover, avoid, fits, clear) ?: continue
            }
        val slots =
            (0 until n).map { i ->
                val step = i * (size + gap)
                val left = group.left + if (horizontal) step else 0f
                val top = group.top + if (horizontal) 0f else step
                Rect(left, top, left + size, top + size)
            }
        // まとまり全体では人物との重なりが少なくても、1 個がほぼ隠れることがある。半分以上隠れるアイコンがあれば、個数を減らして探し直す
        if (n > 1 && cover != null && slots.any { cover(it) > ICON_HIDDEN }) continue
        return slots.map { Rect(it.left - b.left, it.top - b.top, it.right - b.left, it.bottom - b.top) }
    }
    // 指定の位置ではどの個数もはみ出すときは、空いた場所を自動で探す
    if (fixed != null) return iconSlots(b, box, available, viewWidth, inset, cover, avoid, fits, null, scale)
    return emptyList()
}

/** 名前の箱の半分の幅と高さ。縦書きの英語は 90 度回すので、長さが縦になる。 */
private fun LabelText.boxHalfWidth(): Float = (if (vertical) size else maxLength) / 2f

private fun LabelText.boxHalfHeight(): Float = (if (vertical) maxLength else size) / 2f

/** アイコンの一辺。コマの広さ（内接する長方形の面積の平方根）に対する割合と、画面の幅に対する下限・上限。 */
private const val ICON_PER_PANEL = 0.25f
private const val ICON_MIN_SCALE = 0.13f
private const val ICON_MAX_SCALE = 0.17f

/** アイコンの一辺の、内接する長方形の短い辺に対する上限（小さいコマで小さくなりすぎないよう、利用者の指摘で 0.45 から 0.7 まで上げた）。 */
private const val ICON_BOX_LIMIT = 0.7f

/** 1 つのコマに並べるアイコンの最大の数と、複数のときの大きさ（1 個のときに対する割合）、アイコンの間（大きさに対する割合）。 */
private const val MAX_ICONS = 4
private const val ICON_MULTI_SCALE = 0.8f
private const val ICON_GAP = 0.25f

/** アイコンのうち人物に隠れる割合がこれを超えたら、隠れているとみなす。 */
private const val ICON_HIDDEN = 0.5f

/** 時計の置き場所と、はみ出さずに描ける範囲（枠と余白 [inset] の内側）。[center] は画面の座標、返す座標はコマの外接長方形の左上が原点。 */
private fun clockOf(
    polygon: Polygon,
    center: Point,
    k: Float,
    inset: Float,
    style: WidgetStyle = WidgetStyle(),
): ClockText {
    val b = polygon.bounds
    val l = BuiltinWidgets.clockLimit(polygon, center, k, inset)
    return ClockText(center.x - b.left, center.y - b.top, k, Rect(l.left - b.left, l.top - b.top, l.right - b.left, l.bottom - b.top), style)
}

/** コマに置くアプリのアイコンの不透明度。絵の上で強く目立ちすぎないように、わずかに透かす。 */
private const val ICON_ALPHA = 0.92f
