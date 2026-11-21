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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
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
import dev.furunov.komalauncher.model.geometry.Rect
import dev.furunov.komalauncher.model.layout.EditHistory
import dev.furunov.komalauncher.model.layout.LayerKind
import dev.furunov.komalauncher.model.layout.LayerOrder
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.LayoutShape
import dev.furunov.komalauncher.model.layout.PanelContent
import dev.furunov.komalauncher.model.layout.PanelId
import dev.furunov.komalauncher.model.layout.Part
import dev.furunov.komalauncher.model.layout.PartEdits
import dev.furunov.komalauncher.model.layout.ResolvedPanel
import dev.furunov.komalauncher.model.layout.Tilt
import dev.furunov.komalauncher.model.ports.TiltSource
import dev.furunov.komalauncher.model.settings.GestureKind
import dev.furunov.komalauncher.ui.art.BlankPage
import dev.furunov.komalauncher.ui.art.DepthMaps
import dev.furunov.komalauncher.ui.art.DepthStyle
import dev.furunov.komalauncher.ui.art.PanelTextSpec
import dev.furunov.komalauncher.ui.art.PersonMask
import dev.furunov.komalauncher.ui.art.buildPanelTexts
import dev.furunov.komalauncher.ui.art.depthRequests
import dev.furunov.komalauncher.ui.art.drawHaze
import dev.furunov.komalauncher.ui.art.drawPanelTexts
import dev.furunov.komalauncher.ui.art.localPlacement
import dev.furunov.komalauncher.ui.art.rememberArts
import dev.furunov.komalauncher.ui.art.rememberBlurredArts
import dev.furunov.komalauncher.ui.art.rememberDepthMaps
import dev.furunov.komalauncher.ui.art.rememberMasks
import dev.furunov.komalauncher.ui.dnd.DragDropState
import dev.furunov.komalauncher.ui.dnd.DragItem
import dev.furunov.komalauncher.ui.dnd.DragShadow
import dev.furunov.komalauncher.ui.dnd.DragSource
import dev.furunov.komalauncher.ui.dnd.LocalDragDrop
import dev.furunov.komalauncher.ui.icons.rememberIconBitmaps
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import kotlin.math.roundToInt

