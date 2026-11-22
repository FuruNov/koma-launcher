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
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.furunov.komalauncher.model.apps.AppKey
import dev.furunov.komalauncher.model.folders.FolderEdits
import dev.furunov.komalauncher.model.folders.Folders
import dev.furunov.komalauncher.model.geometry.Point
import dev.furunov.komalauncher.model.layout.Border
import dev.furunov.komalauncher.model.layout.BorderStyle
import dev.furunov.komalauncher.model.layout.CutEnd
import dev.furunov.komalauncher.model.layout.DepthSpec
import dev.furunov.komalauncher.model.layout.LabelMode
import dev.furunov.komalauncher.model.layout.LayerKind
import dev.furunov.komalauncher.model.layout.LayerOrder
import dev.furunov.komalauncher.model.layout.LayoutEdits
import dev.furunov.komalauncher.model.layout.LayoutShape
import dev.furunov.komalauncher.model.layout.Panel
import dev.furunov.komalauncher.model.layout.PanelContent
import dev.furunov.komalauncher.model.layout.PanelId
import dev.furunov.komalauncher.model.layout.ResolvedLayout
import dev.furunov.komalauncher.model.layout.ResolvedPanel
import dev.furunov.komalauncher.model.layout.SplitDirection
import dev.furunov.komalauncher.model.layout.Tilt
import dev.furunov.komalauncher.model.layout.Tone
import dev.furunov.komalauncher.ui.apps.AppPicker
import dev.furunov.komalauncher.ui.art.DepthStyle
import dev.furunov.komalauncher.ui.art.PanelFace
import dev.furunov.komalauncher.ui.art.PanelStyle
import dev.furunov.komalauncher.ui.art.PanelTextSpec
import dev.furunov.komalauncher.ui.art.buildPanelTexts
import dev.furunov.komalauncher.ui.art.depthRequests
import dev.furunov.komalauncher.ui.art.drawHaze
import dev.furunov.komalauncher.ui.art.drawPanelTexts
import dev.furunov.komalauncher.ui.art.drawPersonFront
import dev.furunov.komalauncher.ui.art.drawPersonPop
import dev.furunov.komalauncher.ui.art.localPlacement
import dev.furunov.komalauncher.ui.art.only
import dev.furunov.komalauncher.ui.art.rememberArts
import dev.furunov.komalauncher.ui.art.rememberBlurredArts
import dev.furunov.komalauncher.ui.art.rememberDepthMaps
import dev.furunov.komalauncher.ui.art.rememberMasks
import dev.furunov.komalauncher.ui.controls.ToggleSwitch
import dev.furunov.komalauncher.ui.icons.rememberIconBitmaps
import dev.furunov.komalauncher.ui.icons.rememberIconShadows
import java.time.LocalDateTime
import kotlin.math.hypot
import kotlin.math.roundToInt

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
    folderDeps: EditorFolders,
    onClose: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val folders by folderDeps.store.folders.collectAsStateWithLifecycle()
    vm.setScreen(screenWidth, screenHeight)
    // 開いた直後はコマを選んでいないので、全体の設定から見せる
    var tab by rememberSaveable { mutableStateOf(EditorTab.Whole) }
    var addingApps by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { vm.setImage(it.toString()) }
        }
    val tree = state.layout.shape as? LayoutShape.Tree
    val selectedPanel = state.selected?.let { state.layout.panel(it) }
    // コマを押して選んだら、そのコマの設定を見せる
    LaunchedEffect(state.selected) { if (state.selected != null) tab = EditorTab.Panel }
    val names = remember(folders) { folders.byPanel.mapNotNull { (id, f) -> f.name?.let { id to it } }.toMap() }
    // コマに置くアイコンのアプリ（フォルダは先頭から順、全アプリ一覧は記号で null）。ホームと同じ位置に縮小図にも描く
    val iconApps =
        remember(folders, state.layout) {
            folders.byPanel.filterValues { it.apps.isNotEmpty() }.mapValues { (_, f) -> f.apps.map<AppKey, AppKey?> { it } } +
                state.layout.panels.filter { it.content == PanelContent.Drawer }.associate { it.id.value to listOf<AppKey?>(null) }
        }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Color(0xFF121216)).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("コマ割りを編集", style = TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold))
                Spacer(Modifier.weight(1f))
                Chip("元に戻す", enabled = state.canUndo, onClick = vm::undo)
                Spacer(Modifier.width(8.dp))
                Chip("やり直す", enabled = state.canRedo, onClick = vm::redo)
                Spacer(Modifier.width(8.dp))
                Chip("閉じる", onClick = onClose)
            }
            Preview(vm, state, names, iconApps, screenWidth, screenHeight, Color(tree?.frame?.gutterColor ?: defaultGutterColor), tab == EditorTab.Whole, Modifier.weight(1.3f).fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("選択中のコマ", selected = tab == EditorTab.Panel) { tab = EditorTab.Panel }
                Chip("全体", selected = tab == EditorTab.Whole) { tab = EditorTab.Whole }
            }
            // 操作の欄が伸びても縮小図が潰れないよう、高さを分け合う（操作の欄は中でスクロールする）
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
                state.message?.let {
                    BasicText(it, style = TextStyle(color = Color(0xFFFFD54F), fontSize = 13.sp), modifier = Modifier.padding(vertical = 4.dp).clickable(onClick = vm::dismissMessage))
                }
                when (tab) {
                    EditorTab.Panel ->
                        when {
                            tree == null -> Hint("旧形式のレイアウトは「全体」のテンプレートを選ぶと編集できます")

                            selectedPanel == null -> Hint("縮小図のコマをタップして選択してください")

                            else -> PanelControls(vm, state, tree, selectedPanel, folderDeps, folders, onPickImage = {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }, onAddApps = { addingApps = true })
                        }

                    EditorTab.Whole -> WholeControls(vm, tree, defaultGutterColor, state.restored)
                }
            }
        }
        val folderPanel = selectedPanel?.takeIf { it.content is PanelContent.Folder }
        if (addingApps && folderPanel != null) {
            val apps by folderDeps.apps.collectAsStateWithLifecycle()
            val id = folderPanel.id.value
            AppPicker(
                title = folders.byPanel[id]?.name ?: folderPanel.label ?: "アプリを追加",
                apps = apps,
                checked = folders.appsOf(id).toSet(),
                onToggle = { app -> folderDeps.store.update { FolderEdits.toggle(it, id, app) } },
                onDone = { addingApps = false },
            )
        }
    }
}

