package dev.furunov.komalauncher.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.furunov.komalauncher.model.apps.AppKey
import dev.furunov.komalauncher.ui.dnd.DragItem
import dev.furunov.komalauncher.ui.dnd.DragSource
import dev.furunov.komalauncher.ui.dnd.LocalDragDrop
import dev.furunov.komalauncher.ui.dnd.draggableApp
import dev.furunov.komalauncher.ui.icons.AppIcon
import kotlin.math.roundToInt

/**
 * コマの近くに開くフォルダ。コマの中心から広がるように開く。
 * 画面の端やシステムバーにかからないよう、位置を内側へ寄せる。
 */
@Composable
internal fun FolderPopup(
    panel: PanelGeometry,
    state: HomeUiState,
    screenWidth: Float,
    screenHeight: Float,
    onEvent: (HomeEvent) -> Unit,
    labelOf: (AppKey) -> String,
    dropInfo: FolderDropInfo,
) {
    val dnd = LocalDragDrop.current
    DisposableEffect(panel.panel.id) { onDispose { dropInfo.clear() } }
    val appearance = state.settings.appearance
    val apps = state.folders.appsOf(panel.panel.id.value)
    val name = state.folders.byPanel[panel.panel.id.value]?.name ?: panel.panel.label
    val density = LocalDensity.current
    val insets = WindowInsets.safeDrawing
    val margin = with(density) { 12.dp.toPx() }
    val top = insets.getTop(density) + margin
    val bottom = screenHeight - insets.getBottom(density) - margin

    BackHandler { onEvent(HomeEvent.Dismiss) }

    val appear = remember(panel.panel.id) { Animatable(0f) }
    LaunchedEffect(panel.panel.id) { appear.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 700f)) }

    var size by remember { mutableStateOf(IntSize.Zero) }
    val center = panel.polygon.centroid
    val x = (center.x - size.width / 2f).coerceIn(margin, (screenWidth - size.width - margin).coerceAtLeast(margin))
    val y = (center.y - size.height / 2f).coerceIn(top, (bottom - size.height).coerceAtLeast(top))
    val origin =
        TransformOrigin(
            ((center.x - x) / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
            ((center.y - y) / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
        )

    // フォルダから引き出したら、フォルダを透明にしてホームのコマを見せる。
    // 取り除くと指の追跡が途切れるので、合成したまま見えなくする
    val draggedOut = dnd.isDragging && dropInfo.popupRect?.contains(dnd.position) == false
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (draggedOut) 0f else 1f }
            .background(Color.Black.copy(alpha = 0.35f * appear.value))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onEvent(HomeEvent.Dismiss) },
    ) {
        Column(
            Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .onSizeChanged { size = it }
                .onGloballyPositioned { dropInfo.popupRect = it.boundsInRoot() }
                .graphicsLayer {
                    scaleX = 0.6f + 0.4f * appear.value
                    scaleY = 0.6f + 0.4f * appear.value
                    alpha = appear.value
                    transformOrigin = origin
                }.background(Color(0xFF1E1E24).copy(alpha = appearance.folderOpacity), RoundedCornerShape(20.dp))
                // 背景のタップでフォルダが閉じないよう、内側のタップをここで受け止める
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(16.dp),
        ) {
            if (name != null) {
                BasicText(name, style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(12.dp))
            }
            if (apps.isEmpty()) {
                BasicText(
                    "フォルダは空です。アプリ一覧でアイコンを長押しし、ここへドラッグして追加できます",
                    style = TextStyle(color = Color(0xFFBBBBBB), fontSize = 13.sp),
                    modifier = Modifier.width(220.dp),
                )
            }
            apps.chunked(appearance.folderColumns).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { c, app ->
                        val index = r * appearance.folderColumns + c
                        FolderItem(
                            app = app,
                            label = labelOf(app),
                            iconDp = appearance.iconSizeDp,
                            showLabel = appearance.showLabels,
                            dragging = dnd.item?.app == app && dnd.isDragging,
                            modifier =
                            Modifier
                                .onGloballyPositioned { dropInfo.itemCenters[index] = it.boundsInRoot().center }
                                .draggableApp(DragItem(app, DragSource.Folder(panel.panel.id.value)), dnd) {
                                    onEvent(HomeEvent.Launch(app))
                                },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** フォルダの中で落とした位置を決めるための、画面のルートの座標での位置。描画のたびに更新する。 */
class FolderDropInfo {
    var popupRect: androidx.compose.ui.geometry.Rect? = null
    val itemCenters = mutableMapOf<Int, androidx.compose.ui.geometry.Offset>()

    /** [p] に最も近いアイコンの位置。並べ替えの挿入位置に使う。 */
    fun indexAt(p: androidx.compose.ui.geometry.Offset): Int? = itemCenters.minByOrNull { (it.value - p).getDistanceSquared() }?.key

    fun clear() {
        popupRect = null
        itemCenters.clear()
    }
}

@Composable
private fun FolderItem(
    app: AppKey,
    label: String,
    iconDp: Int,
    showLabel: Boolean,
    dragging: Boolean,
    modifier: Modifier,
) {
    Column(
        modifier
            .width((iconDp + 20).dp)
            .graphicsLayer { alpha = if (dragging) 0.3f else 1f }
            .padding(vertical = 4.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        AppIcon(app, iconDp.dp)
        if (showLabel) {
            BasicText(
                label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