/** ホーム画面の外から差し込む部分。機能のモジュールどうしが互いを知らずに済むよう、:app が渡す。 */
class HomeSlots(
    /** ウィジェットのコマの中身。[PanelGeometry.inscribed] の位置に置く。 */
    val widget: @Composable (PanelGeometry) -> Unit = {},
    /** フォルダ以外の重ねる画面（全アプリ一覧、ホームのメニュー）。 */
    val overlay: @Composable (Overlay) -> Unit = {},
    /** コマの長押しのメニューに、外から加える項目（ウィジェットの選択など）。 */
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
        val masks by rememberMasks(state.masks, state.imagesRevision)
        // 前景の人物の立体感。影と縁取りの形、ぼかした絵は、その効果を入れているときだけ作る
        val depth = remember(state.layout, wPx) { DepthStyle.of(state.layout, wPx) }
        val depthRequests = remember(depth, geometry, masks) { depthRequests(depth, geometry.panels.mapNotNull { g -> g.image?.let { id -> g.art?.let { id to it.width } } }, masks) }
        val depthMaps by rememberDepthMaps(masks, depthRequests)
        val blurIds = remember(depth, geometry, masks) { if (depth.spec.blur.enabled) geometry.panels.mapNotNull { it.image }.filter { it in masks }.toSet() else emptySet() }
        val blurred by rememberBlurredArts(state.images, blurIds, state.imagesRevision)
        val folderNames = remember(state.folders) { state.folders.byPanel.mapNotNull { (id, f) -> f.name?.let { id to it } }.toMap() }
        // アイコンを置くコマと、置けるアイコンの数（フォルダはアプリの数、全アプリ一覧は記号 1 つ）
        val iconPanels =
            remember(state.folders, state.layout) {
                state.folders.byPanel.filterValues { it.apps.isNotEmpty() }.mapValues { it.value.apps.size } +
                    state.layout.panels.filter { it.content == PanelContent.Drawer }.associate { it.id.value to 1 }
            }
        // 文字の置き場所は、コマの形・文字の指定・切り抜き（人物の地図）が変わったときだけ探し直す
        val playerPanel = state.player?.panelId
        val texts =
            remember(geometry, state.layout, folderNames, masks, playerPanel, iconPanels) {
                val built = buildPanelTexts(state.layout, geometry.panels.map { ResolvedPanel(it.panel, it.index, it.polygon, it.art) }, wPx, folderNames, masks, iconPanels)
                // 再生中のコマには名前とアイコンを出さない（時計と大見出しは残す）
                if (playerPanel == null) built else built.mapValues { (id, spec) -> if (id == playerPanel) spec.copy(labels = emptyList(), icons = emptyList()) else spec }
            }
        val playerArt by (media?.art ?: NO_ART).collectAsState()
        val artFade = rememberArtFade(playerArt.takeIf { playerPanel != null })
        var flash by remember { mutableStateOf<PlayerFlashEvent?>(null) }
        val measurer = rememberTextMeasurer(cacheSize = 16)
        val now by rememberMinuteClock()
        val parallax = remember(state.layout, wPx) { parallaxOf(state.layout, wPx) }
        // 一覧やフォルダなどを開いている間は、後ろのホームの視差を止める（手の揺れで描き直し続け、開く動きが重くなるため）
        val shownTilt = rememberShownTilt(tilt, parallax.art > 0f, paused = state.overlay != Overlay.None || state.adjusting, maxShiftPx = maxOf(parallax.art, parallax.clock, parallax.masthead, parallax.icon))
        val anim = state.settings.animation
        val pressAnim = PanelAnimations.press(anim.press)
        val currentState = rememberUpdatedState(state)
        val currentTexts = rememberUpdatedState(texts)
        // 「配置を調整」で使う、今の配置（コマの位置・文字とアイコンの置き場所）
        val adjustTarget = AdjustTarget(state.layout, geometry.panels.map { ResolvedPanel(it.panel, it.index, it.polygon, it.art) }, texts, wPx)
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
            Box(Modifier.fillMaxSize().then(gestures)) {
                for (g in geometry.panels) {
                    PanelLayer(
                        g = g,
                        motion = motions.getValue(g.panel.id),
                        art = g.image?.let(arts::get),
                        person = g.image?.let(masks::get),
                        depth = depth,
                        depthMaps = g.image?.let(depthMaps::get),
                        blurred = g.image?.let(blurred::get),
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
                    if (g.panel.content is PanelContent.Widget) {
                        PanelBox(g.inscribed.left, g.inscribed.top, g.inscribed.width, g.inscribed.height, motions.getValue(g.panel.id)) {
                            slots.widget(g)
                        }
                    }
                }
                // 枠の外へはみ出す人物は、どのコマよりも上に描く（隣のコマにかかる分も見せる）。タップの判定はコマの形のまま
                for (g in geometry.panels) {
                    if (!g.panel.pop || state.player?.panelId == g.panel.id) continue
                    val art = g.image?.let(arts::get) ?: continue
                    val person = g.image.let(masks::get) ?: continue
                    PopLayer(g, motions.getValue(g.panel.id), art, person, depthMaps[g.image], depth, { shownTilt.value }, parallax)
                }
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

                is Overlay.PanelMenu -> geometry.panels.firstOrNull { it.panel.id == o.panelId }?.let { g ->
                    PanelMenuSheet(
                        g,
                        state.folders.byPanel[g.panel.id.value]?.name ?: g.panel.label,
                        state.folders.byPanel[g.panel.id.value]?.directLaunch == true,
                        musicPlayer = state.folders.byPanel[g.panel.id.value]?.musicPlayer == true,
                        showingPlayer = state.player?.panelId == g.panel.id,
                        slots.panelMenuItems(g),
                        onEvent,
                    )
                }

                Overlay.MusicAccess -> MusicAccessSheet(onEvent)

                is Overlay.Rename -> RenameSheet(o.panelId, state.folders.byPanel[o.panelId.value]?.name ?: state.layout.panel(o.panelId)?.label, onEvent)

                Overlay.None -> Unit

                else -> slots.overlay(o)
            }

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
                                    LayerKind.MASTHEAD.takeIf { spec?.masthead != null },
                                    LayerKind.CLOCK.takeIf { spec?.clock != null },
                                    LayerKind.PERSON.takeIf { image != null && image in masks },
                                )
                            }
                            editLayout { PartEdits.reorder(it, t.panels, part, move, present) }
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
            },
    ) {
        // 傾きは層の位置（graphicsLayer）でだけ読む。中身は傾きを読まないので、傾きが変わっても描画の命令を作り直さない
        val artShift = { tilt().let { t -> Offset(t.x * parallax.art, t.y * parallax.art) } }
        fun shift(k: Float): () -> Offset = { tilt().let { t -> Offset(-t.x * k, -t.y * k) } }
        val zero = { Offset.Zero }
        val margin = parallax.art + 2f
        // 立体感の効果は、切り抜きのある絵にだけ掛ける。再生中はジャケットで絵が隠れるので掛けない
        val withDepth = art != null && person != null && player == null && depth.spec.any
        val blurAlpha = if (withDepth && depth.spec.blur.enabled) depth.spec.blur.amount else 0f
        // 1. 絵（絵の長方形はコマの外へはみ出す。はみ出た分は外側の graphicsLayer の shape で切り抜く）
        if (art != null) {
            TiltLayer(artShift, pad = margin, cache = true) { with(g.face) { drawArt(art, blurred = blurred, blurAlpha = blurAlpha, withDim = false) } }
        } else {
            TiltLayer(zero) { with(g.face) { drawArt(null) } }
        }
        // 2. 暗さとかすみは絵と一緒に動かさない（動かすと端に塗っていない帯が見える）
        if (art != null && (g.face.dim > 0f || withDepth)) {
            TiltLayer(zero) {
                with(g.face) { drawDim() }
                if (withDepth) drawHaze(g.face, depth)
            }
        }
        // 3. ジャケットはコマの絵の上、文字の下に描く（時計と大見出しを残すため）
        player?.let { (fade, playing) -> TiltLayer(zero) { drawPlayerArt(fade, playing) } }
        // 4. 文字・アイコンと人物を、コマの重ね順どおりに重ねる。部分ごとに動かす量が違うので層を分ける。
        // 人物より後ろのものはまとめて人物の形で抜き（人物の後ろに見える）、人物より前のものはそのまま上に重ねる
        val order = LayerOrder.of(g.panel.order)
        val personAt = order.indexOf(LayerKind.PERSON)
        val shownIcons = if (player == null) iconBitmaps.value else emptyList()
        val part: @Composable (LayerKind) -> Unit = { kind -> if (texts != null) TextPart(kind, texts, g, measurer, now, shownIcons, ::shift, parallax) }
        val behind = order.take(personAt)
        val (mo, ms) = g.art?.let { localPlacement(g.bounds, it) } ?: (null to null)
        if (texts != null && person != null && mo != null && ms != null) {
            LayerGroup {
                behind.forEach { part(it) }
                TiltLayer(artShift, pad = margin, cache = true, blendMode = BlendMode.DstIn) { drawImage(person.bitmap, dstOffset = mo, dstSize = ms) }
            }
        } else {
            behind.forEach { part(it) }
        }
        // 5. 人物の前の効果
        if (withDepth) PersonLayers(g.face, art!!, person!!, depthMaps, depth, artShift, { DepthStyle.lightDirection(tilt()) }, parallax.art)
        order.drop(personAt + 1).forEach { part(it) }
        // 6. 内側の影・光の反射・枠線。光の反射は傾きと逆へ流す（手に持った紙に光が当たっているように見せる）
        TiltLayer(zero) { with(g.face) { drawInnerShadow() } }
        if (parallax.art > 0f) {
            TiltLayer({ tilt().let { t -> Offset(-t.x * g.bounds.width * 0.35f, -t.y * g.bounds.height * 0.2f) } }) { with(g.face) { drawSheen() } }
        } else {
            TiltLayer(zero) { with(g.face) { drawSheen() } }
        }
        TiltLayer(zero) {
            with(g.face) { drawBorder() }
            // 押している間は少し暗くする
            if (motion.press.value > 0f) drawRect(Color.Black.copy(alpha = 0.18f * motion.press.value))
            if (highlight != null) drawRect(highlight.copy(alpha = 0.35f))
        }
        // 絵が無いコマ（見本や、画像をまだ置いていないコマ）だけ、紙かベタの上に名前を表示する
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
    shift: (Float) -> () -> Offset,
    parallax: ParallaxAmounts,
) {
    val area = Size(g.bounds.width, g.bounds.height)
    when (kind) {
        LayerKind.ICONS ->
            if (icons.isNotEmpty() && texts.icons.isNotEmpty()) {
                TiltLayer(shift(parallax.icon)) { drawPanelTexts(PanelTextSpec(icons = texts.icons), area, measurer, now(), null, null, null, icons = icons, iconFilter = g.face.toneFilter, ink = g.face.inkText) }
            }

        LayerKind.LABEL -> if (texts.labels.isNotEmpty()) TiltLayer(shift(parallax.text)) { drawPanelTexts(PanelTextSpec(labels = texts.labels), area, measurer, now(), null, null, null, ink = g.face.inkText) }

        LayerKind.MASTHEAD -> texts.masthead?.let { m -> TiltLayer(shift(parallax.masthead)) { drawPanelTexts(PanelTextSpec(masthead = m), area, measurer, now(), null, null, null, ink = g.face.inkText) } }

        LayerKind.CLOCK -> texts.clock?.let { c -> TiltLayer(shift(parallax.clock)) { drawPanelTexts(PanelTextSpec(clock = c), area, measurer, now(), null, null, null, ink = g.face.inkText) } }

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
            val artShift = { tilt().let { t -> Offset(t.x * parallax.art, t.y * parallax.art) } }
            // 枠とすき間に落ちる影は、コマの中より近い（人物と枠の間が狭い）ので、短く薄くする
            PersonLayers(g.face, art, person, maps, depth, artShift, { DepthStyle.lightDirection(tilt()) }, parallax.art, extra = pad, shadowScale = 0.6f, shadowAlpha = 0.8f)
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

/** 毎分 0 秒に進む時計。ホームが見えている間だけ進める（裏では描かないので）。 */
@Composable
private fun rememberMinuteClock(): State<LocalDateTime> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(LocalDateTime.now(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = LocalDateTime.now()
                val next = value.withSecond(0).withNano(0).plusMinutes(1)
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
                // 目標へ寄せる途中の値は手元で持ち、画面に渡す値は 1 画素ずつ動く刻みに丸める。
                // 刻みが変わったときだけ状態が変わるので、1 画素未満の動きでは描き直さない（手に持つと毎フレーム描いていたため）
                val step = if (maxShiftPx > 0f) 1f / maxShiftPx else 1f
                var current = shown.value
                source.target.collectLatest { target ->
                    while (current != target) {
                        withFrameNanos { }
                        current = Tilt.follow(current, target)
                        shown.value = Tilt.quantize(current, step)
                    }
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

/** コマに置くアイコン。[app] が null なら全アプリ一覧の記号。[rect] はコマの左上が原点の画素。 */
internal data class PanelIcon(
    val app: AppKey?,
    val rect: Rect,
)

/** [position]（画面の座標）がコマのアイコンの枠の中なら、そのアプリ。全アプリ一覧の記号と、再生中のコマは除く。 */
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

/** コマに置くアイコン。フォルダは先頭から順のアプリ、全アプリ一覧は記号（app が null）。 */
private fun panelIcons(
    g: PanelGeometry,
    spec: PanelTextSpec?,
    state: HomeUiState,
): List<PanelIcon> {
    val rects = spec?.icons.orEmpty()
    if (g.panel.content == PanelContent.Drawer) return rects.take(1).map { PanelIcon(null, it) }
    return rects.zip(state.folders.appsOf(g.panel.id.value)) { r, app -> PanelIcon(app, r) }
}
