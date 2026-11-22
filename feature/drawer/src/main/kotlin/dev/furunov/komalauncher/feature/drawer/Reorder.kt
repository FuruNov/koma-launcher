package dev.furunov.komalauncher.feature.drawer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * 長押しから始まる並べ替えの受け手。
 * - 長押しして動かすと、指の下の項目の位置へ移す（[onMove]）。指を離したら [onEnd] に true を渡す
 * - 動かさずに離すと [onEnd] に false を渡し、[onRelease] を呼ぶ（メニューを出す）
 * - 指が領域の外へ出て [onLeave] が true を返したら、その後の動きは [onHandoffMove]、離したときは [onHandoffEnd] へ渡す（ホームへ運ぶ）
 */
internal class ReorderHandler(
    /** 指の位置にある項目のキーと、並びの中の番号。項目が無ければ null。 */
    val itemAt: (Offset) -> Pair<Any, Int>?,
    val onStart: (Any) -> Unit,
    val onMove: (key: Any, to: Int) -> Unit,
    val onEnd: (moved: Boolean) -> Unit,
    val onRelease: (Any) -> Unit = {},
    /** 指が領域の外へ出たとき。指の位置（領域の座標）を受け、引き継ぐなら true を返す。 */
    val onLeave: ((key: Any, at: Offset) -> Boolean)? = null,
    val onHandoffMove: (Offset) -> Unit = {},
    val onHandoffEnd: () -> Unit = {},
    /** 指が端にあるときに呼ぶ。引数は進める量（px）。並びの向き（縦か横か）で端を決める。 */
    val onEdge: (Float) -> Unit = {},
    val horizontal: Boolean = false,
)

/**
 * [handler] の並べ替えを受ける。長押しが成立した後の指の動きは、子より先（Initial）に受け取って消費する。
 * Main で受けると、一覧のスクロールが先に動きを消費し、並べ替えずにスクロールした（2026-11-22 に実機で確認）。
 */
internal fun Modifier.reorderable(
    key: Any?,
    handler: ReorderHandler,
): Modifier = pointerInput(key) {
    val edge = 64.dp.toPx()
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // 長押しの前に指が動いたら（スクロールなら）、何もしない
        awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        var pointer = down.position
        val moving = handler.itemAt(pointer)?.first ?: return@awaitEachGesture
        handler.onStart(moving)
        var travelled = 0f
        var handedOff = false
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            val delta = change.positionChange()
            // 離したときも消費し、項目の clickable に押したと受け取らせない
            change.consume()
            if (!change.pressed) break
            pointer += delta
            travelled += delta.getDistance()
            if (handedOff) {
                handler.onHandoffMove(delta)
                continue
            }
            val inside = pointer.x in 0f..size.width.toFloat() && pointer.y in 0f..size.height.toFloat()
            if (!inside && handler.onLeave?.invoke(moving, pointer) == true) {
                handedOff = true
                continue
            }
            val along = if (handler.horizontal) pointer.x else pointer.y
            val length = if (handler.horizontal) size.width else size.height
            when {
                along < edge -> handler.onEdge(-(edge - along) / 4)
                along > length - edge -> handler.onEdge((along - (length - edge)) / 4)
            }
            handler.itemAt(pointer)?.let { (over, index) -> if (over != moving) handler.onMove(moving, index) }
        }
        when {
            handedOff -> handler.onHandoffEnd()

            travelled < slop -> {
                handler.onEnd(false)
                handler.onRelease(moving)
            }

            else -> handler.onEnd(true)
        }
    }
}

/** 動かしている項目を少し大きくし、ほかの項目より手前に描く。 */
internal fun Modifier.lifted(on: Boolean): Modifier = if (!on) {
    this
} else {
    zIndex(1f).graphicsLayer {
        scaleX = 1.12f
        scaleY = 1.12f
    }
}
