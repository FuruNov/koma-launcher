package dev.furunov.komalauncher.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.furunov.komalauncher.model.geometry.Point
import dev.furunov.komalauncher.model.geometry.Rect
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.LayoutShape
import dev.furunov.komalauncher.model.layout.PanelId
import dev.furunov.komalauncher.model.layout.Part
import dev.furunov.komalauncher.model.layout.PartEdits
import dev.furunov.komalauncher.model.layout.ResolvedPanel
import dev.furunov.komalauncher.ui.art.PanelTextSpec

/**
 * ホームの「配置を調整」。時計・大見出し・コマの名前・アイコンのまとまりを、ホームの上で直接扱う。
 * - 1 本指のドラッグで動かす（名前は動かさない）
 * - 2 本指でつまむと、指を置いたコンポーネントだけを拡大縮小する
 * - ダブルタップで、そのコンポーネントの大きさ（アイコンは位置も）を自動に戻す
 * 普段のホームの操作（タップでフォルダ、スワイプのジェスチャー）とぶつからないよう、この状態の間だけ有効にする。
 */
internal class AdjustTarget(
    val layout: Layout,
    val panels: List<ResolvedPanel>,
    val texts: Map<PanelId, PanelTextSpec>,
    val viewWidth: Float,
)

/** 画面の点 [p] にあるコンポーネント。アイコン → 時計・大見出し → 名前 の順に調べる（上に重なって見えるものから）。 */
internal fun partAt(
    t: AdjustTarget,
    p: Point,
): Part? {
    for (panel in t.panels.asReversed()) {
        val r = iconGroupRect(panel, t.texts[panel.panel.id]) ?: continue
        if (r.contains(p)) return Part.Icons(panel.panel.id, r.center)
    }
    PartEdits.textAt(t.layout, t.panels, t.viewWidth, p)?.let { return it }
    for (panel in t.panels.asReversed()) {
        val b = panel.polygon.bounds
        for (l in t.texts[panel.panel.id]?.labels.orEmpty()) {
            if (labelRect(b, l).contains(p)) return Part.Label(panel.panel.id)
        }
    }
    return null
}

/** アイコンのまとまりを囲む長方形（画面の座標）。アイコンが無ければ null。 */
private fun iconGroupRect(
    panel: ResolvedPanel,
    spec: PanelTextSpec?,
): Rect? {
    val icons = spec?.icons.orEmpty()
    if (icons.isEmpty()) return null
    val b = panel.polygon.bounds
    return Rect(b.left + icons.minOf { it.left }, b.top + icons.minOf { it.top }, b.left + icons.maxOf { it.right }, b.top + icons.maxOf { it.bottom })
}

/** 名前の箱（画面の座標）。縦書きの英語は 90 度回すので、長さが縦になる。 */
private fun labelRect(
    b: Rect,
    l: dev.furunov.komalauncher.ui.art.LabelText,
): Rect {
    val halfW = (if (l.vertical) l.size else l.maxLength) / 2f
    val halfH = (if (l.vertical) l.maxLength else l.size) / 2f
    return Rect(b.left + l.centerX - halfW, b.top + l.centerY - halfH, b.left + l.centerX + halfW, b.top + l.centerY + halfH)
}

/**
 * 「配置を調整」の指の操作。[target] は今の配置（描画の段階の値を読むので State で受け取る）。
 * [edit] でレイアウトを書き換え、[record] は 1 回の操作の始まりに呼ぶ（元に戻す履歴に積む）。
 */
