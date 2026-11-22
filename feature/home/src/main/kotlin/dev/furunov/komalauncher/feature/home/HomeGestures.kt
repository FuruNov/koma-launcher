package dev.furunov.komalauncher.feature.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.furunov.komalauncher.model.settings.GestureKind
import kotlin.math.abs
import kotlin.math.hypot

/** ジェスチャーの結果の受け手。ホーム画面はこれを呼ぶだけで、何を実行するかは知らない。 */
interface HomeGestureHandler {
    fun onPanelPress(panel: PanelGeometry)

    /** [tapped] が true ならタップが成立した。false ならスワイプなどで取り消された。[position] は押した位置（画面の座標）。 */
    fun onPanelRelease(panel: PanelGeometry, tapped: Boolean, position: Offset)

    fun onPanelLongPress(panel: PanelGeometry, position: Offset)

    /** 自由に置くウィジェットの長押し。 */
    fun onWidgetLongPress(widget: WidgetGeometry) = Unit

    /** コマの上の左右のスワイプ。[toLeft] が true なら左へスワイプした。 */
    fun onPanelSwipe(panel: PanelGeometry, toLeft: Boolean)

    fun onGesture(kind: GestureKind, position: Offset)

    /** 1 本指で上へ動き始めたとき（指を離す前）。上へのスワイプに備えて、重い準備を先に済ませるために使う。1 回の操作で 1 度だけ呼ぶ。 */
    fun onSwipeUpStart() = Unit
}

/**
 * ホーム画面のジェスチャーを見分ける。
 *
 * - コマの上のタップは、指を離した瞬間に成立させる。ダブルタップを待たない（待つと約 300 ms 遅れる）。
 * - ダブルタップはコマの間の余白でだけ見分ける。余白の 1 回目のタップは何もしないので、待つ必要がない。
 * - 移動量が touchSlop を超えたらタップを取り消し、縦の移動量が [swipeDistance] を超えたらスワイプにする。
 * - 横の移動量が [swipeDistance] を超え、縦より大きければ、コマの上の左右のスワイプにする（余白の上では何もしない）。
 * - 2 本目の指が触れたら、2 本指のスワイプかピンチとして扱う。
 */
fun Modifier.homeGestures(
    geometry: State<HomeGeometry?>,
    enabled: State<Boolean>,
    handler: HomeGestureHandler,
): Modifier = pointerInput(handler) {
    val swipeDistance = 56.dp.toPx()
    var lastGutterTapAt = 0L
    var lastGutterTapPos = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!enabled.value) return@awaitEachGesture
        val geo = geometry.value ?: return@awaitEachGesture
        // ほかのアプリのウィジェットの上のタップはウィジェットのビューが受けるので、下のコマのタップにしない。
        // 内蔵のウィジェット（時計・テキスト）はコマに描いた文字なので、以前と同じく下のコマのタップにする
        val widget = geo.widgetAt(down.position.x, down.position.y)
        val viewWidget = widget?.takeIf { it.spec.builtin == null }
        val panel = if (viewWidget != null) null else geo.panelAt(down.position.x, down.position.y)
        panel?.let(handler::onPanelPress)

        val outcome = trackGesture(down.position, viewConfiguration.touchSlop, swipeDistance, viewConfiguration.longPressTimeoutMillis, handler::onSwipeUpStart)
        when (outcome) {
            is Outcome.Tap -> {
                if (viewWidget != null) {
                    // ウィジェットのビューが受ける
                } else if (panel != null) {
                    handler.onPanelRelease(panel, tapped = true, down.position)
                } else {
                    val now = down.uptimeMillis
                    val near = (down.position - lastGutterTapPos).getDistance() < swipeDistance
                    if (now - lastGutterTapAt < viewConfiguration.doubleTapTimeoutMillis && near) {
                        handler.onGesture(GestureKind.DoubleTapGutter, down.position)
                        lastGutterTapAt = 0L
                    } else {
                        lastGutterTapAt = now
                        lastGutterTapPos = down.position
                    }
                }
            }

            is Outcome.LongPress -> {
                if (widget != null) {
                    panel?.let { handler.onPanelRelease(it, tapped = false, down.position) }
                    handler.onWidgetLongPress(widget)
                } else if (panel != null) {
                    handler.onPanelRelease(panel, tapped = false, down.position)
                    handler.onPanelLongPress(panel, down.position)
                } else {
                    handler.onGesture(GestureKind.LongPressGutter, down.position)
                }
                // 長押しの後の指の動きは、長押しのメニューやドラッグに任せる
            }

            is Outcome.Gesture -> {
                panel?.let { handler.onPanelRelease(it, tapped = false, down.position) }
                handler.onGesture(outcome.kind, down.position)
            }

            is Outcome.HorizontalSwipe ->
                panel?.let {
                    handler.onPanelRelease(it, tapped = false, down.position)
                    handler.onPanelSwipe(it, outcome.toLeft)
                }

            Outcome.Nothing -> panel?.let { handler.onPanelRelease(it, tapped = false, down.position) }
        }
    }
}

