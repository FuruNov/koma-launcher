package dev.furunov.komalauncher.feature.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.furunov.komalauncher.model.apps.DrawerFolder
import dev.furunov.komalauncher.model.apps.DrawerFolders

private val Selected = Color(0xFF44444E)
private val Dim = Color(0xFFAAAAAA)

/** 項目 1 つの高さと、左の列の幅。 */
private val ItemHeight = 56.dp
internal val RailWidth = 68.dp

/**
 * 「すべて」とフォルダを L 字に並べ、その内側に [content]（アプリの一覧）を置く。
 * 左の列に入る分だけ並べ、入りきらない分は下端の行に、左の角から横に並べる。項目の大きさは変えない。
 *
 * 全てを左の列に詰めると、18 個で項目が 34dp・文字が 9sp まで小さくなり、見づらいと利用者から指摘があった（2026-11-22）。
 * 下端の行の分、アプリの一覧は 1 行ぶん低くなる。
 *
 * 高さはキーボードの分を含まない場所（呼び出し側が ime を除いて配置する）で測るので、キーボードが出ても並びは変わらない。
 * 長押しの並べ替えとメニューは、呼び出し側が [slots]（各位置の画面上の範囲）を使って受ける。
 */
@Composable
internal fun FolderStrip(
    folders: List<DrawerFolder>,
    selected: String?,
    dragKey: Any?,
    /** 運んでいるアプリを離すと入るフォルダの id。枠で強調する。 */
    dropTarget: String?,
    onSelect: (String?) -> Unit,
    /**
     * 各位置の画面上の範囲。キーは位置の番号（0 が「すべて」、1 からがフォルダの並び）。
     * フォルダごとでなく位置ごとに記録する。並べ替えの途中でフォルダが動いても位置は変わらないので、記録が配置の更新を待たずに済む。
     * フォルダごとに記録すると、動かした直後は古い範囲と新しい並びが食い違い、1 つ動かすと行き過ぎたり戻ったりした（2026-11-22）。
     */
    slots: MutableMap<Int, Rect>,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val entries: List<DrawerFolder?> = listOf(null) + folders
    val height = with(density) { size.height.toDp() }
    val fitAll = ((height - 8.dp) / ItemHeight).toInt()
    val railCount =
        if (size == IntSize.Zero || entries.size <= fitAll) entries.size else ((height - ItemHeight - 8.dp) / ItemHeight).toInt().coerceAtLeast(1)
    slots.keys.retainAll(entries.indices.toSet())

    @Composable
    fun entry(
        position: Int,
        f: DrawerFolder?,
        modifier: Modifier,
    ) {
        RailItem(
            symbol = f?.symbol ?: ALL_SYMBOL,
            name = f?.name ?: "すべて",
            selected = f?.id == selected,
            target = f != null && f.id == dropTarget,
            modifier =
            modifier
                // 拡大（lifted）する前の範囲を記録する。拡大した範囲は隣の位置に重なるため
                .onGloballyPositioned { slots[position] = it.boundsInRoot() }
                .lifted(f != null && (f.id == dragKey || f.id == dropTarget))
                .clickable { onSelect(f?.id) },
        )
    }

    Column(modifier.fillMaxSize().onSizeChanged { size = it }) {
        Row(Modifier.weight(1f)) {
            Column(Modifier.width(RailWidth).padding(top = 4.dp)) {
                entries.take(railCount).forEachIndexed { i, f -> entry(i, f, Modifier.fillMaxWidth()) }
            }
            content(Modifier.weight(1f))
        }
        val rest = entries.drop(railCount)
        if (rest.isNotEmpty()) {
            // 角の項目は左の列と同じ幅にして、記号を左の列とそろえる。残りは、少ないときは同じ幅で左から並べ、多いときは残りの横幅を等分する
            val share = rest.size * RailWidth.value > with(density) { size.width.toDp() }.value
            Row(Modifier.fillMaxWidth().height(ItemHeight)) {
                rest.forEachIndexed { i, f -> entry(railCount + i, f, if (i == 0 || !share) Modifier.width(RailWidth) else Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RailItem(
    symbol: String,
    name: String,
    selected: Boolean,
    modifier: Modifier,
    /** true なら、運んでいるアプリの入る先として枠を付ける。 */
    target: Boolean = false,
) {
    Column(
        modifier.height(ItemHeight).padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(width = 48.dp, height = 32.dp)
                .background(if (selected) Selected else Color.Transparent, RoundedCornerShape(16.dp))
                .then(if (target) Modifier.border(2.dp, Color(0xFFFFD54F), RoundedCornerShape(16.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) { FolderSymbol(symbol, if (selected) Color.White else Dim, 22.dp) }
        // 5 文字までは切れない幅（既定のフォルダ名は 5 文字以内にしてある）
        BasicText(
            name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = if (selected) Color.White else Dim, fontSize = 10.5.sp, lineHeight = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center),
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
