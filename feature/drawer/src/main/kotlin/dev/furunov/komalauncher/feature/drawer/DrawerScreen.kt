package dev.furunov.komalauncher.feature.drawer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.furunov.komalauncher.model.apps.AppEntry
import dev.furunov.komalauncher.model.apps.AppKey
import dev.furunov.komalauncher.model.apps.AppSearch
import dev.furunov.komalauncher.model.apps.AppShortcut
import dev.furunov.komalauncher.model.apps.DrawerList
import dev.furunov.komalauncher.model.apps.Ranked
import dev.furunov.komalauncher.model.apps.RankedShortcut
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
)

/** 検索で表示するショートカットの最大の件数。 */
private const val MAX_SHORTCUTS = 8

/**
 * 全アプリ一覧。LazyVerticalGrid なので、アイコンは画面に見えている分だけ読む。
 * 開いたらすぐ打てるよう、検索欄にフォーカスしてキーボードを表示する。Enter で先頭を起動する。
 * 一番上で下へ引くと閉じる。
 *
 * [launches] は起動回数（「よく使う」と検索の並べ替え）。[loadShortcuts] は検索のためのショートカットで、
 * 開いたときに 1 回だけ呼ぶ（IO のスレッドで呼ぶのは呼び出し側）。
 */
@Composable
fun DrawerScreen(
    apps: List<AppEntry>,
    columns: Int,
    hidden: Set<AppKey>,
    launches: Map<AppKey, Int>,
    loadShortcuts: suspend () -> List<AppShortcut>,
    actions: DrawerActions,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = actions.onDismiss)
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val visible = remember(apps, hidden) { apps.filter { it.key !in hidden } }
    val frequent = remember(visible, launches, columns) { DrawerList.frequent(visible, hidden, launches, columns) }
    val ranked = remember(visible, launches, q) { if (q.isEmpty()) emptyList() else AppSearch.rank(q, visible, launches) }
    val shortcuts by produceState(emptyList<AppShortcut>()) { value = loadShortcuts() }
    val visiblePackages = remember(visible) { visible.mapTo(HashSet()) { it.key.packageName } }
    val rankedShortcuts =
        remember(shortcuts, visiblePackages, q) {
            if (q.isEmpty()) emptyList() else AppSearch.rankShortcuts(q, shortcuts.filter { it.packageName in visiblePackages }).take(MAX_SHORTCUTS)
        }
    // ショートカットの行に表示するアプリの名前とアイコン
    val appOf = remember(visible) { visible.associateBy { it.key.packageName } }
    val grid = rememberLazyGridState()
    val dnd = LocalDragDrop.current
    val slide = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
        slide.animateTo(0f, tween(220))
    }
    // 入力が変わったら先頭へ戻す（先頭の候補が見えるように）
    LaunchedEffect(q) { grid.scrollToItem(0) }

    Box(
        modifier
            .fillMaxSize()
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
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = grid,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            ) {
                if (q.isEmpty()) {
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

private fun AppKey.id() = "$packageName/$className"

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
