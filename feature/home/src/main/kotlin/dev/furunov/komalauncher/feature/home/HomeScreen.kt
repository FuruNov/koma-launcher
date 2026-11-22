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
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.furunov.komalauncher.feature.home.anim.MotionContext
import dev.furunov.komalauncher.feature.home.anim.PanelAnimations
import dev.furunov.komalauncher.feature.home.anim.PanelMotion
import dev.furunov.komalauncher.model.apps.AppKey
import dev.furunov.komalauncher.model.apps.DrawerFolders
import dev.furunov.komalauncher.model.geometry.Rect
import dev.furunov.komalauncher.model.layout.BuiltinWidget
import dev.furunov.komalauncher.model.layout.EditHistory
import dev.furunov.komalauncher.model.layout.LayerKind
import dev.furunov.komalauncher.model.layout.LayerOrder
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.LayoutEdits
import dev.furunov.komalauncher.model.layout.LayoutShape
import dev.furunov.komalauncher.model.layout.PanelContent
import dev.furunov.komalauncher.model.layout.PanelId
import dev.furunov.komalauncher.model.layout.Part
import dev.furunov.komalauncher.model.layout.PartEdits
import dev.furunov.komalauncher.model.layout.ResolvedPanel
import dev.furunov.komalauncher.model.layout.Tilt
import dev.furunov.komalauncher.model.layout.WidgetSpec
import dev.furunov.komalauncher.model.ports.TiltSource
import dev.furunov.komalauncher.model.settings.GestureKind
import dev.furunov.komalauncher.model.settings.LauncherAction
import dev.furunov.komalauncher.model.widgets.BuiltinWidgets
import dev.furunov.komalauncher.model.widgets.WidgetPlacement
import dev.furunov.komalauncher.ui.art.BlankPage
import dev.furunov.komalauncher.ui.art.DepthArtPainter
import dev.furunov.komalauncher.ui.art.DepthLayerConfig
import dev.furunov.komalauncher.ui.art.DepthLayers
import dev.furunov.komalauncher.ui.art.DepthMaps
import dev.furunov.komalauncher.ui.art.DepthStyle
import dev.furunov.komalauncher.ui.art.PanelTextSpec
import dev.furunov.komalauncher.ui.art.PersonMask
import dev.furunov.komalauncher.ui.art.buildPanelTexts
import dev.furunov.komalauncher.ui.art.depthPersonShift
import dev.furunov.komalauncher.ui.art.depthRequests
import dev.furunov.komalauncher.ui.art.drawHaze
import dev.furunov.komalauncher.ui.art.drawPanelTexts
import dev.furunov.komalauncher.ui.art.drawPersonImage
import dev.furunov.komalauncher.ui.art.localPlacement
import dev.furunov.komalauncher.ui.art.rememberArts
import dev.furunov.komalauncher.ui.art.rememberBlurredArts
import dev.furunov.komalauncher.ui.art.rememberDepthLayers
import dev.furunov.komalauncher.ui.art.rememberDepthMaps
import dev.furunov.komalauncher.ui.art.rememberMasks
import dev.furunov.komalauncher.ui.dnd.DragDropState
import dev.furunov.komalauncher.ui.dnd.DragItem
import dev.furunov.komalauncher.ui.dnd.DragShadow
import dev.furunov.komalauncher.ui.dnd.DragSource
import dev.furunov.komalauncher.ui.dnd.LocalDragDrop
import dev.furunov.komalauncher.ui.icons.rememberIconBitmaps
import dev.furunov.komalauncher.ui.icons.rememberIconShadows
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import kotlin.math.roundToInt

/** ホーム画面の外から差し込む部分。機能のモジュールどうしが互いを知らずに済むよう、:app が渡す。 */
class HomeSlots(
    /** 自由に置くウィジェットの中身。引数はウィジェットと、置く大きさ（dp）。 */
    val freeWidget: @Composable (WidgetSpec, Float, Float) -> Unit = { _, _, _ -> },
    /** 自由に置いたウィジェットを、別のウィジェットに入れ替える選択画面を開く。引数はウィジェットの id。 */
    val replaceWidget: (String) -> Unit = {},
    /** ほかのアプリのウィジェットの名前（長押しのメニューの見出しに出す）。引数は provider。分からなければ null。 */
    val widgetLabel: (String) -> String? = { null },
    /** フォルダ以外の重ねる画面（ホームのメニューなど）。 */
    val overlay: @Composable (Overlay) -> Unit = {},
    /** 全アプリ一覧。開く瞬間に一から作らないよう、閉じている間も作っておく。引数は開いているか。 */
    val drawer: @Composable (Boolean) -> Unit = {},
    /** コマの長押しのメニューに、外から加える項目。 */
    val panelMenuItems: (PanelGeometry) -> List<PanelMenuItem> = { emptyList() },
)

class PanelMenuItem(
    val label: String,
    val onClick: () -> Unit,
)

