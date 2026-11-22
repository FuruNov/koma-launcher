package dev.furunov.komalauncher.feature.drawer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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
import androidx.compose.ui.zIndex
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
    /** フォルダのタブのメニューの「名前を変更」。引数はフォルダの id。 */
    val onRenameFolder: (String) -> Unit = {},
    /** 並べ替えで指を離したとき。フォルダの id と、そのフォルダの全てのアプリの新しい順。 */
    val onReorderApps: (String, List<AppKey>) -> Unit = { _, _ -> },
    /** 並べ替えでタブを動かしたとき。全てのフォルダの id の新しい順。 */
    val onReorderTabs: (List<String>) -> Unit = {},
)

/** 検索で表示するショートカットの最大の件数。 */
private const val MAX_SHORTCUTS = 8

/**
 * 全アプリ一覧。LazyVerticalGrid なので、アイコンは画面に見えている分だけ読む。
 * 開いたらすぐ打てるよう、検索欄にフォーカスしてキーボードを表示する。Enter で先頭を起動する。
 * 一番上で下へ引くと閉じる。
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
    val columns = settings.columns
    val hidden = settings.hidden
    val visible = remember(apps, hidden) { apps.filter { it.key !in hidden } }
    val frequent = remember(visible, launches, columns) { DrawerList.frequent(visible, hidden, launches, columns) }
    val folders = remember(visible, settings) { DrawerFolders.of(visible, settings) }
    // 選んでいるフォルダのタブの id。null なら「すべて」。中身が無くなってタブが消えたら「すべて」に戻す
    var tab by remember { mutableStateOf<String?>(null) }
    val folder = folders.firstOrNull { it.id == tab }
    // タブを長押しして開いたメニューのフォルダの id
    var tabMenu by remember { mutableStateOf<String?>(null) }
    // true の間は並べ替えの状態。アイコンとタブを長押しして動かすと順が変わり、起動とホームへ運ぶ操作はしない
    var reordering by remember { mutableStateOf(false) }
    // 動かしている間だけの順。指を離したら保存し、保存した順から作り直した一覧に戻す
    var draggingApps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var draggingTabs by remember { mutableStateOf<List<DrawerFolder>?>(null) }
    var dragKey by remember { mutableStateOf<Any?>(null) }
    val shownApps = draggingApps ?: folder?.apps.orEmpty()
    val shownTabs = draggingTabs ?: folders
    // 並べ替えるフォルダのタブが消えたら（中身が無くなったら）、並べ替えを終える
    LaunchedEffect(folder == null) { if (folder == null) reordering = false }
    BackHandler(enabled = open && (reordering || tabMenu != null)) {
        if (tabMenu != null) tabMenu = null else reordering = false
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
            focus.requestFocus()
            keyboard?.show()
        } else {
            keyboard?.hide()
            focusManager.clearFocus()
            slide.snapTo(1f)
            reordering = false
            tabMenu = null
        }
    }
    // タブのメニューと並べ替えの間は、キーボードを閉じる（メニューに重なるため）
    LaunchedEffect(tabMenu != null) { if (tabMenu != null) keyboard?.hide() }
    LaunchedEffect(reordering) {
        if (reordering) {
            query = ""
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }
    // アイコンを運び始めたらキーボードを閉じる。一覧が透明になって見えるホームの下側のコマを、キーボードが隠すため
    LaunchedEffect(dnd.isDragging) { if (dnd.isDragging) keyboard?.hide() }
    // 入力が変わったら先頭へ戻す（先頭の候補が見えるように）
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
                    // 一覧が一番上にあるときだけ、下へ引いて閉じる。並べ替えの間は閉じない
                    if (!reordering && grid.firstVisibleItemIndex == 0 && grid.firstVisibleItemScrollOffset == 0) total += dy
                }
            },
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (reordering) {
                ReorderBar(onDone = { reordering = false })
            } else {
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
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions =
                        KeyboardActions(onGo = {
                            // 先頭のアプリ、アプリが 1 つも一致しなければ先頭のショートカットを起動する
                            ranked.firstOrNull()?.let { actions.onLaunch(it.app.key) }
                                ?: rankedShortcuts.firstOrNull()?.let { actions.onLaunchShortcut(it.shortcut) }
                        }),
                        modifier = Modifier.fillMaxWidth().padding(end = 40.dp).focusRequester(focus),
                    )
                    BasicText(
                        "設定",
                        style = TextStyle(color = Color(0xFFBBBBBB), fontSize = 13.sp),
                        modifier =
                        Modifier
                            .align(Alignment.CenterEnd)
                            .clickable { scope.launch { actions.onOpenSettings() } },
                    )
                }
            }
            // 検索している間は全てのアプリから探すので、タブを隠す
            if (q.isEmpty() && folders.isNotEmpty()) {
                val tabs = rememberLazyListState()
                FolderTabs(
                    shownTabs,
                    folder?.id,
                    state = tabs,
                    reordering = reordering,
                    dragKey = dragKey,
                    onSelect = { tab = it },
                    onLongPress = { tabMenu = it },
                    modifier =
                    if (!reordering) {
                        Modifier
                    } else {
                        // 先頭の「すべて」は動かさないので、フォルダの位置は項目の番号から 1 を引く
                        Modifier.reorderable(
                            key = folders,
                            itemAt = { p -> tabs.layoutInfo.visibleItemsInfo.firstOrNull { p.x.toInt() in it.offset until it.offset + it.size && it.index > 0 }?.let { it.key to it.index - 1 } },
                            onStart = { key ->
                                dragKey = key
                                draggingTabs = folders
                            },
                            onMove = { key, to -> draggingTabs?.let { l -> draggingTabs = DrawerFolders.moveItem(l, l.indexOfFirst { it.id == key }, to) } },
                            onEnd = {
                                draggingTabs?.let { l -> actions.onReorderTabs(l.map { it.id }) }
                                draggingTabs = null
                                dragKey = null
                            },
                            horizontal = true,
                            onEdge = { dx -> tabs.dispatchRawDelta(dx) },
                        )
                    },
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = grid,
                modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp)
                    .then(
                        if (!reordering || folder == null) {
                            Modifier
                        } else {
                            Modifier.reorderable(
                                key = folder,
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
                                onEnd = {
                                    draggingApps?.let { l -> actions.onReorderApps(folder.id, l.map { it.key }) }
                                    draggingApps = null
                                    dragKey = null
                                },
                                // 一覧の上下の端まで運んだら、続きが見えるようにスクロールする
                                onEdge = { dy -> grid.dispatchRawDelta(dy) },
                            )
                        },
                    ),
            ) {
                if (q.isEmpty() && folder != null && reordering) {
                    items(shownApps, key = { it.key.id() }) { app -> ReorderItem(app, app.key.id() == dragKey, Modifier.animateItem()) }
                } else if (q.isEmpty() && folder != null) {
                    items(folder.apps, key = { it.key.id() }) { app -> DrawerItem(app, null, false, actions, dnd) }
                } else if (q.isEmpty()) {
                    if (frequent.isNotEmpty()) {
                        header("よく使う")
                        items(frequent, key = { "frequent/" + it.key.id() }) { app -> DrawerItem(app, null, false, actions, dnd) }
                        item(span = { GridItemSpan(maxLineSpan) }, key = "divider") {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).height(1.dp).background(Color(0x33FFFFFF)))
                        }
                    }
                    items(visible, key = { it.key.id() }) { app -> DrawerItem(app, null, false, actions, dnd) }
                } else {
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
            }
        }
        tabMenu?.let { id ->
            TabMenu(
                name = folders.firstOrNull { it.id == id }?.name ?: id,
                onRename = {
                    tabMenu = null
                    actions.onRenameFolder(id)
                },
                onReorder = {
                    tabMenu = null
                    tab = id
                    reordering = true
                },
                onDismiss = { tabMenu = null },
            )
        }
    }
}

/**
 * 検索欄の下のタブ。先頭の「すべて」（[selected] が null）の後に、中身のあるフォルダを並べる。フォルダのタブを長押しするとメニューを出す。
 * 並べ替えの間（[reordering]）は、長押しを [modifier] の並べ替えに任せ、「すべて」は選べなくする。
 */
