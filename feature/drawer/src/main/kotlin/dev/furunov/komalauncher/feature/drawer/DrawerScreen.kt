package dev.furunov.komalauncher.feature.drawer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.furunov.komalauncher.model.apps.AppEntry
import dev.furunov.komalauncher.model.apps.AppKey
import dev.furunov.komalauncher.ui.icons.AppIcon
import kotlinx.coroutines.launch

/** 全アプリ一覧の操作の受け手。ホームとのやり取りは :app がつなぐ。 */
class DrawerActions(
    val onLaunch: (AppKey) -> Unit,
    val onDismiss: () -> Unit,
    /** アイコンの長押し。ホームのコマへ運ぶドラッグの開始や、メニューの表示に使う。 */
    val onLongPress: (AppKey) -> Unit = {},
    val onOpenSettings: () -> Unit = {},
)

/**
 * 全アプリ一覧。LazyVerticalGrid なので、アイコンは画面に見えている分だけ読む。
 * 一番上で下へ引くと閉じる。
 */
@Composable
fun DrawerScreen(
    apps: List<AppEntry>,
    columns: Int,
    hidden: Set<AppKey>,
    actions: DrawerActions,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = actions.onDismiss)
    var query by remember { mutableStateOf("") }
    val shown =
        remember(apps, hidden, query) {
            val q = query.trim()
            apps.filter { it.key !in hidden && (q.isEmpty() || it.label.contains(q, ignoreCase = true)) }
        }
    val grid = rememberLazyGridState()
    val slide = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { slide.animateTo(0f, tween(220)) }

    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = slide.value * size.height * 0.3f
                alpha = 1f - slide.value
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
                    modifier = Modifier.fillMaxWidth(),
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
                items(shown, key = { "${it.key.packageName}/${it.key.className}" }) { app ->
                    DrawerItem(app, actions)
                }
            }
        }
    }
}

@Composable
private fun DrawerItem(
    app: AppEntry,
    actions: DrawerActions,
) {
    Column(
        Modifier
            .padding(vertical = 10.dp)
            .pointerInput(app.key) {
                detectTapGestures(
                    onTap = { actions.onLaunch(app.key) },
                    onLongPress = { actions.onLongPress(app.key) },
                )
            },
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
