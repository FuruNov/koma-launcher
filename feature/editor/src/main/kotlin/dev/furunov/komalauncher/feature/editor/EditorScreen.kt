package dev.furunov.komalauncher.feature.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.furunov.komalauncher.model.geometry.Point
import dev.furunov.komalauncher.model.layout.BorderStyle
import dev.furunov.komalauncher.model.layout.CutEnd
import dev.furunov.komalauncher.model.layout.LayoutEdits
import dev.furunov.komalauncher.model.layout.LayoutShape
import dev.furunov.komalauncher.model.layout.PanelContent
import dev.furunov.komalauncher.model.layout.SplitDirection
import dev.furunov.komalauncher.ui.art.PanelFace
import dev.furunov.komalauncher.ui.art.rememberArts
import kotlin.math.hypot

/**
 * コマ割りの編集画面（モックアップの「線で割る」）。上に画面の縮小図、下に操作を並べる。
 * 縮小図の上の操作: コマを押して選ぶ、選んだコマの線の端（丸）を縁に沿って動かす、
 * 選んだコマの絵をドラッグで動かし 2 本指で拡大する。
 */
@Composable
fun EditorScreen(
    vm: EditorViewModel,
    /** ホームと同じ画面の大きさ（画素）。 */
    screenWidth: Float,
    screenHeight: Float,
    /** 設定の「コマの間の色」。レイアウトに色が無いときに使う。 */
    defaultGutterColor: Long,
    onClose: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    vm.setScreen(screenWidth, screenHeight)
    BackHandler(onBack = onClose)
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { vm.setImage(it.toString()) }
        }
    val frame = (state.layout.shape as? LayoutShape.Tree)?.frame
    val selectedPanel = state.selected?.let { state.layout.panel(it) }

    Column(Modifier.fillMaxSize().background(Color(0xFF121216)).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("コマ割りを編集", style = TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.weight(1f))
            Chip("元に戻す", enabled = state.canUndo, onClick = vm::undo)
            Spacer(Modifier.width(8.dp))
            Chip("閉じる", onClick = onClose)
        }
        Preview(vm, state, screenWidth, screenHeight, Color(frame?.gutterColor ?: defaultGutterColor), Modifier.weight(1f).fillMaxWidth())
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
            state.message?.let {
                BasicText(it, style = TextStyle(color = Color(0xFFFFD54F), fontSize = 13.sp), modifier = Modifier.padding(vertical = 4.dp).clickable(onClick = vm::dismissMessage))
            }
            ChipRow("型") {
                LayoutEdits.Template.entries.forEach { t -> Chip(t.label) { vm.applyTemplate(t) } }
            }
            if (state.editable) {
                if (selectedPanel == null) {
                    Hint("コマを押して選ぶと、割る・線を消す・画像を選ぶができます")
                } else {
                    ChipRow("コマ") {
                        Chip("横に割る") { vm.split(SplitDirection.HORIZONTAL) }
                        Chip("縦に割る") { vm.split(SplitDirection.VERTICAL) }
                        Chip("線を消す", enabled = vm.selectedCutPath() != null, onClick = vm::merge)
                        Chip("種類: ${kindLabel(selectedPanel.content)}", onClick = vm::cycleKind)
                    }
                    ChipRow("画像") {
                        Chip(if (state.busy) "取り込み中…" else "画像を選ぶ", enabled = !state.busy) {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        Chip("画像を外す", enabled = selectedPanel.art != null, onClick = vm::removeImage)
                    }
                    if (selectedPanel.art != null) Hint("画像はドラッグで動かし、2 本の指で広げると大きくなります。絵の端は見えない範囲で止まります")
                }
                if (frame != null) {
                    SliderRow("コマの間", frame.gutter, 0f..60f, onStart = vm::beginGesture) { v -> vm.setFrame(record = false) { it.copy(gutter = v) } }
                    ChipRow("間の色") {
                        Chip("黒", selected = frame.gutterColor == BLACK) { vm.setFrame { it.copy(gutterColor = BLACK) } }
                        Chip("白", selected = frame.gutterColor == WHITE) { vm.setFrame { it.copy(gutterColor = WHITE) } }
                        Chip("設定の色", selected = frame.gutterColor == null) { vm.setFrame { it.copy(gutterColor = null) } }
                    }
                    ChipRow("枠") {
                        listOf(BorderStyle.NONE to "なし", BorderStyle.SINGLE to "1 本線", BorderStyle.DOUBLE to "二重").forEach { (s, label) ->
                            Chip(label, selected = frame.border.style == s) { vm.setFrame { it.copy(border = it.border.copy(style = s)) } }
                        }
                        Chip("裁ち落とし", selected = frame.bleed) { vm.setFrame { it.copy(bleed = !it.bleed) } }
                    }
                    SliderRow("枠の太さ", frame.border.width, 0f..40f, onStart = vm::beginGesture) { v -> vm.setFrame(record = false) { it.copy(border = it.border.copy(width = v)) } }
                }
            }
        }
    }
}