@Composable
fun HomeScreen(
    state: HomeUiState,
    onEvent: (HomeEvent) -> Unit,
    unlockPending: StateFlow<Boolean>,
    onUnlockPlayed: () -> Unit,
    slots: HomeSlots,
    modifier: Modifier = Modifier,
    /** layout.json 以外の知らせ（保存ファイルを読めなかったなど）。 */
    notice: String? = null,
    /** 端末の傾き（視差）。null なら動かさない。 */
    tilt: TiltSource? = null,
    /** 再生中のコマのジャケットと音。null ならジャケットを描かず、棒はそれらしい動きにする。 */
    media: HomeMedia? = null,
    /** レイアウトを書き換える（「配置を調整」で使う）。 */
    editLayout: ((Layout) -> Layout) -> Unit = {},
) {
    // コマの間の色は、レイアウトに書いてあればそれを、無ければ設定の色を使う
    val frameGutter = (state.layout.shape as? LayoutShape.Tree)?.frame?.gutterColor
    val gutter = Color(frameGutter ?: state.settings.appearance.gutterColor)
    BoxWithConstraints(modifier.fillMaxSize().background(gutter)) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val defaultGutter = state.settings.appearance.gutterColor
        val geometry = remember(state.layout, wPx, hPx, defaultGutter) { HomeGeometry.of(state.layout, wPx, hPx, defaultGutter) }
        val geometryState = rememberUpdatedState<HomeGeometry?>(geometry)
        val motions = remember(state.layout) { state.layout.panels.associate { it.id to PanelMotion() } }
        val scope = rememberCoroutineScope()
        val arts by rememberArts(state.images, geometry.artSizes, state.imagesRevision)
        // 試作: 深度マップで絵を画素ごとにずらす層。外部ファイルのディレクトリの depth3d/ に層がある画像だけに使う
        val context = LocalContext.current
        val depth3dDir = remember(context) { context.getExternalFilesDir(null)?.let { java.io.File(it, "depth3d") } }
        // 試作の層の切り抜き（<id>.keep.png、人物以外が不透明）があれば、人物の効果と文字の抜き方もそれに揃える
        val maskPaths =
            remember(state.masks, depth3dDir, state.imagesRevision) {
                state.masks.mapValues { (id, path) -> depth3dDir?.let { java.io.File(it, "$id.keep.png") }?.takeIf { it.isFile }?.path ?: path }
            }
        val masks by rememberMasks(maskPaths, state.imagesRevision)
        // 前景の人物の立体感。影と縁取りの形、ぼかした絵は、その効果を入れているときだけ作る
        val depth = remember(state.layout, wPx) { DepthStyle.of(state.layout, wPx) }
        val depthRequests = remember(depth, geometry, masks) { depthRequests(depth, geometry.panels.mapNotNull { g -> g.image?.let { id -> g.art?.let { id to it.width } } }, masks) }
        val depthMaps by rememberDepthMaps(masks, depthRequests)
        // 試作: はみ出し用の切り抜き（<id>.pop.png）があれば、枠の外へ出すのはその形だけにする（机や料理は人物と一緒に動かすが、はみ出させない）
        val popPaths =
            remember(state.masks, depth3dDir, state.imagesRevision) {
                state.masks.keys.mapNotNull { id -> depth3dDir?.let { java.io.File(it, "$id.pop.png") }?.takeIf { it.isFile }?.let { id to it.path } }.toMap()
            }
        val popMasks by rememberMasks(popPaths, state.imagesRevision)
        val popRequests = remember(depth, geometry, popMasks) { depthRequests(depth, geometry.panels.mapNotNull { g -> g.image?.let { id -> g.art?.let { id to it.width } } }, popMasks) }
        val popDepthMaps by rememberDepthMaps(popMasks, popRequests)
        val blurIds = remember(depth, geometry, masks) { if (depth.spec.blur.enabled) geometry.panels.mapNotNull { it.image }.filter { it in masks }.toSet() else emptySet() }
        val blurred by rememberBlurredArts(state.images, blurIds, state.imagesRevision)
        val imageIds = remember(geometry) { geometry.panels.mapNotNull { it.image }.toSet() }
        val depth3d by rememberDepthLayers(depth3dDir, imageIds, state.imagesRevision)
        val folderNames = remember(state.folders) { state.folders.byPanel.mapNotNull { (id, f) -> f.name?.let { id to it } }.toMap() }
        // アイコンを置くコマと、置けるアイコンの数（フォルダはアプリの数、全アプリ一覧はアイコン 1 つ）
        val iconPanels =
            remember(state.folders, state.layout) {
                state.folders.byPanel.filterValues { it.apps.isNotEmpty() }.mapValues { it.value.apps.size } +
                    state.layout.panels.filter { it.content == PanelContent.Drawer }.associate { it.id.value to 1 }
            }
        // 文字の置き場所は、コマの形・文字の指定・切り抜き（人物の地図）が変わったときだけ探し直す
        val playerPanel = state.player?.panelId
        val texts =
            remember(geometry, state.layout, folderNames, masks, playerPanel, iconPanels) {
                val built = buildPanelTexts(state.layout, geometry.panels.map { ResolvedPanel(it.panel, it.index, it.polygon, it.art) }, wPx, hPx, folderNames, masks, iconPanels)
                // 再生中のコマには名前とアイコンを出さない（時計と大見出しは残す）
                if (playerPanel == null) built else built.mapValues { (id, spec) -> if (id == playerPanel) spec.copy(labels = emptyList(), icons = emptyList()) else spec }
            }
        val playerArt by (media?.art ?: NO_ART).collectAsState()
        val artFade = rememberArtFade(playerArt.takeIf { playerPanel != null })
        var flash by remember { mutableStateOf<PlayerFlashEvent?>(null) }
        val measurer = rememberTextMeasurer(cacheSize = 16)
        // 秒を出す時計があれば毎秒、無ければ毎分進める
        val withSeconds = (state.layout.shape as? LayoutShape.Tree)?.widgets.orEmpty().any { it.builtin == BuiltinWidget.CLOCK && it.style.seconds }
        val now by rememberMinuteClock(withSeconds)
        // 以前の時計と大見出し（clock・text.masthead）を、同じ位置と大きさの内蔵のウィジェットに書き換える（1 度だけ）
        LaunchedEffect(state.layout, wPx, hPx) {
            val panels = geometry.panels.map { ResolvedPanel(it.panel, it.index, it.polygon, it.art) }
            if (BuiltinWidgets.migrate(state.layout, panels, wPx, hPx) != null) editLayout { BuiltinWidgets.migrate(it, panels, wPx, hPx) ?: it }
        }
        val parallax = remember(state.layout, wPx) { parallaxOf(state.layout, wPx) }
        // 一覧やフォルダなどを開いている間は、後ろのホームの視差を止める（手の揺れで描き直し続け、開く動きが重くなるため）
        // 試作の深度の層が動く最大量（画素）。刻みをこれにも合わせ、1 回の描き直しで 1 画素を超えて跳ばないようにする
        val depth3dShift = geometry.panels.maxOfOrNull { g -> if (g.image in depth3d.first) depth3d.second.strength * (g.face.artSize?.width ?: 0) else 0f } ?: 0f
        val shownTilt = rememberShownTilt(tilt, parallax.art > 0f, paused = state.overlay != Overlay.None || state.adjusting, maxShiftPx = maxOf(parallax.art, parallax.clock, parallax.masthead, parallax.icon, depth3dShift))
        val anim = state.settings.animation
        val pressAnim = PanelAnimations.press(anim.press)
        val currentState = rememberUpdatedState(state)
        val currentTexts = rememberUpdatedState(texts)
        // 「配置を調整」で使う、今の配置（コマの位置・文字とアイコンの置き場所）
        val adjustTarget = AdjustTarget(state.layout, geometry.panels.map { ResolvedPanel(it.panel, it.index, it.polygon, it.art) }, texts, wPx, hPx, geometry.widgets, with(density) { 10.dp.toPx() })
        val currentAdjust = rememberUpdatedState<AdjustTarget?>(adjustTarget)
        // 「配置を調整」の間の元に戻す履歴。調整を始めるたびに空にする
        val adjustHistory = remember(state.adjusting) { EditHistory<Layout>() }
        var adjustCanUndo by remember(state.adjusting) { mutableStateOf(false) }
        // 「配置を調整」で最後に触ったコンポーネント。重ね順のボタンはこれに掛ける
        var adjustSelected by remember(state.adjusting) { mutableStateOf<Part?>(null) }
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
                    onLongPress = { onEvent(HomeEvent.ShowAppMenu(it.app, (it.source as? DragSource.Folder)?.panelId, fromDrawer = it.source == DragSource.Drawer)) },
                    onDragBegin = { onEvent(HomeEvent.HideAppMenu) },
                )
            }
        // 指の下のフォルダのコマ。ドラッグしている間だけ光らせる
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

        // 上へスワイプし始めた時刻。全アプリ一覧を開く前に、ホームのスナップショットを先に作る合図（Freeze.kt）
        var freezePrepare by remember { mutableLongStateOf(0L) }

        val handler =
            remember(geometry, pressAnim, anim) {
                object : HomeGestureHandler {
                    override fun onSwipeUpStart() {
                        if (currentState.value.settings.actionFor(GestureKind.SwipeUp) == LauncherAction.OpenDrawer) freezePrepare = System.nanoTime()
                    }

                    override fun onPanelPress(panel: PanelGeometry) {
                        if (!anim.enabled) return
                        scope.launch { pressAnim.pressDown(motions.getValue(panel.panel.id), ctx(panel)) }
                    }

                    override fun onPanelRelease(panel: PanelGeometry, tapped: Boolean, position: Offset) {
                        val m = motions.getValue(panel.panel.id)
                        // アイコンの枠の中を押したら、フォルダを開かずに先頭のアプリを起動する（人物の後ろに隠れた部分も含む）
                        val iconApp = if (tapped) iconAppAt(panel, position, currentTexts.value, currentState.value) else null
                        if (iconApp != null) {
                            onEvent(HomeEvent.Launch(iconApp))
                            if (anim.enabled) scope.launch { pressAnim.pressUp(m, ctx(panel)) }
                        } else if (tapped) {
                            currentState.value.player?.takeIf { it.panelId == panel.panel.id }?.let { flash = PlayerFlashEvent(PlayerFlash.forTap(it.playing), System.nanoTime()) }
                            // 動きの完了を待たずに開く。待つとタップへの反応が遅れて感じられる
                            onEvent(HomeEvent.TapPanel(panel.panel.id))
                            if (anim.enabled) scope.launch { pressAnim.pressUp(m, ctx(panel)) }
                        } else if (anim.enabled) {
                            scope.launch { pressAnim.pressCancel(m, ctx(panel)) }
                        }
                    }

                    // 時計・大見出し・名前・アイコンの上の長押しは「配置を調整」に入る。それ以外はコマのメニュー
                    override fun onPanelLongPress(panel: PanelGeometry, position: Offset) {
                        val onPart = currentAdjust.value?.let { partAt(it, dev.furunov.komalauncher.model.geometry.Point(position.x, position.y)) } != null
                        onEvent(if (onPart) HomeEvent.Adjust(true) else HomeEvent.OpenOverlay(Overlay.PanelMenu(panel.panel.id)))
                    }

                    override fun onWidgetLongPress(widget: WidgetGeometry) = onEvent(HomeEvent.OpenOverlay(Overlay.WidgetMenu(widget.spec.id)))

                    override fun onPanelSwipe(panel: PanelGeometry, toLeft: Boolean) {
                        if (currentState.value.player?.panelId == panel.panel.id) {
                            flash = PlayerFlashEvent(if (toLeft) PlayerFlash.PREVIOUS else PlayerFlash.NEXT, System.nanoTime())
                        }
                        onEvent(HomeEvent.SwipePanel(panel.panel.id, toLeft))
                    }

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
            val adjustRecord = {
                adjustHistory.push(currentState.value.layout)
                adjustCanUndo = true
            }
            val gestures =
                if (state.adjusting) {
                    Modifier.adjustGestures(currentAdjust, adjustRecord, editLayout, onSelect = { adjustSelected = it })
                } else {
                    Modifier.homeGestures(geometryState, gesturesEnabled, handler)
                }
            // 全アプリ一覧を開いている間は、ホームをスナップショットの画像で描く（Freeze.kt）。アイコンをホームへドラッグしている間は、ドラッグ先を見せるため普段どおり描く
            Box(Modifier.fillMaxSize().then(gestures).freezable(state.overlay.overDrawer && !dnd.isDragging, freezePrepare)) {
                for (g in geometry.panels) {
                    PanelLayer(
                        g = g,
                        motion = motions.getValue(g.panel.id),
                        art = g.image?.let(arts::get),
                        person = g.image?.let(masks::get),
                        depth = depth,
                        depthMaps = g.image?.let(depthMaps::get),
                        blurred = g.image?.let(blurred::get),
                        depthLayers = g.image?.let(depth3d.first::get),
                        depthConfig = depth3d.second,
                        texts = texts[g.panel.id],
                        icons = panelIcons(g, texts[g.panel.id], state),
                        measurer = measurer,
                        now = { now },
                        tilt = { shownTilt.value },
                        parallax = parallax,
                        label = state.folders.byPanel[g.panel.id.value]?.name ?: g.panel.label,
                        highlight = if (hovered == g.panel.id) Color(state.settings.appearance.highlightColor) else null,
                        player = state.player?.takeIf { it.panelId == g.panel.id }?.let { p -> artFade to p.playing },
                    )
                    state.player?.takeIf { it.panelId == g.panel.id }?.let { p ->
                        PlayerLayer(
                            g = g,
                            motion = motions.getValue(g.panel.id),
                            playing = p.playing,
                            barColor = Color(state.settings.appearance.highlightColor),
                            spectrum = media?.spectrum,
                            flash = flash,
                        )
                    }
                }
                // 自由に置くウィジェットは、すべてのコマの上に描く。人物の後ろのものを描き、人物を描き直し、人物の前のものを描く
                // 内蔵のウィジェット（時計・テキスト）はコマの文字の層で描くので、ここではほかのアプリのウィジェットだけを描く
                val (frontWidgets, behindWidgets) = geometry.widgets.filter { it.spec.builtin == null }.partition { it.spec.front }
                val widgetRects = behindWidgets.map { it.bounds }
                val widgetBox: @Composable (WidgetGeometry) -> Unit = { w ->
                    val r = w.bounds
                    key(w.spec.id) {
                        Box(
                            Modifier
                                .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                                .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() }),
                        ) {
                            slots.freeWidget(w.spec, r.width / density.density, r.height / density.density)
                            // 「配置を調整」の間は、ウィジェットのビューにタッチを渡さない（動かす指でウィジェットを押さない）。
                            // 上に重ねた層が受けるので、下のビューには届かず、親の調整の操作には届く
                            if (state.adjusting) Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
                        }
                    }
                }
                behindWidgets.forEach { widgetBox(it) }
                // ウィジェットに重なる人物を、ウィジェットの上に描き直す（ウィジェットが人物の後ろに回って見える）
                for (g in geometry.panels) {
                    if (widgetRects.none { it.left < g.bounds.right && g.bounds.left < it.right && it.top < g.bounds.bottom && g.bounds.top < it.bottom } || state.player?.panelId == g.panel.id) continue
                    val art = g.image?.let(arts::get) ?: continue
                    val person = g.image.let(masks::get) ?: continue
                    val personShift = depth3d.first[g.image]?.let { l -> g.face.artSize?.let { s -> depthPersonShift(l, depth3d.second, s.width.toFloat()) } }
                    PersonOverWidgets(g, motions.getValue(g.panel.id), art, person, widgetRects, { shownTilt.value }, personShift ?: parallax.art)
                }
                // 枠の外へはみ出す人物は、どのコマよりも上に描く（隣のコマにかかる分も見せる）。タップの判定はコマの形のまま
                for (g in geometry.panels) {
                    if (!g.panel.pop || state.player?.panelId == g.panel.id) continue
                    val art = g.image?.let(arts::get) ?: continue
                    val popMask = popMasks[g.image]
                    val person = popMask ?: g.image.let(masks::get) ?: continue
                    // 試作の層で描くコマは、人物を板として動かす量に合わせる
                    val personShift = depth3d.first[g.image]?.let { l -> g.face.artSize?.let { s -> depthPersonShift(l, depth3d.second, s.width.toFloat()) } }
                    PopLayer(g, motions.getValue(g.panel.id), art, person, if (popMask != null) popDepthMaps[g.image] else depthMaps[g.image], depth, { shownTilt.value }, parallax, personShift)
                }
                // 人物の前のウィジェットは、はみ出した人物より上に描く
                frontWidgets.forEach { widgetBox(it) }
            }

            listOfNotNull(state.layoutError, notice).joinToString("\n").takeIf { it.isNotEmpty() }?.let {
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

            // 一覧の上に重ねたメニューの間も、一覧を開いたままにする
            slots.drawer(state.overlay.overDrawer)
            // 一覧の上にメニューを重ねたら、キーボードを閉じる（中央のメニューの下半分を隠すため）。名前の入力では入力に使うので閉じない
            val keyboard = LocalSoftwareKeyboardController.current
            LaunchedEffect(state.overlay) {
                val o = state.overlay
                if (o.overDrawer && o != Overlay.Drawer && o !is Overlay.DrawerFolderName) keyboard?.hide()
            }
            @Composable
            fun overlayContent(o: Overlay) {
                when (o) {
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

                    is Overlay.PanelMenu -> geometry.panels.firstOrNull { it.panel.id == o.panelId }?.let { g ->
                        PanelMenuSheet(
                            g,
                            state.folders.byPanel[g.panel.id.value]?.name ?: g.panel.label,
                            state.folders.byPanel[g.panel.id.value]?.directLaunch == true,
                            musicPlayer = state.folders.byPanel[g.panel.id.value]?.musicPlayer == true,
                            showingPlayer = state.player?.panelId == g.panel.id,
                            slots.panelMenuItems(g),
                            onEvent,
                            linkedName = state.folders.byPanel[g.panel.id.value]?.drawerFolder?.let { DrawerFolders.nameOf(it, state.settings.drawer) },
                        )
                    }

                    is Overlay.WidgetMenu ->
                        WidgetMenuSheet(
                            title =
                            geometry.widgets.firstOrNull { it.spec.id == o.widgetId }?.spec?.let { spec ->
                                when (spec.builtin) {
                                    BuiltinWidget.CLOCK -> "時計"
                                    BuiltinWidget.TEXT -> "テキスト"
                                    null -> slots.widgetLabel(spec.provider)
                                }
                            } ?: "ウィジェット",
                            builtin = geometry.widgets.firstOrNull { it.spec.id == o.widgetId }?.spec?.builtin != null,
                            onStyle = { onEvent(HomeEvent.OpenOverlay(Overlay.WidgetStyle(o.widgetId))) },
                            onAdjust = { onEvent(HomeEvent.Adjust(true)) },
                            onReplace = {
                                onEvent(HomeEvent.Dismiss)
                                slots.replaceWidget(o.widgetId)
                            },
                            onRemove = {
                                editLayout { WidgetPlacement.remove(it, o.widgetId) }
                                onEvent(HomeEvent.Dismiss)
                            },
                            onEvent = onEvent,
                        )

                    is Overlay.WidgetStyle ->
                        geometry.widgets.firstOrNull { it.spec.id == o.widgetId }?.let { w ->
                            WidgetStyleSheet(w.spec, onChange = { style -> editLayout { WidgetPlacement.setStyle(it, o.widgetId, style) } }, onEvent = onEvent)
                        }

                    Overlay.MusicAccess -> MusicAccessSheet(onEvent)

                    is Overlay.ConfirmRemove -> ConfirmRemoveSheet(o.app, state.labelOf(o.app), o.failed, onEvent)

                    is Overlay.Rename -> RenameSheet(state.folders.byPanel[o.panelId.value]?.name ?: state.layout.panel(o.panelId)?.label, { onEvent(HomeEvent.Rename(o.panelId, it)) }, onEvent)

                    is Overlay.DrawerFolderMenu -> DrawerFolderMenuSheet(o, onEvent)

                    is Overlay.LinkPanel -> LinkPanelSheet(o, onEvent)

                    is Overlay.DrawerFolderName -> RenameSheet(o.current, { onEvent(HomeEvent.NameDrawerFolder(o.app, o.folderId, it)) }, onEvent)

                    Overlay.None -> Unit

                    else -> slots.overlay(o)
                }
            }
            // 長押しのメニューは、長押しした元の画面（フォルダ）を下に残したまま重ねる。
            // 元の画面は、メニューの有無にかかわらず同じ場所で描く。別の場所で描き直すと作り直しになり、
            // 押さえているアイコンの指の操作が途切れて入れ替えができなかった（2026-11-22 に実機で確認）
            val menu = state.overlay as? Overlay.AppMenu
            overlayContent(menu?.under ?: state.overlay)
            menu?.let { AppMenuSheet(it, state.labelOf(it.app), onEvent) }

            if (state.adjusting) {
                AdjustOverlay(
                    target = adjustTarget,
                    canUndo = adjustCanUndo,
                    onUndo = {
                        adjustHistory.undo(currentState.value.layout)?.let { prev -> editLayout { prev } }
                        adjustCanUndo = adjustHistory.canUndo
                    },
                    onDone = { onEvent(HomeEvent.Adjust(false)) },
                    selected = adjustSelected,
                    onReorder = { move ->
                        adjustSelected?.let { part ->
                            adjustRecord()
                            val t = adjustTarget
                            // そのコマにあるもの。無いものは 1 つ前・後ろへ動かすときに飛ばす
                            val present = { id: PanelId ->
                                val spec = t.texts[id]
                                val image = t.panels.firstOrNull { it.panel.id == id }?.panel?.art?.image
                                setOfNotNull(
                                    LayerKind.ICONS.takeIf { spec?.icons?.isNotEmpty() == true },
                                    LayerKind.LABEL.takeIf { spec?.labels?.isNotEmpty() == true },
                                    LayerKind.MASTHEAD.takeIf { spec?.mastheads?.isNotEmpty() == true },
                                    LayerKind.CLOCK.takeIf { spec?.clocks?.isNotEmpty() == true },
                                    LayerKind.PERSON.takeIf { image != null && image in masks },
                                )
                            }
                            val builtin = (part as? Part.Widget)?.start?.builtin
                            if (part is Part.Widget && builtin == null) {
                                editLayout { WidgetPlacement.reorder(it, part.start.id, move) }
                            } else if (part is Part.Widget && builtin != null) {
                                // 内蔵のウィジェットはコマの文字の層で描くので、かかるコマすべての重ね順で動かす
                                val s = part.start
                                val layer = if (builtin == BuiltinWidget.CLOCK) LayerKind.CLOCK else LayerKind.MASTHEAD
                                val ids =
                                    t.panels.filter { p ->
                                        val b = p.polygon.bounds
                                        s.x * t.viewWidth < b.right && b.left < (s.x + s.w) * t.viewWidth && s.y * t.viewHeight < b.bottom && b.top < (s.y + s.h) * t.viewHeight
                                    }.map { it.panel.id }
                                editLayout { l -> ids.fold(l) { acc, id -> LayoutEdits.updatePanel(acc, id) { p -> p.copy(order = LayerOrder.move(p.order, layer, move, present(id)).takeIf { it != LayerOrder.DEFAULT }) } } }
                            } else {
                                editLayout { PartEdits.reorder(it, t.panels, part, move, present) }
                            }
                        }
                    },
                )
            }
            if (dnd.isDragging && dnd.item?.source is DragSource.Folder) {
                RemoveZone(active = dnd.position.y < removeZoneBottom, modifier = Modifier.align(Alignment.TopCenter))
            }
            if (state.undo != null) {
                UndoBar(
                    undoKey = state.undo,
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
internal fun PanelBox(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    motion: PanelMotion,
    /** null でなければ、この形で切り抜く（外接長方形の左上が原点）。 */
    clipShape: androidx.compose.ui.graphics.Shape? = null,
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
                if (clipShape != null) {
                    shape = clipShape
                    clip = true
                }
            },
    ) { content() }
}

@Composable
private fun PanelLayer(
    g: PanelGeometry,
    motion: PanelMotion,
    art: ImageBitmap?,
    /** 人物の切り抜き。文字を人物の後ろに回すのと、立体感の効果に使う。 */
    person: PersonMask?,
    depth: DepthStyle,
    depthMaps: DepthMaps?,
    /** ぼかした絵（立体感の C）。 */
    blurred: ImageBitmap?,
    /** 試作: 深度マップで絵を画素ごとにずらす層。null なら絵を一様にずらす。 */
    depthLayers: DepthLayers?,
    depthConfig: DepthLayerConfig,
    texts: PanelTextSpec?,
    /** 先頭のアプリと、そのアイコンを置く場所（コマの左上が原点の画素）。 */
    icons: List<PanelIcon>,
    measurer: TextMeasurer,
    /** 時計の時刻。描画の段階で読む（毎分、そのコマだけを描き直す）。 */
    now: () -> LocalDateTime,
    /** 画面に描く傾き。描画の段階で読む（傾きが変わったら、作り直しでなく描き直しだけにする）。 */
    tilt: () -> Tilt,
    parallax: ParallaxAmounts,
    label: String?,
    highlight: Color?,
    /** 再生中のコマなら、ジャケットのクロスフェードと、再生中か（false なら一時停止中）。 */
    player: Pair<ArtFade, Boolean>? = null,
) {
    val density = LocalDensity.current
    val b = g.bounds
    // どのコマに何のアプリがあるか分かるよう、フォルダの先頭のアプリのアイコンを背景の空いた所に描く。
    // 文字と同じ層で描き、人物の切り抜きで抜いて人物の後ろに回す（描画の段階で読むので、読み込めたら描き直す）
    val iconBitmaps = rememberIconBitmaps(icons.map { it.app to it.rect.width.roundToInt() })
    val iconShadows = rememberIconShadows(icons.map { it.app to it.rect.width.roundToInt() })
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
                shadowElevation = if ("elevation" in HomeDebug.off) 0f else 4.dp.toPx() + motion.press.value * 10.dp.toPx()
                shape = g.shape
                clip = true
            },
    ) {
        // 傾きは層の位置（graphicsLayer）でだけ読む。中身は傾きを読まないので、傾きが変わっても描画の命令を作り直さない
        val artShift = { tilt().let { t -> Offset(t.x * parallax.art, t.y * parallax.art) } }
        fun shift(k: Float): () -> Offset = { tilt().let { t -> Offset(-t.x * k, -t.y * k) } }
        val margin = parallax.art + 2f
        // 試作: 層があれば、絵を画素ごとにずらして描く。再生中はジャケットで絵が隠れるので使わない
        val face = g.face
        val off = HomeDebug.off
        val painter =
            remember(art, depthLayers, face, depthConfig, "depth3d" in off) {
                val o = face.artOffset
                val s = face.artSize
                if (art != null && depthLayers != null && o != null && s != null && "depth3d" !in off) DepthArtPainter(art.asAndroidBitmap(), depthLayers, Offset(o.x.toFloat(), o.y.toFloat()), s.width.toFloat(), s.height.toFloat(), depthConfig) else null
            }.takeIf { player == null }
        // 試作の層で描くときは、人物を板として動かす量（傾き 1 のときの画素）。人物の形を使う効果もこの量だけ動かす
        val personK = painter?.let { depthPersonShift(depthLayers!!, depthConfig, face.artSize!!.width.toFloat()) }
        val personShift = personK?.let { k -> { tilt().let { t -> Offset(t.x * k, t.y * k) } } } ?: artShift
        val personPad = maxOf(parallax.art, kotlin.math.abs(personK ?: 0f))
        // 立体感の効果は、切り抜きのある絵にだけ掛ける。再生中はジャケットで絵が隠れるので掛けない
        val withDepth = art != null && person != null && player == null && depth.spec.any
        val blurAlpha = if (withDepth && depth.spec.blur.enabled) depth.spec.blur.amount else 0f
        // 1. 絵（絵の長方形はコマの外へはみ出す。はみ出た分は外側の graphicsLayer の shape で切り抜く）
        if (painter != null) {
            // 傾きは描画の段階で読む。専用の描画の層に入れ、傾きが変わったら、この層の四角形 1 つだけを描き直す（コマ全体の命令を作り直さない）
            if (TiltMotion.moving) {
                // 傾きで動いている間は、シェーダーを縦横半分の層に描き、2 倍に拡大して重ねる（計算する画素を 4 分の 1 にする）。
                // 止まって 0.2 秒たったら元の解像度で描き直す。動いている間の粗さは見た目にほとんど分からなかった
                Box(
                    Modifier
                        .layout { m, c ->
                            val pl = m.measure(androidx.compose.ui.unit.Constraints.fixed((c.maxWidth + 1) / 2, (c.maxHeight + 1) / 2))
                            layout(c.maxWidth, c.maxHeight) { pl.place(0, 0) }
                        }.graphicsLayer {
                            scaleX = 2f
                            scaleY = 2f
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                        }.drawBehind {
                            val full = androidx.compose.ui.geometry.Size(size.width * 2f, size.height * 2f)
                            scale(0.5f, pivot = Offset.Zero) { with(painter) { draw(tilt().let { t -> Offset(t.x, t.y) }, face, full) } }
                        },
                )
            } else {
                Box(Modifier.fillMaxSize().graphicsLayer().drawBehind { with(painter) { draw(tilt().let { t -> Offset(t.x, t.y) }, face) } })
            }
        } else if (art != null) {
            // 絵を 1 枚描くだけなので、描いた結果は保存しない（保存すると速くならずに GPU のテクスチャが 1 枚増える）
            TiltLayer(artShift) { with(g.face) { drawArt(art, blurred = blurred, blurAlpha = blurAlpha, withDim = false) } }
        } else {
            StaticLayer { with(g.face) { drawArt(null) } }
        }
        // 2. 暗さとかすみは絵と一緒に動かさない（動かすと端に塗っていない帯が見える）
        if (art != null && (g.face.dim > 0f || withDepth)) {
            StaticLayer {
                with(g.face) { drawDim() }
                if (withDepth) drawHaze(g.face, depth)
            }
        }
        // 3. ジャケットはコマの絵の上、文字の下に描く（時計と大見出しを残すため）
        player?.let { (fade, playing) -> StaticLayer { drawPlayerArt(fade, playing) } }
        // 4. 文字・アイコンと人物を、コマの重ね順どおりに重ねる。部分ごとに動かす量が違うので層を分ける。
        // 人物より後ろのものはまとめて人物の形で抜き（人物の後ろに見える）、人物より前のものはそのまま上に重ねる
        val order = LayerOrder.of(g.panel.order)
        val personAt = order.indexOf(LayerKind.PERSON)
        val shownIcons = if (player == null) iconBitmaps.value else emptyList()
        val part: @Composable (LayerKind) -> Unit = { kind -> if (texts != null && "texts" !in off) TextPart(kind, texts, g, measurer, now, shownIcons, iconShadows.value, ::shift, parallax, { DepthStyle.lightDirection(tilt()) }) }
        val behind = order.take(personAt)
        val (mo, ms) = g.art?.let { localPlacement(g.bounds, it) } ?: (null to null)
        // 人物を描き直す効果（影・背景のぼかし・縁取り）があれば、描き直した人物が文字の上に重なるので、文字を人物の形で抜かない
        // （画面の外でまとめ直す処理と、そのための GPU のテクスチャが要らなくなる）
        val personCovers = withDepth && depth.spec.redrawsPerson && "outline" !in off
        if (texts != null && person != null && mo != null && ms != null && "textmask" !in off && !personCovers) {
            LayerGroup {
                behind.forEach { part(it) }
                TiltLayer(personShift, pad = personPad + 2f, cache = true, blendMode = BlendMode.DstIn) { drawImage(person.bitmap, dstOffset = mo, dstSize = ms) }
            }
        } else {
            behind.forEach { part(it) }
        }
        // 5. 人物の前の効果
        if (withDepth) PersonLayers(g.face, art!!, person!!, depthMaps, depth, personShift, { DepthStyle.lightDirection(tilt()) }, personPad)
        order.drop(personAt + 1).forEach { part(it) }
        // 6. 内側の影・光の反射・枠線。光の反射は傾きと逆へ流す（手に持った紙に光が当たっているように見せる）
        StaticLayer { with(g.face) { drawInnerShadow() } }
        if ("sheen" in off) {
            Unit
        } else if (parallax.art > 0f) {
            TiltLayer({ tilt().let { t -> Offset(-t.x * g.bounds.width * 0.35f, -t.y * g.bounds.height * 0.2f) } }) { with(g.face) { drawSheen() } }
        } else {
            StaticLayer { with(g.face) { drawSheen() } }
        }
        StaticLayer {
            with(g.face) { drawBorder() }
            // 押している間は少し暗くする
            if (motion.press.value > 0f) drawRect(Color.Black.copy(alpha = 0.18f * motion.press.value))
            if (highlight != null) drawRect(highlight.copy(alpha = 0.35f))
        }
        // 絵が無いコマ（サンプルや、画像をまだ置いていないコマ）だけ、紙かベタの上に名前を表示する
        if (art == null && label != null && player == null) {
            BasicText(
                label,
                style = TextStyle(color = if (g.face.inkText) BlankPage.INK else Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** 文字・アイコンの 1 つの部分を、視差の量だけ動く層に描く。そのコマに無い部分なら何も置かない。 */
@Composable
private fun TextPart(
    kind: LayerKind,
    texts: PanelTextSpec,
    g: PanelGeometry,
    measurer: TextMeasurer,
    now: () -> LocalDateTime,
    icons: List<ImageBitmap?>,
    /** アイコンの影（[icons] と同じ順）。影は光の向き（[light]、傾きで回る）と反対へずらす。 */
    iconShadows: List<ImageBitmap?>,
    shift: (Float) -> () -> Offset,
    parallax: ParallaxAmounts,
    light: () -> Offset,
) {
    val area = Size(g.bounds.width, g.bounds.height)
    when (kind) {
        LayerKind.ICONS ->
            if (icons.isNotEmpty() && texts.icons.isNotEmpty()) {
                TiltLayer(shift(parallax.icon)) { drawPanelTexts(PanelTextSpec(icons = texts.icons), area, measurer, now(), null, null, null, icons = icons, iconFilter = g.face.toneFilter, ink = g.face.inkText, iconShadows = iconShadows, shadowDir = if (iconShadows.any { it != null }) -light() else Offset.Zero) }
            }

        LayerKind.LABEL -> if (texts.labels.isNotEmpty()) TiltLayer(shift(parallax.text)) { drawPanelTexts(PanelTextSpec(labels = texts.labels), area, measurer, now(), null, null, null, ink = g.face.inkText) }

        LayerKind.MASTHEAD -> if (texts.mastheads.isNotEmpty()) TiltLayer(shift(parallax.masthead)) { drawPanelTexts(PanelTextSpec(mastheads = texts.mastheads), area, measurer, now(), null, null, null, ink = g.face.inkText) }

        LayerKind.CLOCK -> if (texts.clocks.isNotEmpty()) TiltLayer(shift(parallax.clock)) { drawPanelTexts(PanelTextSpec(clocks = texts.clocks), area, measurer, now(), null, null, null, ink = g.face.inkText) }

        LayerKind.PERSON -> Unit
    }
}

/**
 * 枠の外へはみ出した人物（立体感の E）。コマより [DepthStyle.popWidth] だけ大きい層に、コマと同じ動き（登場・押したとき）を付けて描く。
 * 層は指の操作を受けないので、タップはその下のコマが受ける（判定はコマの形のまま）。
 */
@Composable
private fun PopLayer(
    g: PanelGeometry,
    motion: PanelMotion,
    art: ImageBitmap,
    person: PersonMask,
    maps: DepthMaps?,
    depth: DepthStyle,
    tilt: () -> Tilt,
    parallax: ParallaxAmounts,
    /** 試作の層で描くコマなら、人物を板として動かす量（傾き 1 のときの画素）。null なら絵と同じ量。 */
    personShift: Float? = null,
) {
    val density = LocalDensity.current
    val b = g.bounds
    val pad = depth.popWidth
    Box(
        Modifier
            .offset { IntOffset((b.left - pad).roundToInt(), (b.top - pad).roundToInt()) }
            .size(with(density) { (b.width + 2 * pad).toDp() }, with(density) { (b.height + 2 * pad).toDp() })
            .graphicsLayer {
                // 層はコマと同じ中心を持つので、拡大縮小の中心もコマと同じになる
                translationX = motion.dx.value
                translationY = motion.dy.value
                scaleX = motion.scale.value
                scaleY = motion.scale.value
                alpha = motion.alpha.value
            }.drawWithContent {
                // 枠線の内側の縁から、外へ pad までの帯だけに描く（コマの中は PanelLayer で描き終えている）
                val ring =
                    Path().apply {
                        fillType = PathFillType.EvenOdd
                        addPolygonAt(g.polygon.offset(pad), b.left - pad, b.top - pad)
                        addPolygonAt(g.polygon.offset(-depth.borderWidth), b.left - pad, b.top - pad)
                    }
                clipPath(ring) { this@drawWithContent.drawContent() }
            },
    ) {
        // 中の層の原点をコマの左上に戻す
        Box(Modifier.offset { IntOffset(pad.roundToInt(), pad.roundToInt()) }.size(with(density) { b.width.toDp() }, with(density) { b.height.toDp() })) {
            val k = personShift ?: parallax.art
            val artShift = { tilt().let { t -> Offset(t.x * k, t.y * k) } }
            // 枠とすき間に落ちる影は、コマの中より近い（人物と枠の間が狭い）ので、短く薄くする
            PersonLayers(g.face, art, person, maps, depth, artShift, { DepthStyle.lightDirection(tilt()) }, maxOf(parallax.art, kotlin.math.abs(k)), extra = pad, shadowScale = 0.6f, shadowAlpha = 0.8f)
        }
    }
}

/**
 * ウィジェットに重なる人物（コマの中の分だけ）。ウィジェットと重なる長方形の中だけに描く。
 * 人物の効果（影・縁取り）は描かず、絵の色と暗さだけを合わせる。
 */
@Composable
private fun PersonOverWidgets(
    g: PanelGeometry,
    motion: PanelMotion,
    art: ImageBitmap,
    person: PersonMask,
    /** 画面の座標のウィジェットの長方形。 */
    widgets: List<Rect>,
    tilt: () -> Tilt,
    /** 傾き 1 のときに人物を動かす量（画素）。 */
    shift: Float,
) {
    val b = g.bounds
    PanelBox(b.left, b.top, b.width, b.height, motion, clipShape = g.shape) {
        Box(
            Modifier.fillMaxSize().drawWithContent {
                val clip = Path().apply { widgets.forEach { addRect(androidx.compose.ui.geometry.Rect(it.left - b.left, it.top - b.top, it.right - b.left, it.bottom - b.top)) } }
                clipPath(clip) { this@drawWithContent.drawContent() }
            },
        ) {
            TiltLayer({ tilt().let { t -> Offset(t.x * shift, t.y * shift) } }, pad = kotlin.math.abs(shift) + 2f, cache = true) { drawPersonImage(g.face, art, person) }
        }
    }
}

private fun Path.addPolygonAt(
    p: dev.furunov.komalauncher.model.geometry.Polygon,
    originX: Float,
    originY: Float,
) {
    p.points.forEachIndexed { i, pt -> if (i == 0) moveTo(pt.x - originX, pt.y - originY) else lineTo(pt.x - originX, pt.y - originY) }
    close()
}

/** 毎分 0 秒に進む時計（[everySecond] なら毎秒）。ホームが見えている間だけ進める（裏では描かないので）。 */
@Composable
private fun rememberMinuteClock(everySecond: Boolean = false): State<LocalDateTime> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(LocalDateTime.now(), lifecycle, everySecond) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = LocalDateTime.now()
                val next = if (everySecond) value.withNano(0).plusSeconds(1) else value.withSecond(0).withNano(0).plusMinutes(1)
                delay(java.time.Duration.between(LocalDateTime.now(), next).toMillis().coerceAtLeast(100))
            }
        }
    }
}