internal fun Modifier.adjustGestures(
    target: State<AdjustTarget?>,
    record: () -> Unit,
    edit: ((Layout) -> Layout) -> Unit,
): Modifier = pointerInput(Unit) {
    var lastTapAt = 0L
    var lastTapPart: Part? = null
    awaitEachGesture {
        val down = awaitFirstDown()
        val t = target.value ?: return@awaitEachGesture
        val p0 = Point(down.position.x, down.position.y)
        val part = partAt(t, p0) ?: return@awaitEachGesture
        var began = false
        var pinching = false
        var startSpread = 0f
        var startScale = 1f
        while (true) {
            val e = awaitPointerEvent()
            val pressed = e.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size >= 2) {
                val spread = (pressed[0].position - pressed[1].position).getDistance()
                if (!pinching) {
                    pinching = true
                    startSpread = spread
                    startScale = PartEdits.scaleOf(target.value?.layout ?: t.layout, part)
                    if (!began) record()
                    began = true
                } else if (startSpread > 0f) {
                    val scale = startScale * spread / startSpread
                    edit { PartEdits.setScale(it, part, scale) }
                }
                pressed.forEach { it.consume() }
                continue
            }
            // ピンチの後に指を 1 本離しても、残った指では動かさない（意図せず位置がずれないように）
            if (pinching) continue
            val ch = pressed.firstOrNull { it.id == down.id } ?: continue
            val d = ch.position - down.position
            if (!began && d.getDistance() < viewConfiguration.touchSlop) continue
            if (part is Part.Label) continue
            if (!began) record()
            began = true
            ch.consume()
            val panels = t.panels
            edit { PartEdits.move(it, panels, part, d.x, d.y) }
        }
        if (!began) {
            // 同じコンポーネントを続けて 2 回押したら、自動に戻す
            val now = down.uptimeMillis
            val same = lastTapPart?.let { it::class == part::class && (it !is Part.Icons || it.panel == (part as Part.Icons).panel) && (it !is Part.Label || it.panel == (part as Part.Label).panel) } == true
            if (same && now - lastTapAt < viewConfiguration.doubleTapTimeoutMillis) {
                record()
                edit { PartEdits.reset(it, part) }
                lastTapAt = 0L
                lastTapPart = null
            } else {
                lastTapAt = now
                lastTapPart = part
            }
        }
    }
}

/** 「配置を調整」の表示。扱えるコンポーネントに枠を描き、上端に見出しと「元に戻す」「完了」を置く。 */
@Composable
internal fun AdjustOverlay(
    target: AdjustTarget,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onDone: () -> Unit,
) {
    BackHandler(onBack = onDone)
    val tree = target.layout.shape as? LayoutShape.Tree
    Canvas(Modifier.fillMaxSize()) {
        val stroke = Stroke(width = 2.dp.toPx())
        val color = Color(0xCCFFD54F)
        fun frame(r: Rect) = drawRect(color, topLeft = Offset(r.left, r.top), size = Size(r.width, r.height), style = stroke)
        for (panel in target.panels) {
            val spec = target.texts[panel.panel.id]
            iconGroupRect(panel, spec)?.let(::frame)
            spec?.labels?.forEach { frame(labelRect(panel.polygon.bounds, it)) }
        }
        if (tree != null) {
            val k = tree.clock.size * target.viewWidth / tree.frame.refWidth
            PartEdits.clockCenter(target.layout, target.panels)?.let { c -> frame(Rect(c.x - 450f * k, c.y - 240f * k, c.x + 450f * k, c.y + 170f * k)) }
            if (tree.text.masthead.isNotBlank()) {
                val id = dev.furunov.komalauncher.model.layout.PanelText.mastheadPanel(target.panels, tree.text, tree.clock)
                val b = target.panels.firstOrNull { it.panel.id == id }?.polygon?.bounds
                val c = PartEdits.mastheadCenter(target.layout, target.panels)
                if (b != null && c != null) {
                    val half = dev.furunov.komalauncher.model.layout.PanelText.mastheadSize(b) * tree.text.mastheadScale * 0.6f
                    frame(Rect(c.x - b.width * 0.45f, c.y - half, c.x + b.width * 0.45f, c.y + half))
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xE61E1E24), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText("配置を調整", style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(vertical = 8.dp))
            Spacer(Modifier.weight(1f))
            Button("元に戻す", enabled = canUndo, onClick = onUndo)
            Button("完了", accent = true, onClick = onDone)
        }
        BasicText(
            "ドラッグで移動、2 本指でつまんで拡大縮小、ダブルタップで自動に戻す",
            style = TextStyle(color = Color.White, fontSize = 12.sp),
            modifier = Modifier.padding(top = 6.dp).background(Color(0x991E1E24), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun Button(
    text: String,
    enabled: Boolean = true,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = if (accent) Color(0xFFFFD54F) else Color(0xFF2A2A32)
    val fg = if (accent) {
        Color(0xFF121216)
    } else if (enabled) {
        Color.White
    } else {
        Color(0xFF5A5A64)
    }
    Box(Modifier.background(bg, RoundedCornerShape(18.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        BasicText(text, style = TextStyle(color = fg, fontSize = 14.sp))
    }
}