private const val BLACK = 0xFF111111
private const val WHITE = 0xFFFFFFFF

internal fun kindLabel(c: PanelContent): String = when (c) {
    is PanelContent.Folder -> "フォルダ"
    PanelContent.Widget -> "ウィジェット"
    PanelContent.Drawer -> "全アプリ一覧"
}

/** 画面の縮小図と、その上の指の操作。 */
@Composable
private fun Preview(
    vm: EditorViewModel,
    state: EditorUiState,
    screenWidth: Float,
    screenHeight: Float,
    gutter: Color,
    modifier: Modifier,
) {
    val resolved = remember(state.layout, screenWidth, screenHeight) { vm.resolve(state.layout) }
    val sizes =
        remember(resolved) {
            resolved.panels.mapNotNull { p -> p.art?.let { a -> p.panel.art?.image?.let { it to Size(a.width, a.height) } } }.toMap()
        }
    val arts by rememberArts(state.images, sizes, state.imagesRevision)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val handleRadius = with(density) { 22.dp.toPx() }
    val current by rememberUpdatedState(state)
    val currentResolved by rememberUpdatedState(resolved)

    BoxWithConstraints(modifier.padding(8.dp), contentAlignment = Alignment.Center) {
        val maxW = with(density) { maxWidth.toPx() }
        val maxH = with(density) { maxHeight.toPx() }
        val s = minOf(maxW / screenWidth, maxH / screenHeight)
        val cutPath = state.selected?.let { LayoutEdits.parentCutPath(state.layout, it) }
        val cut = cutPath?.let { p -> resolved.cuts.firstOrNull { it.path == p } }
        Canvas(
            Modifier
                .size(with(density) { (screenWidth * s).toDp() }, with(density) { (screenHeight * s).toDp() })
                .semantics { contentDescription = "コマ割りの図" }
                .pointerInput(s) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val start = down.position
                        val p0 = Point(start.x / s, start.y / s)
                        val st = current
                        // 1. 線の端の丸を掴んだら、親のコマの縁に沿って動かす
                        val path = st.selected?.let { LayoutEdits.parentCutPath(st.layout, it) }
                        val c = path?.let { pp -> currentResolved.cuts.firstOrNull { it.path == pp } }
                        val end =
                            c?.let {
                                when {
                                    hypot(it.a.x * s - start.x, it.a.y * s - start.y) < handleRadius * 1.6f -> CutEnd.A
                                    hypot(it.b.x * s - start.x, it.b.y * s - start.y) < handleRadius * 1.6f -> CutEnd.B
                                    else -> null
                                }
                            }
                        if (c != null && end != null) {
                            vm.beginGesture()
                            while (true) {
                                val e = awaitPointerEvent()
                                val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                ch.consume()
                                vm.moveCutEnd(c.path, end, Point(ch.position.x / s, ch.position.y / s))
                            }
                            return@awaitEachGesture
                        }
                        val hit = currentResolved.panels.lastOrNull { it.polygon.bounds.contains(p0) && it.polygon.contains(p0) }
                        val art = vm.selectedArt()
                        // 2. 選んでいるコマの絵を動かす・広げる
                        if (hit != null && hit.panel.id == st.selected && art != null) {
                            var began = false
                            var startCentroid = start
                            var startSpread = 0f
                            var pointers = 1
                            while (true) {
                                val e = awaitPointerEvent()
                                val pressed = e.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                val centroid = pressed.fold(Offset.Zero) { acc, ch -> acc + ch.position } / pressed.size.toFloat()
                                val spread = if (pressed.size >= 2) (pressed[0].position - pressed[1].position).getDistance() else 0f
                                // 指の数が変わったら、そこを新しい起点にする（絵が跳ばないように）
                                if (pressed.size != pointers) {
                                    pointers = pressed.size
                                    startCentroid = centroid
                                    startSpread = spread
                                }
                                if (!began && (centroid - start).getDistance() < viewConfiguration.touchSlop && pressed.size < 2) continue
                                if (!began) {
                                    vm.beginGesture()
                                    began = true
                                }
                                val now = vm.selectedArt() ?: break
                                val d = (centroid - startCentroid) / s
                                val k = if (pressed.size >= 2 && startSpread > 0f) spread / startSpread else 1f
                                vm.moveArt(now, d.x, d.y, k)
                                startCentroid = centroid
                                startSpread = spread
                                pressed.forEach { it.consume() }
                            }
                            return@awaitEachGesture
                        }
                        // 3. 押して離したら、そのコマを選ぶ（余白なら選ぶのをやめる）
                        val up = waitForUpOrCancel(down.id)
                        if (up != null && (up - start).getDistance() < viewConfiguration.touchSlop) vm.select(hit?.panel?.id)
                    }
                },
        ) {
            scale(s, s, pivot = Offset.Zero) {
                // 拡大縮小の中で描くので、大きさは画面の画素で渡す（キャンバスの大きさのままだと一部しか塗らない）
                drawRect(gutter, size = Size(screenWidth, screenHeight))
                for (p in resolved.panels) {
                    val face = PanelFace(p.polygon, p.art, PanelFace.placeholderFor(p.index))
                    val b = face.bounds
                    translate(b.left, b.top) {
                        clipPath(face.path) { with(face) { drawContent(p.panel.art?.image?.let(arts::get)) } }
                    }
                    // 種類と名前。ホームでは出さない編集用の表示
                    val text = listOfNotNull(p.panel.label, kindLabel(p.panel.content)).distinct().joinToString("\n")
                    val layout =
                        measurer.measure(
                            text,
                            TextStyle(
                                color = Color.White,
                                fontSize = (13 / s / density.fontScale).sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                shadow = Shadow(Color.Black, Offset(0f, 2f / s), 6f / s),
                            ),
                        )
                    val c = b.center
                    drawText(layout, topLeft = Offset(c.x - layout.size.width / 2f, c.y - layout.size.height / 2f))
                }
                val sel = resolved.panels.firstOrNull { it.panel.id == state.selected }
                if (sel != null) {
                    val outline = Path().apply {
                        sel.polygon.points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                        close()
                    }
                    drawPath(outline, Color(0xFFFFD54F), style = Stroke(width = 4.dp.toPx() / s))
                }
                if (cut != null) {
                    drawLine(Color(0xFFFFD54F), Offset(cut.a.x, cut.a.y), Offset(cut.b.x, cut.b.y), strokeWidth = 2.dp.toPx() / s)
                    listOf(cut.a, cut.b).forEach { p ->
                        drawCircle(Color(0xFFFFD54F), radius = handleRadius / s, center = Offset(p.x, p.y))
                        drawCircle(Color(0xFF121216), radius = handleRadius / s, center = Offset(p.x, p.y), style = Stroke(width = 2.dp.toPx() / s))
                    }
                }
            }
        }
    }
}