/**
 * 画面に描く傾き。センサーの目標へ 1 フレームごとに少しずつ寄せる。ホームが見えている間だけセンサーを読む。
 * 目標に着いたらフレームを待つのをやめる（止まっている間は描き直さない）。
 */
@Composable
private fun rememberShownTilt(
    source: TiltSource?,
    enabled: Boolean,
    /** true の間は、今の傾きのまま止める（絵が跳ばないよう、0 には戻さない）。 */
    paused: Boolean,
    /** 傾きが端（1）のときに、絵・文字・時計が動く量の最大（画素）。1 画素未満の変化で描き直さないよう、これで刻みを決める。 */
    maxShiftPx: Float,
): State<Tilt> {
    val shown = remember { mutableStateOf(Tilt.ZERO) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(source, enabled, paused) {
        if (source == null || !enabled) {
            shown.value = Tilt.ZERO
            return@LaunchedEffect
        }
        if (paused) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            source.start()
            try {
                // 目標へ寄せる途中の値は手元で持ち、画面に渡す値は刻みに丸める（刻みが変わったときだけ描き直す）。
                // 止まっているときは 1 画素未満の差を無視する（手に持つと毎フレーム描いていたため）。
                // 1 画素以上の差が出たら意図して傾けたとみなし、止まるまで 1/4 画素の刻みで描き直す
                // （1 画素ずつだと、ゆっくり傾けたときに 0.1 秒ごとに跳んで見えた。2026-11-22 に動画で確認）
                val step = if (maxShiftPx > 0f) 1f / maxShiftPx else 1f
                val fine = step / Tilt.FINE_STEPS
                var current = shown.value
                var moving = false
                source.target.collectLatest { target ->
                    if (!moving && !Tilt.startsMoving(shown.value, target, step)) return@collectLatest
                    moving = true
                    while (current != target) {
                        TiltMotion.moving = true
                        withFrameNanos { }
                        current = Tilt.follow(current, target)
                        shown.value = Tilt.quantize(current, fine)
                    }
                    // 目標に着いたら止まっているとみなす。次の 1 画素未満の揺れでは描き直さない
                    moving = false
                    // 止まって 0.2 秒たったら「動いていない」にする（すぐ次の傾きが来たら、この待ちは打ち切られる）
                    kotlinx.coroutines.delay(200)
                    TiltMotion.moving = false
                }
            } finally {
                source.stop()
                shown.value = Tilt.ZERO
            }
        }
    }
    return shown
}