private enum class EditorTab { Panel, Whole }

/** 「選択中のコマ」の欄。分割・種類・画像と、フォルダのコマならフォルダの設定。 */
@Composable
private fun PanelControls(
    vm: EditorViewModel,
    state: EditorUiState,
    tree: LayoutShape.Tree,
    panel: Panel,
    folderDeps: EditorFolders,
    folders: Folders,
    onPickImage: () -> Unit,
    onAddApps: () -> Unit,
) {
    ChipRow("分割") {
        Chip("横に分割") { vm.split(SplitDirection.HORIZONTAL) }
        Chip("縦に分割") { vm.split(SplitDirection.VERTICAL) }
        Chip("結合", enabled = vm.selectedCutPath() != null, onClick = vm::merge)
    }
    // 種類を全て並べ、今の種類を光らせる。押すたびに切り替える形では、どんな種類があるのか前もって分からなかった
    ChipRow("種類") {
        listOf(PanelContent.Folder(emptyList()), PanelContent.Drawer, PanelContent.Widget).forEach { kind ->
            Chip(kindLabel(kind), selected = panel.content::class == kind::class) { vm.setKind(kind) }
        }
    }
    if (panel.iconAt != null || panel.iconScale != 1f) {
        ChipRow("アイコン") { Chip("位置と大きさを自動に戻す") { vm.resetIcons(panel.id) } }
    }
    // ウィジェットのコマには名前を表示しないので、欄も出さない
    if (panel.content != PanelContent.Widget) {
        val folderName = folders.byPanel[panel.id.value]?.name
        NameFields(vm, panel, folderName, state.restored, onClearFolderName = { folderDeps.store.update { FolderEdits.rename(it, panel.id.value, null) } })
    }
    if (panel.content is PanelContent.Folder) FolderControls(folderDeps, folders, panel, onAddApps)
    ChipRow("画像") {
        Chip(if (state.busy) "読み込み中…" else "画像を選択", enabled = !state.busy, onClick = onPickImage)
        Chip("画像を削除", enabled = panel.art != null, onClick = vm::removeImage)
    }
    panel.art?.let { art ->
        // 人物の切り抜きがある絵だけ、枠の外へはみ出させられる（はみ出す量は「全体」の「立体感」で決める）
        if (art.image in state.masks) {
            ChipRow("はみ出し") {
                Chip("枠の外へはみ出す", selected = panel.pop) { vm.setPop(!panel.pop) }
            }
        }
        Hint("ドラッグで移動、ピンチで拡大・縮小")
        // 透過させたウィジェットや名前を、明るい絵の上でも読めるようにする
        SliderRow("暗さ", art.dim * 100f, 0f..100f, step = 5f) { v -> vm.setDim(v / 100f) }
    }
}

