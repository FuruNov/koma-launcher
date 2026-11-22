package dev.furunov.komalauncher.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.furunov.komalauncher.model.layout.BuiltinWidget
import dev.furunov.komalauncher.model.layout.DateFormat
import dev.furunov.komalauncher.model.layout.TextColor
import dev.furunov.komalauncher.model.layout.WidgetFonts
import dev.furunov.komalauncher.model.layout.WidgetSpec
import dev.furunov.komalauncher.model.layout.WidgetStyle
import dev.furunov.komalauncher.ui.art.widgetFontFamily
import dev.furunov.komalauncher.ui.controls.ToggleSwitch

private val FONT_NAMES =
    mapOf(
        WidgetFonts.DELA to "Dela Gothic One",
        WidgetFonts.BODONI to "Bodoni Moda",
        WidgetFonts.SANS to "ゴシック",
        WidgetFonts.SERIF to "明朝",
        WidgetFonts.MONO to "等幅",
        WidgetFonts.CONDENSED to "幅の狭いゴシック",
    )

private val WEIGHTS = listOf(300 to "細い", 400 to "標準", 500 to "中", 700 to "太い", 900 to "極太")

private val DATES = listOf(DateFormat.LONG to "11月22日（日）", DateFormat.SHORT to "11/22 日", DateFormat.NONE to "表示しない")

private val COLORS = listOf(TextColor.AUTO to "自動", TextColor.WHITE to "白", TextColor.BLACK to "黒", TextColor.ACCENT to "アクセント")

/**
 * 内蔵のウィジェット（時計・テキスト）の見た目を変えるシート。ホームの時計が見えるよう、画面の下側に暗くせずに出す。
 * 選んだ値はその場で [onChange] に渡し、ホームに反映する。
 */
@Composable
internal fun WidgetStyleSheet(
    spec: WidgetSpec,
    onChange: (WidgetStyle) -> Unit,
    onEvent: (HomeEvent) -> Unit,
) {
    BackHandler { onEvent(HomeEvent.Dismiss) }
    val kind = spec.builtin ?: return
    val s = spec.style
    val font = s.font ?: WidgetFonts.defaultFor(kind)
    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onEvent(HomeEvent.Dismiss) }
            .imePadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .background(Color(0xF21E1E24), RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    if (kind == BuiltinWidget.CLOCK) "時計のスタイル" else "テキストのスタイル",
                    style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                BasicText(
                    "完了",
                    style = TextStyle(color = Color(0xFFFFD54F), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.clickable { onEvent(HomeEvent.Dismiss) }.padding(8.dp),
                )
            }
            if (kind == BuiltinWidget.TEXT) {
                var text by remember(spec.id) { mutableStateOf(s.text) }
                StyleRow("文字") {
                    BasicTextField(
                        value = text,
                        onValueChange = {
                            text = it.take(64)
                            onChange(s.copy(text = text))
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                        cursorBrush = SolidColor(Color.White),
                        modifier = Modifier.fillMaxWidth().background(Color(0xFF2A2A32), RoundedCornerShape(10.dp)).padding(10.dp),
                    )
                }
            }
            StyleRow("フォント") {
                WidgetFonts.ALL.forEach { id ->
                    val sample = if (kind == BuiltinWidget.CLOCK) "12:34" else "Aa"
                    StyleChip(selected = id == font, onClick = { onChange(s.copy(font = id)) }) {
                        ChipText(sample, style = TextStyle(fontFamily = widgetFontFamily(id), fontSize = 18.sp, fontStyle = if (id == WidgetFonts.BODONI) FontStyle.Italic else FontStyle.Normal))
                        ChipText(FONT_NAMES.getValue(id), style = TextStyle(fontSize = 11.sp))
                    }
                }
            }
            if (WidgetFonts.hasWeights(font)) {
                val current = s.weight ?: if (kind == BuiltinWidget.CLOCK) 700 else 800
                StyleRow("太さ") {
                    WEIGHTS.forEach { (w, name) ->
                        StyleChip(selected = current == w || (w == 900 && current == 800), onClick = { onChange(s.copy(weight = w)) }) { ChipText(name) }
                    }
                }
            } else {
                Hint("${FONT_NAMES.getValue(font)} は太さが 1 種類だけです")
            }
            if (kind == BuiltinWidget.CLOCK) {
                StyleRow("日付") {
                    DATES.forEach { (d, name) -> StyleChip(selected = s.date == d, onClick = { onChange(s.copy(date = d)) }) { ChipText(name) } }
                }
                SwitchRow("24 時間制", s.h24) { onChange(s.copy(h24 = !s.h24)) }
                SwitchRow("秒", s.seconds) { onChange(s.copy(seconds = !s.seconds)) }
            } else {
                val italic = s.italic ?: (font == WidgetFonts.BODONI)
                SwitchRow("斜体", italic) { onChange(s.copy(italic = !italic)) }
            }
            StyleRow("色") {
                COLORS.forEach { (c, name) -> StyleChip(selected = s.color == c, onClick = { onChange(s.copy(color = c)) }) { ChipText(name) } }
            }
            Hint("「自動」は、白い紙のコマの上では黒、それ以外では白にします")
            SwitchRow(if (kind == BuiltinWidget.CLOCK) "影" else "縁と影", s.shadow) { onChange(s.copy(shadow = !s.shadow)) }
            if (s != WidgetStyle(text = s.text)) {
                BasicText(
                    "デフォルトに戻す",
                    style = TextStyle(color = Color(0xFFFFD54F), fontSize = 14.sp),
                    modifier = Modifier.clickable { onChange(WidgetStyle(text = s.text)) }.padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun StyleRow(
    title: String,
    content: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(title, style = TextStyle(color = Color(0xFF9E9EA8), fontSize = 13.sp), modifier = Modifier.width(72.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    on: Boolean,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(title, style = TextStyle(color = Color(0xFF9E9EA8), fontSize = 13.sp), modifier = Modifier.width(72.dp))
        ToggleSwitch(on)
    }
}

@Composable
private fun StyleChip(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val fg = if (selected) Color(0xFF1A1A1A) else Color.White
    Column(
        Modifier
            .background(if (selected) Color(0xFFFFD54F) else Color(0xFF2A2A32), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalChipColor provides fg) { content() }
    }
}

private val LocalChipColor = androidx.compose.runtime.compositionLocalOf { Color.White }

@Composable
private fun ChipText(
    text: String,
    style: TextStyle = TextStyle(fontSize = 13.sp),
) = BasicText(text, style = style.copy(color = LocalChipColor.current))

@Composable
private fun Hint(text: String) = BasicText(text, style = TextStyle(color = Color(0xFF9E9EA8), fontSize = 12.sp), modifier = Modifier.padding(vertical = 4.dp))
