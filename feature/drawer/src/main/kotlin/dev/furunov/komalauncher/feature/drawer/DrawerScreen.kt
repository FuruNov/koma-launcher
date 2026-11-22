package dev.furunov.komalauncher.feature.drawer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.furunov.komalauncher.model.apps.AppEntry
import dev.furunov.komalauncher.model.apps.AppKey
import dev.furunov.komalauncher.model.apps.AppSearch
import dev.furunov.komalauncher.model.apps.AppShortcut
import dev.furunov.komalauncher.model.apps.DrawerFolder
import dev.furunov.komalauncher.model.apps.DrawerFolders
import dev.furunov.komalauncher.model.apps.DrawerList
import dev.furunov.komalauncher.model.apps.Ranked
import dev.furunov.komalauncher.model.apps.RankedShortcut
import dev.furunov.komalauncher.model.settings.DrawerSettings
import dev.furunov.komalauncher.ui.dnd.DragDropState
import dev.furunov.komalauncher.ui.dnd.DragItem
import dev.furunov.komalauncher.ui.dnd.DragSource
import dev.furunov.komalauncher.ui.dnd.LocalDragDrop
import dev.furunov.komalauncher.ui.dnd.draggableApp
import dev.furunov.komalauncher.ui.icons.AppIcon
import kotlinx.coroutines.launch

/** 全アプリ一覧の操作の受け手。ホームとのやり取りは :app がつなぐ。 */
class DrawerActions(
    val onLaunch: (AppKey) -> Unit,
    val onDismiss: () -> Unit,
    val onOpenSettings: () -> Unit = {},
    val onLaunchShortcut: (AppShortcut) -> Unit = {},
    /** フォルダの中でアイコンを長押しし、動かさずに離したとき。長押しのメニューを出す。 */
    val onShowAppMenu: (AppKey) -> Unit = {},
    /** フォルダのメニューの「名前を変更」。引数はフォルダの id。 */
    val onRenameFolder: (String) -> Unit = {},
    /** フォルダのメニューの「記号を変更」で選んだとき。フォルダの id と記号の id。 */
    val onSetFolderSymbol: (String, String) -> Unit = { _, _ -> },
    /** フォルダの中でアイコンを動かして離したとき。フォルダの id と、そのフォルダの全てのアプリの新しい順。 */
    val onReorderApps: (String, List<AppKey>) -> Unit = { _, _ -> },
    /** 左端の列でフォルダを動かして離したとき。全てのフォルダの id の新しい順。 */
    val onReorderFolders: (List<String>) -> Unit = {},
)

/** 検索で表示するショートカットの最大の件数。 */
private const val MAX_SHORTCUTS = 8

/**
 * 全アプリ一覧。LazyVerticalGrid なので、アイコンは画面に見えている分だけ読む。
 * 開いたらすぐ打てるよう、検索欄にフォーカスしてキーボードを表示する。Enter で先頭を起動する。
 * 一番上で下へ引くと閉じる。
 *
 * 左端の縦の列で「すべて」とフォルダを選ぶ。一覧を左右に払うと、列の順で前後のフォルダへ移る。検索の間は列を隠し、全てのアプリから探す。
 * フォルダの中と左端の列では、長押しして動かすと並べ替え、動かさずに離すとメニューを出す。
 * フォルダの中のアイコンを一覧の外まで運ぶと、ホームのコマへ運ぶ操作に切り替わる（「すべて」では長押しですぐ運ぶ）。
 *
 * [launches] は起動回数（「よく使う」と検索の並べ替え）。[loadShortcuts] は検索のためのショートカットで、
 * 開いたときと、アプリ一覧の読み込みが済んだときに呼ぶ（読み込み前に開くと、インストール済みのアプリが分からず空になるため）。IO のスレッドで呼ぶのは呼び出し側。
 *
 * ホームを表示している間も作っておき（[open] が false の間は画面の外へずらして透明にする）、開くときは表示を切り替えるだけにする。
 * 開くたびに一覧を一から作ると、UI スレッドで 30〜90ms かかり、開く瞬間に引っかかるため。
 * キーボードは、下から出てくる動きが終わってから出す（2 つの動きが重なると、配置の計算し直しでさらに引っかかるため）。
 */