private val NO_ART = kotlinx.coroutines.flow.MutableStateFlow<androidx.compose.ui.graphics.ImageBitmap?>(null)

/** コマに置くアイコン。[app] が null なら全アプリ一覧のアイコン。[rect] はコマの左上が原点の画素。 */
internal data class PanelIcon(
    val app: AppKey?,
    val rect: Rect,
)

/** [position]（画面の座標）がコマのアイコンの枠の中なら、そのアプリ。全アプリ一覧のアイコンと、再生中のコマは除く。 */
private fun iconAppAt(
    panel: PanelGeometry,
    position: Offset,
    texts: Map<PanelId, PanelTextSpec>,
    state: HomeUiState,
): AppKey? {
    if (panel.panel.content !is PanelContent.Folder || state.player?.panelId == panel.panel.id) return null
    val x = position.x - panel.bounds.left
    val y = position.y - panel.bounds.top
    return panelIcons(panel, texts[panel.panel.id], state).firstOrNull { x in it.rect.left..it.rect.right && y in it.rect.top..it.rect.bottom }?.app
}

/** コマに置くアイコン。フォルダは先頭から順のアプリ、全アプリ一覧はアイコン（app が null）。 */
private fun panelIcons(
    g: PanelGeometry,
    spec: PanelTextSpec?,
    state: HomeUiState,
): List<PanelIcon> {
    val rects = spec?.icons.orEmpty()
    if (g.panel.content == PanelContent.Drawer) return rects.take(1).map { PanelIcon(null, it) }
    return rects.zip(state.shownAppsOf(g.panel.id.value)) { r, app -> PanelIcon(app, r) }
}