private sealed interface Outcome {
    data object Tap : Outcome

    data object LongPress : Outcome

    data class Gesture(
        val kind: GestureKind,
    ) : Outcome

    data class HorizontalSwipe(
        val toLeft: Boolean,
    ) : Outcome

    data object Nothing : Outcome
}

private suspend fun AwaitPointerEventScope.trackGesture(
    start: Offset,
    slop: Float,
    swipeDistance: Float,
    longPressMs: Long,
    onSwipeUpStart: () -> Unit,
): Outcome {
    var moved = false
    var swipingUp = false
    var multi = false
    var multiStartCentroid = Offset.Zero
    var multiStartSpread = 0f
    var lastCentroid = Offset.Zero
    var lastSpread = 0f
    var last = start

    val result =
        withTimeoutOrNull(longPressMs) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size >= 2) {
                    val c = pressed.fold(Offset.Zero) { acc, ch -> acc + ch.position } / pressed.size.toFloat()
                    val spread = hypot(pressed[0].position.x - pressed[1].position.x, pressed[0].position.y - pressed[1].position.y)
                    if (!multi) {
                        multi = true
                        multiStartCentroid = c
                        multiStartSpread = spread
                    }
                    lastCentroid = c
                    lastSpread = spread
                    continue
                }
                if (multi && pressed.isEmpty()) {
                    val dy = lastCentroid.y - multiStartCentroid.y
                    return@withTimeoutOrNull when {
                        multiStartSpread > 0f && lastSpread / multiStartSpread < 0.7f -> Outcome.Gesture(GestureKind.PinchIn)
                        dy > swipeDistance -> Outcome.Gesture(GestureKind.TwoFingerSwipeDown)
                        dy < -swipeDistance -> Outcome.Gesture(GestureKind.TwoFingerSwipeUp)
                        else -> Outcome.Nothing
                    }
                }
                if (multi) continue
                val change = event.changes.firstOrNull() ?: continue
                if (change.pressed) {
                    last = change.position
                    if ((last - start).getDistance() > slop) moved = true
                    val d = last - start
                    if (moved && !swipingUp && d.y < 0 && abs(d.y) > abs(d.x)) {
                        swipingUp = true
                        onSwipeUpStart()
                    }
                    continue
                }
                // 指が離れた
                val d = change.position - start
                return@withTimeoutOrNull when {
                    !moved -> Outcome.Tap

                    abs(d.y) > swipeDistance && abs(d.y) > abs(d.x) ->
                        Outcome.Gesture(if (d.y < 0) GestureKind.SwipeUp else GestureKind.SwipeDown)

                    abs(d.x) > swipeDistance && abs(d.x) > abs(d.y) -> Outcome.HorizontalSwipe(toLeft = d.x < 0)

                    else -> Outcome.Nothing
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Outcome.Nothing
        }
    if (result != null) return result
    // 長押しの時間が過ぎた。動いていなければ長押し、動いていればスワイプの続きを待つ
    if (!moved && !multi) return Outcome.LongPress
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        if (event.changes.none { it.pressed }) {
            val end = event.changes.firstOrNull()?.position ?: last
            val d = end - start
            return when {
                multi -> Outcome.Nothing
                abs(d.y) > swipeDistance && abs(d.y) > abs(d.x) -> Outcome.Gesture(if (d.y < 0) GestureKind.SwipeUp else GestureKind.SwipeDown)
                abs(d.x) > swipeDistance && abs(d.x) > abs(d.y) -> Outcome.HorizontalSwipe(toLeft = d.x < 0)
                else -> Outcome.Nothing
            }
        }
    }
}