@Composable
fun DrawerScreen(
    apps: List<AppEntry>,
    settings: DrawerSettings,
    launches: Map<AppKey, Int>,
    loadShortcuts: suspend () -> List<AppShortcut>,
    actions: DrawerActions,
    modifier: Modifier = Modifier,
    /** false の間は閉じている（画面の外で透明にしておく）。 */
    open: Boolean = true,
) {
    BackHandler(enabled = open, onBack = actions.onDismiss)
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val hidden = settings.hidden
    val visible = remember(apps, hidden) { apps.filter { it.key !in hidden } }
    val folders = remember(visible, settings) { DrawerFolders.of(visible, settings) }
    // 左端の列の幅のぶん、アプリの列を 1 つ減らす。検索の間は列を隠すので、設定の列数のまま
    val columns = if (q.isEmpty()) (settings.columns - 1).coerceAtLeast(3) else settings.columns
    val frequent = remember(visible, launches, columns) { DrawerList.frequent(visible, hidden, launches, columns) }
    // 選んでいるフォルダの id。null なら「すべて」。中身が無くなってフォルダが消えたら「すべて」に戻す
    var tab by remember { mutableStateOf<String?>(null) }
    val folder = folders.firstOrNull { it.id == tab }
    // 長押しして開いたフォルダのメニューと、記号を選ぶ画面のフォルダの id
    var folderMenu by remember { mutableStateOf<String?>(null) }
    var symbolPicker by remember { mutableStateOf<String?>(null) }
    // 動かしている間だけの順。指を離したら保存し、保存した順から作り直した一覧に戻す
    var draggingApps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var draggingFolders by remember { mutableStateOf<List<DrawerFolder>?>(null) }
    var dragKey by remember { mutableStateOf<Any?>(null) }
    val shownFolders = draggingFolders ?: folders
    BackHandler(enabled = open && (folderMenu != null || symbolPicker != null)) {
        folderMenu = null
        symbolPicker = null
    }
    val ranked = remember(visible, launches, q) { if (q.isEmpty()) emptyList() else AppSearch.rank(q, visible, launches) }
    val shortcuts by produceState(emptyList<AppShortcut>(), apps.isEmpty(), open) { if (open) value = loadShortcuts() }
    val visiblePackages = remember(visible) { visible.mapTo(HashSet()) { it.key.packageName } }
    val rankedShortcuts =
        remember(shortcuts, visiblePackages, q) {
            if (q.isEmpty()) emptyList() else AppSearch.rankShortcuts(q, shortcuts.filter { it.packageName in visiblePackages }).take(MAX_SHORTCUTS)
        }
    // ショートカットの行に表示するアプリの名前とアイコン
    // 追加したショートカット（PWA など）はブラウザと同じパッケージを持つので、アプリだけから引く
    val appOf = remember(visible) { visible.filterNot { it.key.isShortcut }.associateBy { it.key.packageName } }
    val grid = rememberLazyGridState()
    val rail = rememberLazyListState()
    var gridOrigin by remember { mutableStateOf(Offset.Zero) }
    val dnd = LocalDragDrop.current
    val slide = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(open) {
        if (open) {
            // 開くたびに、空の検索と一覧の先頭から始める。キーボードは出てくる動きが終わってから出す
            query = ""
            grid.scrollToItem(0)
            slide.snapTo(1f)
            slide.animateTo(0f, tween(220))
            // フォルダを開いたまま閉じたときは、キーボードを出さない（中身を見に戻ったので）
            if (tab == null) {
                focus.requestFocus()
                keyboard?.show()
            }
        } else {
            keyboard?.hide()
            focusManager.clearFocus()
            slide.snapTo(1f)
            folderMenu = null
            symbolPicker = null
        }
    }
    // フォルダを選んだら、キーボードを閉じて中身を見せる。左端の列は選んだフォルダが見える位置へ送る（左右に払って移ったとき）
    LaunchedEffect(tab) {
        if (tab != null) {
            keyboard?.hide()
            focusManager.clearFocus()
        }
        val index = if (tab == null) 0 else folders.indexOfFirst { it.id == tab } + 1
        val shown = rail.layoutInfo.visibleItemsInfo
        if (shown.isNotEmpty() && (index <= shown.first().index || index >= shown.last().index)) rail.animateScrollToItem((index - 2).coerceAtLeast(0))
    }
    LaunchedEffect(folderMenu != null) { if (folderMenu != null) keyboard?.hide() }
    // アイコンを運び始めたらキーボードを閉じる。一覧が透明になって見えるホームの下側のコマを、キーボードが隠すため
    LaunchedEffect(dnd.isDragging) { if (dnd.isDragging) keyboard?.hide() }
    // 入力やフォルダが変わったら先頭へ戻す（先頭の候補が見えるように）
    LaunchedEffect(q, tab) { grid.scrollToItem(0) }

    Box(
        modifier
            .fillMaxSize()
            // 閉じている間は画面の外へずらし、指の操作を受けないようにする（透明なので描画も省かれる）
            .offset { if (open) IntOffset.Zero else IntOffset(0, 1_000_000) }
            // 閉じている間は、読み上げと画面の自動操作の対象から外す
            .then(if (open) Modifier else Modifier.clearAndSetSemantics {})
            .graphicsLayer {
                translationY = slide.value * size.height * 0.3f
                // 運んでいる間は透明にしてホームのコマを見せる。取り除くと指の追跡が途切れるので合成したままにする
                alpha = if (dnd.isDragging) 0f else 1f - slide.value
            }.background(Color(0xF2121216))
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total > 120.dp.toPx()) actions.onDismiss() },
                ) { _, dy ->
                    // 一覧が一番上にあるときだけ、下へ引いて閉じる
                    if (grid.firstVisibleItemIndex == 0 && grid.firstVisibleItemScrollOffset == 0) total += dy
                }
            },
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            SearchField(
                query = query,
                onQuery = { query = it },
                focus = focus,
                onGo = {
                    // 先頭のアプリ、アプリが 1 つも一致しなければ先頭のショートカットを起動する
                    ranked.firstOrNull()?.let { actions.onLaunch(it.app.key) }
                        ?: rankedShortcuts.firstOrNull()?.let { actions.onLaunchShortcut(it.shortcut) }
                },
                onOpenSettings = { scope.launch { actions.onOpenSettings() } },
            )
            Row(Modifier.fillMaxSize()) {
                if (q.isEmpty()) {
                    FolderRail(
                        shownFolders,
                        folder?.id,
                        dragKey = dragKey,
                        state = rail,
                        onSelect = { tab = it },
                        modifier =
                        Modifier.reorderable(
                            folders,
                            ReorderHandler(
                                // 先頭の「すべて」は動かさないので、フォルダの位置は項目の番号から 1 を引く
                                itemAt = { p -> rail.layoutInfo.visibleItemsInfo.firstOrNull { p.y.toInt() in it.offset until it.offset + it.size && it.index > 0 }?.let { it.key to it.index - 1 } },
                                onStart = { key ->
                                    dragKey = key
                                    draggingFolders = folders
                                },
                                onMove = { key, to -> draggingFolders?.let { l -> draggingFolders = DrawerFolders.moveItem(l, l.indexOfFirst { it.id == key }, to) } },
                                onEnd = { moved ->
                                    if (moved) draggingFolders?.let { l -> actions.onReorderFolders(l.map { it.id }) }
                                    draggingFolders = null
                                    dragKey = null
                                },
                                onRelease = { key -> folderMenu = key as String },
                                onEdge = { dy -> rail.dispatchRawDelta(dy) },
                            ),
                        ),
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = grid,
                    modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 4.dp)
                        .onGloballyPositioned { gridOrigin = it.positionInRoot() }
                        .then(if (q.isEmpty()) Modifier.swipeFolders(folders, tab) { tab = it } else Modifier)
                        .then(
                            if (q.isNotEmpty() || folder == null) {
                                Modifier
                            } else {
                                Modifier.reorderable(
                                    folder,
                                    ReorderHandler(
                                        itemAt = { p ->
                                            grid.layoutInfo.visibleItemsInfo
                                                .firstOrNull { p.x.toInt() in it.offset.x until it.offset.x + it.size.width && p.y.toInt() in it.offset.y until it.offset.y + it.size.height }
                                                ?.let { it.key to it.index }
                                        },
                                        onStart = { key ->
                                            dragKey = key
                                            draggingApps = folder.apps
                                        },
                                        onMove = { key, to -> draggingApps?.let { l -> draggingApps = DrawerFolders.moveItem(l, l.indexOfFirst { it.key.id() == key }, to) } },
                                        onEnd = { moved ->
                                            if (moved) draggingApps?.let { l -> actions.onReorderApps(folder.id, l.map { it.key }) }
                                            draggingApps = null
                                            dragKey = null
                                        },
                                        onRelease = { key -> folder.apps.firstOrNull { it.key.id() == key }?.let { actions.onShowAppMenu(it.key) } },
                                        // 一覧の外まで運んだら、並べ替えをやめて、ホームのコマへ運ぶ操作に切り替える
                                        onLeave = { key, at ->
                                            val app = folder.apps.firstOrNull { it.key.id() == key }
                                            draggingApps = null
                                            dragKey = null
                                            app?.let { dnd.start(DragItem(it.key, DragSource.Drawer), gridOrigin + at) }
                                            app != null
                                        },
                                        onHandoffMove = { dnd.move(it) },
                                        onHandoffEnd = { dnd.end() },
                                        // 一覧の上下の端まで運んだら、続きが見えるようにスクロールする
                                        onEdge = { dy -> grid.dispatchRawDelta(dy) },
                                    ),
                                )
                            },
                        ),
                ) {
                    when {
                        q.isNotEmpty() -> {
                            itemsIndexed(ranked) { i, r -> DrawerItem(r.app, r, i == 0, actions, dnd) }
                            if (rankedShortcuts.isNotEmpty()) {
                                header("ショートカット")
                                rankedShortcuts.forEach { r ->
                                    item(span = { GridItemSpan(maxLineSpan) }, key = "shortcut/${r.shortcut.packageName}/${r.shortcut.id}") {
                                        ShortcutRow(r, appOf[r.shortcut.packageName], actions)
                                    }
                                }
                            }
                        }

                        folder != null ->
                            items(draggingApps ?: folder.apps, key = { it.key.id() }) { app ->
                                FolderItem(app, app.key.id() == dragKey, Modifier.animateItem()) { actions.onLaunch(app.key) }
                            }

                        else -> {
                            if (frequent.isNotEmpty()) {
                                header("よく使う")
                                items(frequent, key = { "frequent/" + it.key.id() }) { app -> DrawerItem(app, null, false, actions, dnd) }
                                item(span = { GridItemSpan(maxLineSpan) }, key = "divider") {
                                    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).height(1.dp).background(Color(0x33FFFFFF)))
                                }
                            }
                            items(visible, key = { it.key.id() }) { app -> DrawerItem(app, null, false, actions, dnd) }
                        }
                    }
                }
            }
        }
        folderMenu?.let { id ->
            folders.firstOrNull { it.id == id }?.let { f ->
                FolderMenu(
                    f,
                    onRename = {
                        folderMenu = null
                        actions.onRenameFolder(id)
                    },
                    onChangeSymbol = {
                        folderMenu = null
                        symbolPicker = id
                    },
                    onDismiss = { folderMenu = null },
                )
            }
        }
        symbolPicker?.let { id ->
            SymbolPicker(
                current = folders.firstOrNull { it.id == id }?.symbol ?: DrawerFolders.FOLDER_SYMBOL,
                onPick = {
                    actions.onSetFolderSymbol(id, it)
                    symbolPicker = null
                },
                onDismiss = { symbolPicker = null },
            )
        }
    }
}