/** 指が離れた位置。取り消されたら null。 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitForUpOrCancel(id: androidx.compose.ui.input.pointer.PointerId): Offset? {
    while (true) {
        val e = awaitPointerEvent()
        val ch = e.changes.firstOrNull { it.id == id } ?: return null
        if (!ch.pressed) return ch.position
    }
}

@Composable
private fun ChipRow(
    title: String,
    content: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(title, style = TextStyle(color = Color(0xFF9E9EA8), fontSize = 13.sp), modifier = Modifier.width(64.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun Hint(text: String) {
    BasicText(text, style = TextStyle(color = Color(0xFF9E9EA8), fontSize = 12.sp), modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
internal fun Chip(
    text: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val bg =
        when {
            selected -> Color(0xFFFFD54F)
            enabled -> Color(0xFF2A2A32)
            else -> Color(0xFF1C1C22)
        }
    val fg =
        when {
            selected -> Color(0xFF121216)
            enabled -> Color.White
            else -> Color(0xFF5A5A64)
        }
    BasicText(
        text,
        style = TextStyle(color = fg, fontSize = 14.sp),
        modifier =
        Modifier
            .background(bg, RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

/** 横に動かすスライダー。Material を入れずに済むよう、ここで作る。 */
@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onStart: () -> Unit,
    onChange: (Float) -> Unit,
) {
    val currentChange by rememberUpdatedState(onChange)
    val currentStart by rememberUpdatedState(onStart)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(title, style = TextStyle(color = Color(0xFF9E9EA8), fontSize = 13.sp), modifier = Modifier.width(64.dp))
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .height(36.dp)
                .semantics { contentDescription = title }
                .pointerInput(range) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        currentStart()
                        fun at(x: Float) = (range.start + (x / size.width).coerceIn(0f, 1f) * (range.endInclusive - range.start)).let { kotlin.math.round(it) }
                        currentChange(at(down.position.x))
                        while (true) {
                            val e = awaitPointerEvent()
                            val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            ch.consume()
                            currentChange(at(ch.position.x))
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val f = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFF3A3A44), RoundedCornerShape(2.dp)))
            Box(Modifier.width(maxWidth * f).height(4.dp).background(Color(0xFFFFD54F), RoundedCornerShape(2.dp)))
            Box(Modifier.offset(x = (maxWidth - 20.dp) * f).size(20.dp).background(Color(0xFFFFD54F), RoundedCornerShape(10.dp)))
        }
        BasicText(value.toInt().toString(), style = TextStyle(color = Color.White, fontSize = 13.sp), modifier = Modifier.width(32.dp))
    }
}