/**
 * コマの名前と英語の名前。英語表示のときは英語の名前を表示し、空なら名前を表示する。
 * 入力中の文字はこの画面で持つ。保存側を通した値を読み直すと、日本語の変換の途中で文字が確定してしまうため。
 * ホームの長押しで付けたフォルダの名前があれば、それを初めの値にし、ここで書き換えたら消す（名前をレイアウトの 1 か所にまとめる）。
 */
@Composable
private fun NameFields(
    vm: EditorViewModel,
    panel: Panel,
    folderName: String?,
    restored: Int,
    onClearFolderName: () -> Unit,
) {
    var ja by remember(panel.id, restored) { mutableStateOf(TextFieldValue(folderName ?: panel.label.orEmpty())) }
    var en by remember(panel.id, restored) { mutableStateOf(TextFieldValue(panel.labelEn.orEmpty())) }
    // 入力の 1 文字ごとには履歴を積まず、このコマの名前を書き始めたときに 1 回だけ積む
    var recorded by remember(panel.id, restored) { mutableStateOf(false) }
    fun recordOnce() {
        if (!recorded) vm.beginGesture()
        recorded = true
    }
    TextRow("名前", ja, "（空欄で非表示）") {
        recordOnce()
        ja = it
        if (folderName != null) onClearFolderName()
        vm.setLabels(it.text, en.text)
    }
    TextRow("英語の名前", en, "（空欄で名前を表示）") {
        recordOnce()
        en = it
        vm.setLabels(ja.text, it.text)
    }
}

