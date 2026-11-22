package dev.furunov.komalauncher.feature.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import dev.furunov.komalauncher.model.apps.DrawerFolder
import dev.furunov.komalauncher.model.apps.DrawerFolders

private val Selected = Color(0xFF44444E)
private val Dim = Color(0xFFAAAAAA)

/**
 * 一覧の左端の縦の列。先頭の「すべて」（[selected] が null）の後に、中身のあるフォルダを記号と名前で並べる。
 * 長押しの並べ替えとメニューは [modifier] の [reorderable] が受ける。
 */
@Composable
internal fun FolderRail(
    folders: List<DrawerFolder>,
    selected: String?,
    dragKey: Any?,
    state: LazyListState,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.width(68.dp).fillMaxHeight(),
    ) {
        item(key = "all") { RailItem(ALL_SYMBOL, "すべて", selected == null, Modifier.clickable { onSelect(null) }) }
        items(folders, key = { it.id }) { f ->
            RailItem(f.symbol, f.name, f.id == selected, Modifier.animateItem().lifted(f.id == dragKey).clickable { onSelect(f.id) })
        }
    }
}

@Composable
private fun RailItem(
    symbol: String,
    name: String,
    selected: Boolean,
    modifier: Modifier,
) {
    Column(
        modifier.padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(width = 48.dp, height = 32.dp)
                .background(if (selected) Selected else Color.Transparent, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) { FolderSymbol(symbol, if (selected) Color.White else Dim, 22.dp) }
        BasicText(
            name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = if (selected) Color.White else Dim, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 左端の列の項目を長押しして離したときのメニュー。 */
@Composable
internal fun FolderMenu(
    folder: DrawerFolder,
    onRename: () -> Unit,
    onChangeSymbol: () -> Unit,
    onDismiss: () -> Unit,
) {
    MenuSheet(onDismiss) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FolderSymbol(folder.symbol, Color.White, 28.dp)
            BasicText(folder.name, style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold))
        }
        listOf("名前を変更" to onRename, "記号を変更" to onChangeSymbol).forEach { (text, onClick) ->
            BasicText(
                text,
                style = TextStyle(color = Color.White, fontSize = 15.sp),
                modifier = Modifier.width(260.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }
}

/** 記号を選ぶ画面。今の記号に印を付ける。 */
@Composable
internal fun SymbolPicker(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    MenuSheet(onDismiss) {
        BasicText("記号を変更", style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(16.dp))
        LazyVerticalGrid(GridCells.Fixed(5), Modifier.width(280.dp).padding(horizontal = 8.dp, vertical = 4.dp)) {
            items(DrawerFolders.symbols) { s ->
                Box(
                    Modifier
                        .padding(4.dp)
                        .size(44.dp)
                        .background(if (s == current) Selected else Color(0xFF26262C), RoundedCornerShape(12.dp))
                        .clickable { onPick(s) },
                    contentAlignment = Alignment.Center,
                ) { FolderSymbol(s, if (s == current) Color(0xFFFFD54F) else Color.White, 24.dp) }
            }
        }
    }
}

@Composable
private fun MenuSheet(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
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
        ) { content() }
    }
}
