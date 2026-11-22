package dev.furunov.komalauncher.feature.drawer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
    /** フォルダのタブを長押ししたとき。引数はフォルダの id。 */
    val onRenameFolder: (String) -> Unit = {},
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
                    // 一覧が一番上にあるときだけ、下へ引いて閉じる
                    if (grid.firstVisibleItemIndex == 0 && grid.firstVisibleItemScrollOffset == 0) total += dy
                }
            },
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
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
            // 検索している間は全てのアプリから探すので、タブを隠す
            if (q.isEmpty() && folders.isNotEmpty()) {
                FolderTabs(folders, folder?.id, onSelect = { tab = it }, onLongPress = actions.onRenameFolder)
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = grid,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            ) {
                if (q.isEmpty() && folder != null) {
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
    }
}

/** 検索欄の下のタブ。先頭の「すべて」（[selected] が null）の後に、中身のあるフォルダを並べる。フォルダのタブを長押しすると名前を変える。 */
@Composable
private fun FolderTabs(
    folders: List<DrawerFolder>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onLongPress: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        item(key = "all") { Tab("すべて", selected == null, Modifier.clickable { onSelect(null) }) }
        items(folders, key = { it.id }) { f ->
            Tab(
                f.name,
                f.id == selected,
                Modifier.pointerInput(f.id) { detectTapGestures(onTap = { onSelect(f.id) }, onLongPress = { onLongPress(f.id) }) },
            )
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