/** 見出しと 1 行の入力欄。 */
@Composable
internal fun TextRow(
    title: String,
    value: TextFieldValue,
    placeholder: String,
    onChange: (TextFieldValue) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(title, style = TextStyle(color = Color(0xFF9E9EA8), fontSize = 13.sp), modifier = Modifier.width(LABEL_WIDTH))
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.copy(text = it.text.take(40))) },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
            cursorBrush = SolidColor(Color.White),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().background(Color(0xFF2A2A32), RoundedCornerShape(8.dp)).padding(10.dp)) {
                    if (value.text.isEmpty()) BasicText(placeholder, style = TextStyle(color = Color(0xFF6A6A74), fontSize = 15.sp))
                    inner()
                }
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/** 「全体」の欄。テンプレート・すき間・枠・文字と時計・見た目。 */
@Composable
private fun WholeControls(
    vm: EditorViewModel,
    tree: LayoutShape.Tree?,
    defaultGutterColor: Long,
    restored: Int,
) {
    ChipRow("テンプレート") {
        LayoutEdits.Template.entries.forEach { t -> Chip(t.label) { vm.applyTemplate(t) } }
    }
    if (tree == null) return
    val frame = tree.frame
    SliderRow("すき間", frame.gutter, 0f..60f) { v -> vm.setFrame(record = false) { it.copy(gutter = v) } }
    ChipRow("すき間の色") {
        // 色の無い古いレイアウトは、設定画面にあった色を使う
        val current = frame.gutterColor ?: defaultGutterColor
        listOf(BLACK to "黒", WHITE to "白", DARK_GRAY to "ダークグレー").forEach { (c, label) ->
            Chip(label, selected = current == c) { vm.setFrame { it.copy(gutterColor = c) } }
        }
    }
    ChipRow("枠") {
        listOf(BorderStyle.NONE to "なし", BorderStyle.SINGLE to "一重", BorderStyle.DOUBLE to "二重").forEach { (s, label) ->
            Chip(label, selected = frame.border.style == s) { vm.setFrame { it.copy(border = it.border.copy(style = s)) } }
        }
        Chip("裁ち落とし", selected = frame.bleed) { vm.setFrame { it.copy(bleed = !it.bleed) } }
    }
    SliderRow("枠の太さ", frame.border.width, 0f..40f, tick = 5f) { v -> vm.setFrame(record = false) { it.copy(border = it.border.copy(width = v)) } }
    ChipRow("枠の色") {
        // 自動は、すき間が暗ければ白、明るければ黒
        listOf(null to "自動", Border.BLACK to "黒", Border.WHITE to "白").forEach { (c, label) ->
            Chip(label, selected = frame.border.color == c) { vm.setFrame { it.copy(border = it.border.copy(color = c)) } }
        }
    }
    TextAndClockControls(vm, tree, restored)
    val look = tree.look
    ChipRow("色調") {
        Chip("なし", selected = look.tone == Tone.NONE) { vm.setLook { it.copy(tone = Tone.NONE) } }
        Chip("パステル", selected = look.tone == Tone.SOFT) { vm.setLook { it.copy(tone = Tone.SOFT) } }
    }
    if (look.tone != Tone.NONE) {
        SliderRow("強さ", look.strength * 100f, 0f..100f, step = 5f) { v -> vm.setLook(record = false) { it.copy(strength = v / 100f) } }
    }
    SliderRow("内側の影", look.inner * 100f, 0f..100f, step = 5f) { v -> vm.setLook(record = false) { it.copy(inner = v / 100f) } }
    SliderRow("光沢", look.sheen * 100f, 0f..100f, step = 5f) { v -> vm.setLook(record = false) { it.copy(sheen = v / 100f) } }
    DepthControls(vm, tree.depth)
}

/**
 * 前景の人物の立体感。人物の切り抜きがある絵にだけ掛かる。効果ごとに入れ外しと値を変える。
 * 長さは基準の幅（1220 など）のときの画素。
 */
@Composable
private fun DepthControls(
    vm: EditorViewModel,
    d: DepthSpec,
) {
    fun set(
        record: Boolean = true,
        transform: (DepthSpec) -> DepthSpec,
    ) = vm.setTree(record) { it.copy(depth = transform(it.depth)) }
    Hint("立体感: 人物の切り抜きがある絵にだけ掛かります")
    ToggleRow("人物の影", d.shadow.enabled) { v -> set { it.copy(shadow = it.shadow.copy(enabled = v)) } }
    if (d.shadow.enabled) {
        SliderRow("濃さ", d.shadow.alpha * 100f, 0f..100f, step = 5f, mark = 55f) { v -> set(false) { it.copy(shadow = it.shadow.copy(alpha = v / 100f)) } }
        SliderRow("ずらし", d.shadow.distance, 0f..60f, step = 1f, mark = 22f) { v -> set(false) { it.copy(shadow = it.shadow.copy(distance = v)) } }
        SliderRow("ぼかし", d.shadow.blur, 2f..30f, step = 1f, tick = 4f, mark = 10f) { v -> set(false) { it.copy(shadow = it.shadow.copy(blur = v)) } }
    }
    ToggleRow("輪郭の光", d.rim.enabled) { v -> set { it.copy(rim = it.rim.copy(enabled = v)) } }
    if (d.rim.enabled) {
        SliderRow("強さ", d.rim.alpha * 100f, 0f..100f, step = 5f, mark = 70f) { v -> set(false) { it.copy(rim = it.rim.copy(alpha = v / 100f)) } }
        SliderRow("幅", d.rim.width, 1f..14f, step = 0.5f, tick = 1f, mark = 4f) { v -> set(false) { it.copy(rim = it.rim.copy(width = v)) } }
    }
    ToggleRow("背景のぼかし", d.blur.enabled) { v -> set { it.copy(blur = it.blur.copy(enabled = v)) } }
    if (d.blur.enabled) {
        SliderRow("ぼかし", d.blur.amount * 100f, 0f..100f, step = 5f, mark = 60f) { v -> set(false) { it.copy(blur = it.blur.copy(amount = v / 100f)) } }
        SliderRow("かすみ", d.blur.haze * 100f, 0f..40f, step = 2f, tick = 4f, mark = 12f) { v -> set(false) { it.copy(blur = it.blur.copy(haze = v / 100f)) } }
    }
    ToggleRow("縁取り", d.outline.enabled) { v -> set { it.copy(outline = it.outline.copy(enabled = v)) } }
    if (d.outline.enabled) {
        SliderRow("太さ", d.outline.width, 1f..12f, step = 0.5f, tick = 1f, mark = 4f) { v -> set(false) { it.copy(outline = it.outline.copy(width = v)) } }
        SliderRow("不透明度", d.outline.alpha * 100f, 0f..100f, step = 5f, mark = 90f) { v -> set(false) { it.copy(outline = it.outline.copy(alpha = v / 100f)) } }
    }
    SliderRow("はみ出し量", d.pop.width, 0f..80f, step = 2f, mark = 40f) { v -> set(false) { it.copy(pop = it.pop.copy(width = v)) } }
    Hint("はみ出させるコマは、「選択中のコマ」で選びます")
}

