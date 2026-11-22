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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * コマのフォルダと全アプリ一覧に登録してあるのに、この端末に入っていないアプリ。機種変更の後に入れ直すために使う。
 * 入っていないアプリのアイコンは取り出せないので、名前の頭文字を色の付いた四角に描いて、全アプリ一覧と同じ格子に並べる。
 * 押すと Play ストアのそのアプリのページを開く（[onOpen]）。入れると、前に登録したフォルダへ自動で入り、この一覧から消える。
 */
@Composable
internal fun MissingAppsScreen(
    /** パッケージ名と名前。名前が分からなければパッケージ名。 */
    missing: List<Pair<String, String>>,
    columns: Int,
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color(0xFF121216)).clickable(enabled = false) {}) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText("未インストールのアプリ", style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                    BasicText(
                        if (missing.isEmpty()) "登録したアプリはすべてインストール済み" else "タップすると Play ストアを開きます。インストールすると元のフォルダに戻ります",
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
                items(missing, key = { it.first }) { (pkg, name) ->
                    Column(
                        Modifier.padding(2.dp).clickable { onOpen(pkg) }.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(52.dp).background(tileColor(name), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) { BasicText(name.take(1), style = TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)) }
                        BasicText(
                            name,
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

/** 名前ごとに決まる、落ち着いた色。同じ名前はいつも同じ色になる。 */
private fun tileColor(name: String): Color {
    val hue = (name.fold(0) { h, c -> (h * 31 + c.code) % 360 }).toFloat()
    return Color.hsl(hue, 0.35f, 0.38f)
}
