package dev.furunov.komalauncher.ui.dnd

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.furunov.komalauncher.model.apps.AppKey
import dev.furunov.komalauncher.ui.icons.AppIcon
import kotlin.math.roundToInt

/** どこからドラッグしてきたか。落とした先での扱い（追加か移動か）が変わる。 */
sealed interface DragSource {
    data object Drawer : DragSource

    data class Folder(
        val panelId: String,
    ) : DragSource
}

data class DragItem(
    val app: AppKey,
    val source: DragSource,
)

/** 落とした先の判断は画面ごとに違うので、ここでは座標とドラッグしたものを渡すだけにする。 */
fun interface DropHandler {
    fun onDrop(
        item: DragItem,
        position: Offset,
    )
}

/**
 * 全アプリ一覧・フォルダ・ホームの間でアイコンをドラッグするときの状態。画面全体で 1 つだけ持つ。
 * ドラッグしている間も元の画面（一覧やフォルダ）は合成されたまま透明にしておく。
 * 取り除くと指の追跡（pointerInput）が途切れ、ドラッグが取り消されるため。
 */
@Stable
class DragDropState(
    private val onDrop: DropHandler,
    /**
     * 長押しが成立したとき。すぐメニューを表示する（Pixel のランチャーと同じ）。
     * 前は動かさずに離したときに表示していたが、長押しの手応えが遅いので変えた（2026-11-22、利用者の選択）。
     */
    private val onLongPress: (DragItem) -> Unit,
    /** 長押しの後に指が [MENU_SLOP_PX] より動いたとき（運び始めたとき）。表示したメニューを消す。 */
    private val onDragBegin: () -> Unit = {},
) {
    var item: DragItem? by mutableStateOf(null)
        private set

    /** 画面のルートの座標での指の位置。 */
    var position: Offset by mutableStateOf(Offset.Zero)
        private set

    // isDragging が読むので Compose の状態にする（ただの変数だと、動かしても透明化などが更新されない）
    private var travelled by mutableFloatStateOf(0f)

    val isDragging: Boolean get() = item != null && travelled > MENU_SLOP_PX

    /** [showMenu] が false なら、長押しのメニューを出さない（全アプリ一覧のフォルダの中から、ドラッグの途中で引き継ぐとき）。 */
    fun start(
        item: DragItem,
        at: Offset,
        showMenu: Boolean = true,
    ) {
        this.item = item
        position = at
        travelled = 0f
        if (showMenu) onLongPress(item)
    }

    fun move(delta: Offset) {
        val wasDragging = isDragging
        position += delta
        travelled += delta.getDistance()
        if (!wasDragging && isDragging) onDragBegin()
    }

    fun end() {
        val i = item ?: return
        // 動かさずに離したら何もしない（メニューは長押しの時点で表示している）
        if (travelled > MENU_SLOP_PX) onDrop.onDrop(i, position)
        item = null
    }

    fun cancel() {
        item = null
    }

    companion object {
        /** 長押しの後にこれより動いたら、ドラッグの操作とみなしてメニューを消す。これより動かずに離したら、メニューを残す。 */
        const val MENU_SLOP_PX = 24f
    }
}

val LocalDragDrop = staticCompositionLocalOf<DragDropState> { error("DragDropState が渡されていない") }

/**
 * タップで [onTap]、長押しで運び始める。1 つの指の追跡で両方を見分ける。
 * タップの検出とドラッグの検出を別々に重ねると、長押しの後に指を離したときタップとしても扱われるため。
 * 長押しの前に指が動いたら何もしない（一覧のスクロールに任せる）。
 */
fun Modifier.draggableApp(
    item: DragItem,
    state: DragDropState,
    onTap: () -> Unit,
): Modifier = composed {
    var origin by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .pointerInput(item) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val slop = viewConfiguration.touchSlop
                // 長押しの時間まで指を見張る。awaitLongPressOrCancellation は使わない。
                // 親の clickable（フォルダの背景のタップを止めるもの）が消費したイベントを取り消しとみなし、
                // タップを拾えなくなるため
                val early =
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var result = Early.Cancel
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                result = Early.Tap
                                break
                            }
                            // 長押しの前に動いたら、スクロールなどに任せる
                            if ((change.position - down.position).getDistance() > slop) break
                        }
                        result
                    }
                when (early) {
                    Early.Tap -> onTap()

                    Early.Cancel -> Unit

                    null -> {
                        // 長押しが成立した。ここからドラッグを始める
                        state.start(item, origin + down.position)
                        val finished =
                            drag(down.id) {
                                state.move(it.positionChange())
                                it.consume()
                            }
                        if (finished) state.end() else state.cancel()
                    }
                }
            }
        }
}

private enum class Early { Tap, Cancel }

/** ドラッグしているアイコン。画面のいちばん上に重ねる。 */
@Composable
fun DragShadow(state: DragDropState) {
    val item = state.item ?: return
    if (!state.isDragging) return
    val half = with(LocalDensity.current) { 32.dp.toPx() }
    AppIcon(
        item.app,
        64.dp,
        Modifier
            .offset { IntOffset((state.position.x - half).roundToInt(), (state.position.y - half * 2.2f).roundToInt()) }
            .graphicsLayer {
                scaleX = 1.1f
                scaleY = 1.1f
                alpha = 0.92f
            },
    )
}
