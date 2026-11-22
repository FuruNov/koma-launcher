package dev.furunov.komalauncher.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.furunov.komalauncher.model.apps.AppEntry
import dev.furunov.komalauncher.model.apps.AppKey
import dev.furunov.komalauncher.ui.icons.AppIcon

/**
 * 非表示にしたアプリの一覧。全アプリ一覧と同じく、アイコンと名前を格子に並べる。
 * 文字だけの一覧では、どのアプリか見分けにくいと利用者から指摘があった（2026-11-22）。
 * 押したアプリを全アプリ一覧に戻す（[onShow]）。戻しても画面は閉じず、続けて戻せる。
 * アンインストール済みのアプリは名前が分からないので、パッケージ名で並べる。
 */
@Composable
internal fun HiddenAppsScreen(
    hidden: Set<AppKey>,
    apps: List<AppEntry>,
    columns: Int,
    onShow: (AppKey) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val labels = apps.associate { it.key to it.label }
    val shown = hidden.map { it to (labels[it] ?: it.packageName) }.sortedBy { it.second }
    Box(Modifier.fillMaxSize().background(Color(0xFF121216)).clickable(enabled = false) {}) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText("非表示のアプリ", style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                    BasicText(
                        if (shown.isEmpty()) "非表示のアプリはありません" else "押すとアプリドロワーに戻ります",
                        style = TextStyle(color = Color(0xFF9A9AA2), fontSize = 13.sp),
                    )
                }
                BasicText(
                    "閉じる",
                    style = TextStyle(color = Color(0xFFFFD54F), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.clickable(onClick = onClose).padding(8.dp),
                )
            }
            LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(shown, key = { "${it.first.packageName}/${it.first.className}/${it.first.shortcutId.orEmpty()}" }) { (key, label) ->
                    Column(
                        Modifier.padding(2.dp).clickable { onShow(key) }.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AppIcon(key, 52.dp)
                        BasicText(
                            label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center),
                            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
