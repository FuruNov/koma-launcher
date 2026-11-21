package dev.furunov.komalauncher.ui.apps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.furunov.komalauncher.model.apps.AppEntry
import dev.furunov.komalauncher.model.apps.AppKey
import dev.furunov.komalauncher.model.apps.AppSearch
import dev.furunov.komalauncher.ui.icons.AppIcon

/**
 * フォルダに入れるアプリを選ぶ画面。行を押すたびに [onToggle] を呼び、選んだ結果はその場で保存側に渡す。
 * 全アプリ一覧からのドラッグを知らなくても、フォルダの中身を決められるようにする。
 */
@Composable
fun AppPicker(
    title: String,
    apps: List<AppEntry>,
    checked: Set<AppKey>,
    onToggle: (AppKey) -> Unit,
    onDone: () -> Unit,
) {
    BackHandler(onBack = onDone)
    var query by remember { mutableStateOf("") }
    // 選んだアプリを先頭に置くと、押すたびに行が動いて押し間違えるので、並びはラベルの順のままにする
    val shown = remember(query, apps) { if (query.isBlank()) apps else AppSearch.rank(query, apps, emptyMap()).map { it.app } }
    Column(Modifier.fillMaxSize().background(Color(0xFF121216)).safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                title,
                style = TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            BasicText(
                "完了",
                style = TextStyle(color = Color(0xFF121216), fontSize = 14.sp),
                modifier =
                Modifier
                    .background(Color(0xFFFFD54F), RoundedCornerShape(18.dp))
                    .clickable(onClick = onDone)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
            cursorBrush = SolidColor(Color.White),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().background(Color(0xFF2A2A32), RoundedCornerShape(8.dp)).padding(12.dp)) {
                    if (query.isEmpty()) BasicText("アプリを検索", style = TextStyle(color = Color(0xFF6A6A74), fontSize = 15.sp))
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
            items(shown, key = { "${it.key.packageName}/${it.key.className}/${it.key.shortcutId.orEmpty()}" }) { app ->
                val on = app.key in checked
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(app.key) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(app.key, 40.dp)
                    Spacer(Modifier.width(14.dp))
                    BasicText(
                        app.label,
                        style = TextStyle(color = Color.White, fontSize = 15.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    CheckMark(on)
                }
            }
        }
    }
}

@Composable
private fun CheckMark(on: Boolean) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .size(24.dp)
            .then(if (on) Modifier.background(Color(0xFFFFD54F), shape) else Modifier.border(2.dp, Color(0xFF6A6A74), shape)),
        contentAlignment = Alignment.Center,
    ) {
        if (on) BasicText("✓", style = TextStyle(color = Color(0xFF121216), fontSize = 16.sp, fontWeight = FontWeight.Bold))
    }
}