private const val BLACK = 0xFF111111
private const val WHITE = 0xFFFFFFFF
private const val DARK_GRAY = 0xFF2B2B33

/** 行の見出しの幅。「テンプレート」「見出しの位置」が 1 行に収まる幅。 */
internal val LABEL_WIDTH = 84.dp

internal fun kindLabel(c: PanelContent): String = when (c) {
    is PanelContent.Folder -> "フォルダ"
    PanelContent.Widget -> "ウィジェット"
    PanelContent.Drawer -> "アプリドロワー"
}

/** 画面の縮小図と、その上の指の操作。 */
@Composable
private fun Preview(
    vm: EditorViewModel,
    state: EditorUiState,
    /** フォルダに付けた名前（コマの id → 名前）。ホームと同じ名前を縮小図に表示する。 */
    names: Map<String, String>,
    /** コマに置くアイコンのアプリ（コマの id → 先頭から順。全アプリ一覧の記号は null）。 */
    iconApps: Map<String, List<AppKey?>>,
    screenWidth: Float,
    screenHeight: Float,
    gutter: Color,
    /** true なら、時計と大見出しを指で動かせる（「全体」のタブ）。false なら選んだコマの絵を動かす。 */
    moveTexts: Boolean,
    modifier: Modifier,
) {
    val resolved = remember(state.layout, screenWidth, screenHeight) { vm.resolve(state.layout) }
    val sizes =
        remember(resolved) {
            resolved.panels.mapNotNull { p -> p.art?.let { a -> p.panel.art?.image?.let { it to Size(a.width, a.height) } } }.toMap()
        }
    val arts by rememberArts(state.images, sizes, state.imagesRevision)
    // 影の Paint などを毎回作らないよう、形か見た目が変わったときだけ作る
    val faces =
        remember(resolved, state.layout, gutter) {
            // 枠の色を自動にしているときは、縮小図のすき間の色から決める
            val style = PanelStyle.of(state.layout, screenWidth, gutter.toArgb().toLong() and 0xFFFFFFFFL)
            resolved.panels.map { PanelFace(it.polygon, it.art, it.index, style, it.panel.art?.dim ?: 0f) }
        }
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val masks by rememberMasks(state.masks, state.imagesRevision)
    // 前景の人物の立体感（ホームと同じ描き方。傾きは 0 として描く）
    val depth = remember(state.layout, screenWidth) { DepthStyle.of(state.layout, screenWidth) }
    val depthRequests = remember(depth, resolved, masks) { depthRequests(depth, resolved.panels.mapNotNull { p -> p.panel.art?.image?.let { id -> p.art?.let { id to it.width } } }, masks) }
    val depthMaps by rememberDepthMaps(masks, depthRequests)
    val blurIds = remember(depth, resolved, masks) { if (depth.spec.blur.enabled) resolved.panels.mapNotNull { it.panel.art?.image }.filter { it in masks }.toSet() else emptySet() }
    val blurred by rememberBlurredArts(state.images, blurIds, state.imagesRevision)
    val light = DepthStyle.lightDirection(Tilt.ZERO)
    val texts =
        remember(resolved, state.layout, masks, names, iconApps) { buildPanelTexts(state.layout, resolved.panels, screenWidth, names, masks, iconApps.mapValues { it.value.size }) }
    // アイコンの画像。全部のコマの分を 1 つの並びで読み込み、描くときにコマごとに切り分ける
    val iconKeys = resolved.panels.flatMap { p -> texts[p.panel.id]?.icons.orEmpty().zip(iconApps[p.panel.id.value].orEmpty()) { r, app -> app to r.width.roundToInt() } }
    val iconBitmaps by rememberIconBitmaps(iconKeys)
    val iconShadows by rememberIconShadows(iconKeys)
    val now = remember { LocalDateTime.now() }
    val density = LocalDensity.current
    val handleRadius = with(density) { 22.dp.toPx() }
    val current by rememberUpdatedState(state)
    val currentResolved by rememberUpdatedState(resolved)
    val currentMoveTexts by rememberUpdatedState(moveTexts)

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
                        // 2. 「選択中のコマ」のタブでは、選んでいるコマの絵を動かす・広げる
                        if (!currentMoveTexts && hit != null && hit.panel.id == st.selected && art != null) {
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
                        // 3. 押して離したら、そのコマを選ぶ
                        val up = waitForUpOrCancel(down.id)
                        // 余白を押しても選択は変えない（モックアップと同じ。細いコマの間で線の端を掴み損ねても、選択が外れないように）
                        if (up != null && hit != null && (up - start).getDistance() < viewConfiguration.touchSlop) vm.select(hit.panel.id)
                    }
                },
        ) {
            scale(s, s, pivot = Offset.Zero) {
                // 拡大縮小の中で描くので、大きさは画面の画素で渡す（キャンバスの大きさのままだと一部しか塗らない）
                drawRect(gutter, size = Size(screenWidth, screenHeight))
                var iconIndex = 0
                for ((p, face) in resolved.panels.zip(faces)) {
                    val b = face.bounds
                    val iconCount = minOf(texts[p.panel.id]?.icons?.size ?: 0, iconApps[p.panel.id.value]?.size ?: 0)
                    val panelIcons = iconBitmaps.drop(iconIndex).take(iconCount)
                    val panelShadows = iconShadows.drop(iconIndex).take(iconCount)
                    iconIndex += iconCount
                    translate(b.left, b.top) {
                        clipPath(face.path) {
                            val image = p.panel.art?.image
                            val art = image?.let(arts::get)
                            val person = image?.let(masks::get)
                            val withDepth = art != null && person != null && depth.spec.any
                            with(face) { drawArt(art, blurred = image?.let(blurred::get), blurAlpha = if (withDepth && depth.spec.blur.enabled) depth.spec.blur.amount else 0f) }
                            if (withDepth) drawHaze(face, depth)
                            // コマの重ね順どおりに描く。人物より後ろのものは人物の形で抜き、前のものはそのまま重ねる（ホームと同じ）
                            val order = LayerOrder.of(p.panel.order)
                            val personAt = order.indexOf(LayerKind.PERSON)
                            val spec = texts[p.panel.id]
                            val (mo, ms) = p.art?.let { localPlacement(b, it) } ?: (null to null)
                            fun parts(
                                kinds: List<LayerKind>,
                                behind: Boolean,
                            ) {
                                spec ?: return
                                kinds.forEach { k ->
                                    val icons = if (k == LayerKind.ICONS) panelIcons else emptyList()
                                    drawPanelTexts(spec.only(k), Size(b.width, b.height), measurer, now, if (behind) person?.bitmap else null, mo, ms, icons = icons, iconFilter = face.toneFilter, ink = face.inkText, iconShadows = if (k == LayerKind.ICONS) panelShadows else emptyList())
                                }
                            }
                            parts(order.take(personAt), behind = true)
                            if (withDepth) drawPersonFront(face, art!!, person!!, depthMaps[image], depth, Offset.Zero, light)
                            parts(order.drop(personAt + 1), behind = false)
                            with(face) { drawOverlay() }
                        }
                    }
                    // 種類。ホームでは出さない編集用の表示。名前はホームと同じ描き方で上に描いてある
                    val text = kindLabel(p.panel.content)
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
                // 枠の外へはみ出す人物は、どのコマよりも上に描く（ホームの PopLayer と同じ）
                for ((p, face) in resolved.panels.zip(faces)) {
                    if (!p.panel.pop) continue
                    val image = p.panel.art?.image ?: continue
                    val art = arts[image] ?: continue
                    val person = masks[image] ?: continue
                    translate(face.bounds.left, face.bounds.top) { drawPersonPop(face, p.polygon, art, person, depthMaps[image], depth, Offset.Zero, light) }
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
internal fun ChipRow(
    title: String,
    content: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(title, style = TextStyle(color = Color(0xFF9E9EA8), fontSize = 13.sp), modifier = Modifier.width(LABEL_WIDTH))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** オンとオフを切り替える行。行のどこを押しても切り替わる。スイッチは、ほかの行の操作と同じく項目名の幅のすぐ右に置く。 */
@Composable
internal fun ToggleRow(
    title: String,
    on: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(title, style = TextStyle(color = Color(0xFF9E9EA8), fontSize = 13.sp), modifier = Modifier.width(LABEL_WIDTH))
        ToggleSwitch(on)
    }
}

@Composable
internal fun Hint(text: String) {
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

/**
 * 横に動かすスライダー。Material を入れずに済むよう、ここで作る。
 *
 * 誤操作を減らすため、次のようにする。
 * - 触れただけでは値を変えない。横へ動かし始めてから変える（縦のスクロールで値が飛ばないように）
 * - 指の位置へ値を飛ばさず、動かした量だけ値を変える
 * - 値を [step] ごとに止め、[tick] ごとに目盛りを描く。目盛りを通るたびに端末を軽く振動させる
 * - [mark]（既定の値など）の目盛りは長く描く
 */
@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float = 1f,
    tick: Float = 10f,
    mark: Float? = null,
    // スライダーの変更は「元に戻す」の履歴に積まない（モックアップと同じ）。見た目の微調整で履歴の上限（50 件）が埋まり、割る・消すを戻せなくなるのを避ける
    onStart: () -> Unit = {},
    onChange: (Float) -> Unit,
) {
    val currentChange by rememberUpdatedState(onChange)
    val currentStart by rememberUpdatedState(onStart)
    val currentValue by rememberUpdatedState(value)
    val haptic = LocalHapticFeedback.current
    val span = range.endInclusive - range.start
    val thumb = 20.dp
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(title, style = TextStyle(color = Color(0xFF9E9EA8), fontSize = 13.sp), modifier = Modifier.width(LABEL_WIDTH))
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .height(40.dp)
                .semantics { contentDescription = title }
                .pointerInput(range, step, tick) {
                    val track = (size.width - thumb.toPx()).coerceAtLeast(1f)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var moved = 0f
                        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                            change.consume()
                            moved = over
                        } ?: return@awaitEachGesture
                        currentStart()
                        val start = currentValue
                        var last = start
                        fun update(dx: Float) {
                            moved += dx
                            val raw = (start + moved / track * span).coerceIn(range.start, range.endInclusive)
                            val snapped = (range.start + kotlin.math.round((raw - range.start) / step) * step).coerceIn(range.start, range.endInclusive)
                            if (snapped == last) return
                            // 目盛りをまたいだら振動させる
                            if (kotlin.math.floor((snapped - range.start) / tick) != kotlin.math.floor((last - range.start) / tick) || (snapped - range.start) % tick == 0f) {
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                            last = snapped
                            currentChange(snapped)
                        }
                        update(0f)
                        horizontalDrag(drag.id) { change ->
                            // consume した後の positionChange は 0 になるので、先に読む
                            val dx = change.positionChange().x
                            change.consume()
                            update(dx)
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val f = ((value - range.start) / span).coerceIn(0f, 1f)
            val trackWidth = maxWidth - thumb
            // 目盛り。つまみの中心が通る範囲に並べる
            Canvas(Modifier.fillMaxWidth().height(40.dp)) {
                val half = thumb.toPx() / 2f
                val w = size.width - thumb.toPx()
                val cy = size.height / 2f
                var t = range.start
                while (t <= range.endInclusive + 0.001f) {
                    val x = half + (t - range.start) / span * w
                    val long = mark != null && kotlin.math.abs(t - mark) < 0.001f
                    val h = if (long) 11.dp.toPx() else 6.dp.toPx()
                    drawLine(Color(if (long) 0xFFCCCCD4 else 0xFF6A6A74), Offset(x, cy + 5.dp.toPx()), Offset(x, cy + 5.dp.toPx() + h), strokeWidth = 1.5.dp.toPx())
                    t += tick
                }
            }
            Box(Modifier.padding(horizontal = thumb / 2).fillMaxWidth().height(4.dp).background(Color(0xFF3A3A44), RoundedCornerShape(2.dp)))
            Box(Modifier.padding(start = thumb / 2).width(trackWidth * f).height(4.dp).background(Color(0xFFFFD54F), RoundedCornerShape(2.dp)))
            Box(Modifier.offset(x = trackWidth * f).size(thumb).background(Color(0xFFFFD54F), RoundedCornerShape(10.dp)))
        }
        BasicText(value.roundToInt().toString(), style = TextStyle(color = Color.White, fontSize = 13.sp), modifier = Modifier.width(32.dp))
    }
}

/** 文字（名前・大見出し）と時計の操作。 */
@Composable
private fun TextAndClockControls(
    vm: EditorViewModel,
    tree: LayoutShape.Tree,
    restored: Int,
) {
    val text = tree.text
    val clock = tree.clock
    ChipRow("名前") {
        listOf(LabelMode.VERTICAL to "縦書き", LabelMode.HORIZONTAL to "横書き", LabelMode.NONE to "非表示").forEach { (m, label) ->
            Chip(label, selected = text.label == m) { vm.setTree { it.copy(text = it.text.copy(label = m)) } }
        }
        Chip("英語", selected = text.english) { vm.setTree { it.copy(text = it.text.copy(english = true)) } }
        Chip("日本語", selected = !text.english) { vm.setTree { it.copy(text = it.text.copy(english = false)) } }
    }
    // フォルダのコマに先頭のアプリのアイコンを置くか（どのコマに何のアプリがあるか分かるように）
    ToggleRow("アイコン", text.icon) { v -> vm.setTree { it.copy(text = it.text.copy(icon = v)) } }
    // 入力中の文字はこの画面で持つ（NameFields と同じ理由）。履歴は書き始めたときに 1 回だけ積む
    var masthead by remember(restored) { mutableStateOf(TextFieldValue(text.masthead)) }
    var recorded by remember(restored) { mutableStateOf(false) }
    TextRow("大見出し", masthead, "（空欄で非表示）") { v ->
        if (!recorded) vm.beginGesture()
        recorded = true
        masthead = v
        vm.setTree(record = false) { it.copy(text = it.text.copy(masthead = v.text)) }
    }
    ToggleRow("時計", clock.enabled) { v -> vm.setTree { it.copy(clock = it.clock.copy(enabled = v)) } }
    if (clock.enabled) {
        SliderRow("サイズ", clock.size * 100f, 50f..160f, step = 5f, mark = 100f) { v -> vm.setTree(record = false) { it.copy(clock = it.clock.copy(size = v / 100f)) } }
    }
    Hint("時計・大見出し・名前・アイコンの位置と大きさは、ホームの「配置を調整」で変えられます（余白か、そのコンポーネントを長押し）")
    ToggleRow("視差", tree.fx.parallax) { v -> vm.setTree { it.copy(fx = it.fx.copy(parallax = v)) } }
    if (tree.fx.parallax) {
        SliderRow("強さ", tree.fx.strength * 100f, 10f..200f, step = 10f, mark = 40f) { v -> vm.setTree(record = false) { it.copy(fx = it.fx.copy(strength = v / 100f)) } }
    }
}