/**
 * 一覧を左右に払うと、左端の列の順（「すべて」が先頭）で前後のフォルダへ移る。左へ払うと次、右へ払うと前。
 * 長押しの並べ替えは動きを消費するので、並べ替えの途中で払ったことにはならない。
 */
private fun Modifier.swipeFolders(
    folders: List<DrawerFolder>,
    current: String?,
    onChange: (String?) -> Unit,
): Modifier = pointerInput(folders, current) {
    val order = listOf<String?>(null) + folders.map { it.id }
    var total = 0f
    detectHorizontalDragGestures(
        onDragStart = { total = 0f },
        onDragEnd = {
            val at = order.indexOf(current)
            val next = at + if (total < 0) 1 else -1
            if (kotlin.math.abs(total) > 72.dp.toPx() && next in order.indices) onChange(order[next])
        },
    ) { change, dx ->
        change.consume()
        total += dx
    }
}

@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    focus: FocusRequester,
    onGo: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(Color(0xFF2A2A30), RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (query.isEmpty()) BasicText("アプリを検索", style = TextStyle(color = Color(0xFF888888), fontSize = 15.sp))
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
            modifier = Modifier.fillMaxWidth().padding(end = 40.dp).focusRequester(focus),
        )
        BasicText(
            "設定",
            style = TextStyle(color = Color(0xFFBBBBBB), fontSize = 13.sp),
            modifier = Modifier.align(Alignment.CenterEnd).clickable(onClick = onOpenSettings),
        )
    }
}

