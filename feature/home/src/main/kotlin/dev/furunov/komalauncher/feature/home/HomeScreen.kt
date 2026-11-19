package dev.furunov.komalauncher.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.furunov.komalauncher.feature.home.anim.MotionContext
import dev.furunov.komalauncher.feature.home.anim.PanelAnimations
import dev.furunov.komalauncher.feature.home.anim.PanelMotion
import dev.furunov.komalauncher.model.layout.PanelContent
import dev.furunov.komalauncher.model.settings.GestureKind
import dev.furunov.komalauncher.ui.dnd.DragDropState
import dev.furunov.komalauncher.ui.dnd.DragItem
import dev.furunov.komalauncher.ui.dnd.DragShadow
import dev.furunov.komalauncher.ui.dnd.DragSource
import dev.furunov.komalauncher.ui.dnd.LocalDragDrop
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** ホーム画面の外から差し込む部分。機能のモジュールどうしが互いを知らずに済むよう、:app が渡す。 */
class HomeSlots(
    /** ウィジェットのコマの中身。[PanelGeometry.inscribed] の位置に置く。 */
    val widget: @Composable (PanelGeometry) -> Unit = {},
    /** フォルダ以外の重ねる画面（全アプリ一覧、ホームのメニュー）。 */
    val overlay: @Composable (Overlay) -> Unit = {},
    /** コマの長押し。フォルダの名前の変更やウィジェットの選択を出す。 */
    val onPanelLongPress: (PanelGeometry) -> Unit = {},
)

@Composable
fun HomeScreen(
    state: HomeUiState,
    onEvent: (HomeEvent) -> Unit,
    unlockPending: StateFlow<Boolean>,
    onUnlockPlayed: () -> Unit,
    slots: HomeSlots,
    modifier: Modifier = Modifier,
) {
    val gutter = Color(state.settings.appearance.gutterColor)
    BoxWithConstraints(modifier.fillMaxSize().background(gutter)) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val geometry = remember(state.layout, wPx, hPx) { HomeGeometry.of(state.layout, wPx, hPx) }
        val geometryState = rememberUpdatedState<HomeGeometry?>(geometry)
        val motions = remember(state.layout) { state.layout.panels.associate { it.id to PanelMotion() } }
        val scope = rememberCoroutineScope()
        val art by rememberArt(state.imagePath, geometry.transform.imageRect.width, geometry.transform.imageRect.height)
        val anim = state.settings.animation
        val pressAnim = PanelAnimations.press(anim.press)
        val currentState = rememberUpdatedState(state)
        val dropInfo = remember { FolderDropInfo() }
        val removeZoneBottom = with(density) { REMOVE_ZONE_HEIGHT.toPx() } + WindowInsets.statusBars.getTop(density)

        fun dropTargetAt(item: DragItem, p: Offset): DropTarget {
            val from = (item.source as? DragSource.Folder)?.panelId
            val overlay = currentState.value.overlay
            return when {
                from != null && p.y < removeZoneBottom -> DropTarget.Remove

                overlay is Overlay.Folder && dropInfo.popupRect?.contains(p) == true ->
                    DropTarget.Panel(overlay.panelId.value, dropInfo.indexAt(p))

                else ->
                    geometry.panelAt(p.x, p.y)?.takeIf { it.panel.content is PanelContent.Folder }
                        ?.let { DropTarget.Panel(it.panel.id.value) } ?: DropTarget.Nowhere
            }
        }
        val dnd =
            remember(geometry) {
                DragDropState(
                    onDrop = { item, p -> onEvent(HomeEvent.Drop(item.app, (item.source as? DragSource.Folder)?.panelId, dropTargetAt(item, p))) },
                    onLongPressRelease = { onEvent(HomeEvent.ShowAppMenu(it.app, (it.source as? DragSource.Folder)?.panelId)) },
                )
            }
        // 指の下のフォルダのコマ。運んでいる間だけ光らせる
        val hovered by remember(geometry) {
            derivedStateOf {
                val item = dnd.item
                when {
                    item == null || !dnd.isDragging -> null

                    // 外す場所の上では、その下のコマを光らせない（落としても加わらないので）
                    item.source is DragSource.Folder && dnd.position.y < removeZoneBottom -> null

                    else -> geometry.panelAt(dnd.position.x, dnd.position.y)?.takeIf { it.panel.content is PanelContent.Folder }?.panel?.id
                }
            }
        }

        fun ctx(g: PanelGeometry, order: Int = 0) = MotionContext(g.bounds, wPx, hPx, order, anim.staggerMs, anim.speed)

        val handler =
            remember(geometry, pressAnim, anim) {
                object : HomeGestureHandler {
                    override fun onPanelPress(panel: PanelGeometry) {
                        if (!anim.enabled) return
                        scope.launch { pressAnim.pressDown(motions.getValue(panel.panel.id), ctx(panel)) }
                    }

                    override fun onPanelRelease(panel: PanelGeometry, tapped: Boolean) {
                        val m = motions.getValue(panel.panel.id)
                        if (tapped) {
                            // 動きの完了を待たずに開く。待つとタップへの反応が遅れて感じられる
                            onEvent(HomeEvent.TapPanel(panel.panel.id))
                            if (anim.enabled) scope.launch { pressAnim.pressUp(m, ctx(panel)) }
                        } else if (anim.enabled) {
                            scope.launch { pressAnim.pressCancel(m, ctx(panel)) }
                        }
                    }

                    override fun onPanelLongPress(panel: PanelGeometry, position: Offset) = slots.onPanelLongPress(panel)

                    override fun onGesture(kind: GestureKind, position: Offset) = onEvent(HomeEvent.Gesture(kind))
                }
            }
        val gesturesEnabled = remember { mutableStateOf(true) }
        gesturesEnabled.value = currentState.value.overlay == Overlay.None

        // ロック解除のアニメーション。画面が見えている（RESUMED）ときだけ再生する。
        // 消灯の時点（pending が立った時点）で全コマを隠しておき、解除の直後に前の配置が一瞬見えるのを防ぐ
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(geometry, anim) {
            val enter = PanelAnimations.enter(anim.enter)
            launch {
                unlockPending.collect { pending -> if (pending && anim.enabled) motions.values.forEach { it.alpha.snapTo(0f) } }
            }
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                unlockPending.collect { pending ->
                    if (!pending) return@collect
                    onUnlockPlayed()
                    if (!anim.enabled) {
                        motions.values.forEach { it.reset() }
                        return@collect
                    }
                    geometry.panels.forEach { g ->
                        launch { enter.enter(motions.getValue(g.panel.id), ctx(g, g.index)) }
                    }
                }
            }
        }

        CompositionLocalProvider(LocalDragDrop provides dnd) {
            Box(Modifier.fillMaxSize().homeGestures(geometryState, gesturesEnabled, handler)) {
                for (g in geometry.panels) {
                    PanelLayer(
                        g = g,
                        motion = motions.getValue(g.panel.id),
                        art = art,
                        imageOffset = Offset(geometry.transform.imageRect.left, geometry.transform.imageRect.top),
                        imageSize = Size(geometry.transform.imageRect.width, geometry.transform.imageRect.height),
                        showPlaceholderLabel = state.imagePath == null,
                        label = state.folders.byPanel[g.panel.id.value]?.name ?: g.panel.label,
                        highlight = if (hovered == g.panel.id) Color(state.settings.appearance.highlightColor) else null,
                    )
                    if (g.panel.content is PanelContent.Widget) {
                        PanelBox(g.inscribed.left, g.inscribed.top, g.inscribed.width, g.inscribed.height, motions.getValue(g.panel.id)) {
                            slots.widget(g)
                        }
                    }
                }
            }

            state.layoutError?.let {
                BasicText(
                    it,
                    style = TextStyle(color = Color.White, fontSize = 12.sp),
                    modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(8.dp)
                        .background(Color(0xCCB00020))
                        .padding(8.dp),
                )
            }

            when (val o = state.overlay) {
                is Overlay.Folder -> geometry.panels.firstOrNull { it.panel.id == o.panelId }?.let { g ->
                    FolderPopup(
                        panel = g,
                        state = state,
                        screenWidth = wPx,
                        screenHeight = hPx,
                        onEvent = onEvent,
                        labelOf = state::labelOf,
                        dropInfo = dropInfo,
                    )
                }

                is Overlay.AppMenu -> AppMenuSheet(o, state.labelOf(o.app), onEvent)

                Overlay.None -> Unit

                else -> slots.overlay(o)
            }

            if (dnd.isDragging && dnd.item?.source is DragSource.Folder) {
                RemoveZone(active = dnd.position.y < removeZoneBottom, modifier = Modifier.align(Alignment.TopCenter))
            }
            if (state.undo != null) {
                UndoBar(
                    onUndo = { onEvent(HomeEvent.Undo) },
                    onTimeout = { onEvent(HomeEvent.ClearUndo) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            DragShadow(dnd)
        }
    }
}

/** 画面座標の長方形に、コマの動きに合わせて置く箱。 */
@Composable
private fun PanelBox(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    motion: PanelMotion,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(density) { width.toDp() }, with(density) { height.toDp() })
            .graphicsLayer {
                translationX = motion.dx.value
                translationY = motion.dy.value
                scaleX = motion.scale.value
                scaleY = motion.scale.value
                alpha = motion.alpha.value
            },
    ) { content() }
}

