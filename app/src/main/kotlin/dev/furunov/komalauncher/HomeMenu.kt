package dev.furunov.komalauncher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** コマの間の余白を長押ししたときのメニュー。 */
@Composable
fun HomeMenu(
    onEditLayout: () -> Unit,
    /** 時計・大見出し・名前・アイコンをホームの上で直接動かす「配置を調整」に入る。 */
    onAdjust: () -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
    /** null なら「ウィジェットを追加」を表示しない（version 1 のコマ割りには置けない）。 */
    onAddWidget: (() -> Unit)? = null,
) {
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        Column(Modifier.width(240.dp).background(Color(0xFF1E1E24), RoundedCornerShape(16.dp)).padding(8.dp)) {
            MenuItem("配置を調整", onAdjust)
            onAddWidget?.let { MenuItem("ウィジェットを追加", it) }
            MenuItem("コマ割りを編集", onEditLayout)
            MenuItem("ランチャーの設定", onSettings)
        }
    }
}

@Composable
private fun MenuItem(
    text: String,
    onClick: () -> Unit,
) {
    BasicText(
        text,
        style = TextStyle(color = Color.White, fontSize = 16.sp),
        // 文字の部分だけでなく、行の幅いっぱいを押せるようにする
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
    )
}