@Composable
private fun FolderTabs(
    folders: List<DrawerFolder>,
    selected: String?,
    state: LazyListState,
    reordering: Boolean,
    dragKey: Any?,
    onSelect: (String?) -> Unit,
    onLongPress: (String) -> Unit,
    modifier: Modifier,
) {
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).then(modifier),
    ) {
        item(key = "all") { Tab("すべて", selected == null, if (reordering) Modifier else Modifier.clickable { onSelect(null) }) }
        items(folders, key = { it.id }) { f ->
            Tab(
                f.name,
                f.id == selected,
                Modifier
                    .animateItem()
                    .lifted(f.id == dragKey)
                    .pointerInput(f.id, reordering) {
                        if (reordering) {
                            detectTapGestures(onTap = { onSelect(f.id) })
                        } else {
                            detectTapGestures(onTap = { onSelect(f.id) }, onLongPress = { onLongPress(f.id) })
                        }
                    },
            )
        }
    }
}

/** 並べ替えの間、検索欄の代わりに表示する帯。 */
@Composable
private fun ReorderBar(onDone: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(Color(0xFF2A2A30), RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("アイコンとタブを長押しして移動", style = TextStyle(color = Color(0xFFBBBBBB), fontSize = 15.sp), modifier = Modifier.weight(1f))
        BasicText(
            "完了",
            style = TextStyle(color = Color(0xFFFFD54F), fontSize = 15.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.clickable(onClick = onDone).padding(horizontal = 4.dp),
        )
    }
}

/** 並べ替えの間のアイコン。押しても起動せず、ホームへも運ばない。 */
@Composable
private fun ReorderItem(
    app: AppEntry,
    dragging: Boolean,
    modifier: Modifier,
) {
    Column(
        modifier
            .lifted(dragging)
            .padding(2.dp)
            .background(if (dragging) Color(0x33FFFFFF) else Color.Transparent, RoundedCornerShape(12.dp))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(app.key, 52.dp)
        BasicText(
            app.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center),
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/** 動かしている項目を少し大きくし、ほかの項目より手前に描く。 */
private fun Modifier.lifted(on: Boolean): Modifier = if (!on) {
    this
} else {
    zIndex(1f).graphicsLayer {
        scaleX = 1.12f
        scaleY = 1.12f
    }
}

/**
 * 長押しして動かすと、指の下の項目の位置へ移す操作。[itemAt] は指の位置にある項目のキーと番号を返す。
 * 動かしている間は [onMove] で表示の順だけを変え、指を離したら [onEnd] で保存する。
 * [onEdge] は指が端（[horizontal] なら左右、そうでなければ上下）にあるときのスクロール量。
 *
 * 長押しが成立した後の指の動きは、子より先（Initial）に受け取って消費する。
 * detectDragGesturesAfterLongPress（Main）では、一覧のスクロールが先に動きを消費し、並べ替えずにスクロールした（2026-11-22 に実機で確認）。
 */
private fun Modifier.reorderable(
    key: Any?,
    itemAt: (Offset) -> Pair<Any, Int>?,
    onStart: (Any) -> Unit,
    onMove: (Any, Int) -> Unit,
    onEnd: () -> Unit,
    horizontal: Boolean = false,
    onEdge: (Float) -> Unit = {},
): Modifier = pointerInput(key) {
    val edge = 64.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // 長押しの前に指が動いたら（スクロールなら）、何もしない
        awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        var pointer = down.position
        val moving = itemAt(pointer)?.first ?: return@awaitEachGesture
        onStart(moving)
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            pointer += change.positionChange()
            change.consume()
            val along = if (horizontal) pointer.x else pointer.y
            val length = if (horizontal) size.width else size.height
            when {
                along < edge -> onEdge(-(edge - along) / 4)
                along > length - edge -> onEdge((along - (length - edge)) / 4)
            }
            itemAt(pointer)?.let { (over, index) -> if (over != moving) onMove(moving, index) }
        }
        onEnd()
    }
}

/** フォルダのタブを長押ししたときのメニュー。 */
@Composable
private fun TabMenu(
    name: String,
    onRename: () -> Unit,
    onReorder: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color(0x66000000)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .background(Color(0xFF1E1E24), RoundedCornerShape(16.dp))
                .clickable(enabled = false) {}
                .padding(vertical = 8.dp),
        ) {
            BasicText(name, style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(16.dp))
            listOf("名前を変更" to onRename, "並べ替え" to onReorder).forEach { (text, onClick) ->
                BasicText(
                    text,
                    style = TextStyle(color = Color.White, fontSize = 15.sp),
                    modifier = Modifier.width(240.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun Tab(
    text: String,
    selected: Boolean,
    modifier: Modifier,
) {
    BasicText(
        text,
        style = TextStyle(color = if (selected) Color.White else Color(0xFFAAAAAA), fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
        modifier =
        Modifier
            .background(if (selected) Color(0xFF44444E) else Color(0xFF26262C), RoundedCornerShape(16.dp))
            .then(modifier)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
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

/** [result] が null でなければ一致した文字を太字にし、[first] なら Enter で開くことを示す枠で囲む。 */
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
    ) {
        AppIcon(app.key, 52.dp)
        BasicText(
            if (result != null) highlighted(app.label, result.match.positions) else AnnotatedString(app.label),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center),
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
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