private val placeholderColors =
    listOf(0xFFE57373, 0xFF64B5F6, 0xFF81C784, 0xFFFFB74D, 0xFFBA68C8, 0xFF4DB6AC, 0xFFF06292, 0xFF9575CD).map { Color(it) }

@Composable
private fun PanelLayer(
    g: PanelGeometry,
    motion: PanelMotion,
    art: ImageBitmap?,
    imageOffset: Offset,
    imageSize: Size,
    showPlaceholderLabel: Boolean,
    label: String?,
    highlight: Color?,
) {
    val density = LocalDensity.current
    val b = g.bounds
    val color = placeholderColors[g.index % placeholderColors.size]
    Box(
        Modifier
            .offset { IntOffset(b.left.roundToInt(), b.top.roundToInt()) }
            .size(with(density) { b.width.toDp() }, with(density) { b.height.toDp() })
            .graphicsLayer {
                // 値はここ（描画の段階）で読む。アニメーション中に画面の作り直しを起こさないため
                translationX = motion.dx.value
                translationY = motion.dy.value
                scaleX = motion.scale.value
                scaleY = motion.scale.value
                alpha = motion.alpha.value
                shadowElevation = 4.dp.toPx() + motion.press.value * 10.dp.toPx()
                shape = g.shape
                clip = true
            }.drawBehind {
                if (art != null) {
                    drawImage(
                        art,
                        dstOffset = IntOffset((imageOffset.x - b.left).roundToInt(), (imageOffset.y - b.top).roundToInt()),
                        dstSize = IntSize(imageSize.width.roundToInt(), imageSize.height.roundToInt()),
                    )
                } else {
                    drawRect(color)
                }
                // 押している間は少し暗くする
                if (motion.press.value > 0f) drawRect(Color.Black.copy(alpha = 0.18f * motion.press.value))
                if (highlight != null) drawRect(highlight.copy(alpha = 0.35f))
            },
    ) {
        if (showPlaceholderLabel && label != null) {
            BasicText(
                label,
                style = TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