// 同じアプリの追加したショートカットどうしが重ならないよう、ショートカットの id も含める
private fun AppKey.id() = "$packageName/$className/${shortcutId.orEmpty()}"

private fun LazyGridScope.header(text: String) {
    item(span = { GridItemSpan(maxLineSpan) }, key = "header/$text") {
        BasicText(text, style = TextStyle(color = Color(0xFF999999), fontSize = 12.sp), modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp))
    }
}

private fun LazyGridScope.itemsIndexed(
    list: List<Ranked>,
    content: @Composable (Int, Ranked) -> Unit,
) {
    list.forEachIndexed { i, r -> item(key = "result/" + r.app.key.id()) { content(i, r) } }
}

/** 一致した文字を太字にした名前。 */
private fun highlighted(
    text: String,
    positions: List<Int>,
): AnnotatedString = buildAnnotatedString {
    append(text)
    val bold = SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFFFFE082))
    positions.filter { it < text.length }.forEach { addStyle(bold, it, it + 1) }
}

/**
 * 「すべて」と検索結果のアイコン。長押しするとすぐホームへ運ぶ（draggableApp）。
 * [result] が null でなければ一致した文字を太字にし、[first] なら Enter で開くことを示す枠で囲む。
 */
@Composable
private fun DrawerItem(
    app: AppEntry,
    result: Ranked?,
    first: Boolean,
    actions: DrawerActions,
    dnd: DragDropState,
) {
    Column(
        Modifier
            .padding(2.dp)
            .then(if (first) Modifier.border(1.5.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier)
            .padding(vertical = 8.dp)
            .draggableApp(DragItem(app.key, DragSource.Drawer), dnd) { actions.onLaunch(app.key) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { ItemBody(app, if (result != null) highlighted(app.label, result.match.positions) else AnnotatedString(app.label)) }
}

/** フォルダの中のアイコン。長押しは一覧の reorderable が受け、ここでは押したときの起動だけを受ける。 */
@Composable
private fun FolderItem(
    app: AppEntry,
    dragging: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .lifted(dragging)
            .padding(2.dp)
            .background(if (dragging) Color(0x33FFFFFF) else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { ItemBody(app, AnnotatedString(app.label)) }
}

@Composable
private fun ItemBody(
    app: AppEntry,
    label: AnnotatedString,
) {
    AppIcon(app.key, 52.dp)
    BasicText(
        label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center),
        modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
    )
}

@Composable
private fun ShortcutRow(
    r: RankedShortcut,
    app: AppEntry?,
    actions: DrawerActions,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { actions.onLaunchShortcut(r.shortcut) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (app != null) AppIcon(app.key, 32.dp)
        BasicText(
            buildAnnotatedString {
                if (app != null) append(app.label + " · ")
                append(highlighted(r.shortcut.label, r.match.positions))
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = Color.White, fontSize = 14.sp),
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
