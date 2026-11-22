package dev.furunov.komalauncher.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.furunov.komalauncher.model.apps.AppKey
import dev.furunov.komalauncher.model.folders.FolderEdits
import dev.furunov.komalauncher.model.layout.PanelContent
import dev.furunov.komalauncher.model.settings.LauncherAction
import dev.furunov.komalauncher.ui.controls.ToggleSwitch
import dev.furunov.komalauncher.ui.icons.AppIcon
import kotlinx.coroutines.delay

/** フォルダから運んでいる間だけ、画面上端に出す「外す」の場所。 */
@Composable
internal fun RemoveZone(
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(if (active) Color(0xE6C62828) else Color(0xB3000000))
            .statusBarsPadding()
            .height(REMOVE_ZONE_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        BasicText("フォルダから削除", style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold))
    }
}

internal val REMOVE_ZONE_HEIGHT = 64.dp

/** 外した・移した直後に数秒だけ出す「元に戻す」。 */
@Composable
internal fun UndoBar(
    /** 戻す先の状態。変わるたびに 5 秒を数え直す（続けて外したとき、2 回目の猶予が短くならないように）。 */
    undoKey: Any,
    onUndo: () -> Unit,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(undoKey) {
        delay(5000)
        onTimeout()
    }
    Row(
        modifier
            .navigationBarsPadding()
            .padding(16.dp)
            .background(Color(0xF0303036), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("フォルダを変更しました", style = TextStyle(color = Color.White, fontSize = 14.sp))
        Spacer(Modifier.width(24.dp))
        BasicText(
            "元に戻す",
            style = TextStyle(color = Color(0xFFFFD54F), fontSize = 14.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.clickable(onClick = onUndo),
        )
    }
}

/** アイコンを長押ししたときのメニュー。長押しが成立したらすぐ表示し、指を動かしたら消す（DragDropState）。 */
@Composable
internal fun AppMenuSheet(
    menu: Overlay.AppMenu,
    label: String,
    onEvent: (HomeEvent) -> Unit,
) {
    BackHandler { onEvent(HomeEvent.Dismiss) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onEvent(HomeEvent.Dismiss) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(280.dp)
                .background(Color(0xFF1E1E24), RoundedCornerShape(16.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(vertical = 8.dp),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(menu.app, 40.dp)
                Spacer(Modifier.width(12.dp))
                BasicText(label, style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold))
            }
            menu.shortcuts.forEach { sc -> MenuRow(sc.label) { onEvent(HomeEvent.LaunchShortcut(sc)) } }
            if (menu.shortcuts.isNotEmpty()) Divider()
            // 追加したショートカット（PWA など）には、アプリ情報とアンインストールが無い。代わりに固定を解除する「削除」を出す
            if (!menu.app.isShortcut) MenuRow("アプリ情報") { onEvent(HomeEvent.AppInfo(menu.app)) }
            if (menu.fromDrawer) MenuRow("フォルダを変更") { onEvent(HomeEvent.ShowDrawerFolders(menu.app)) }
            if (menu.fromDrawer) MenuRow("非表示") { onEvent(HomeEvent.Hide(menu.app)) }
            menu.fromPanel?.let { panel ->
                MenuRow("フォルダから削除") { onEvent(HomeEvent.Drop(menu.app, panel, DropTarget.Remove)) }
            }
            if (menu.app.isShortcut) {
                MenuRow("削除", color = Color(0xFFEF9A9A)) { onEvent(HomeEvent.RemoveShortcut(menu.app)) }
            } else {
                MenuRow("アンインストール", color = Color(0xFFEF9A9A)) { onEvent(HomeEvent.Uninstall(menu.app)) }
            }
        }
    }
}

/** オンとオフを切り替えるメニューの行。右端にスイッチを置く。 */
@Composable
private fun ToggleMenuRow(
    text: String,
    on: Boolean,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(text, style = TextStyle(color = Color.White, fontSize = 15.sp), modifier = Modifier.weight(1f))
        ToggleSwitch(on)
    }
}

@Composable
private fun MenuRow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    onClick: () -> Unit,
) {
    // 幅を指定されなければ、行全体を押せるように横いっぱいに広げる
    val base = if (modifier == Modifier) Modifier.fillMaxWidth() else modifier
    BasicText(
        text,
        style = TextStyle(color = color, fontSize = 15.sp),
        modifier = base.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(Color(0x33FFFFFF)))
}

/** コマの長押しのメニュー。 */
@Composable
internal fun PanelMenuSheet(
    panel: PanelGeometry,
    name: String?,
    directLaunch: Boolean,
    musicPlayer: Boolean,
    showingPlayer: Boolean,
    extra: List<PanelMenuItem>,
    onEvent: (HomeEvent) -> Unit,
    /** 紐付けた全アプリ一覧のフォルダの名前。紐付けていなければ null。 */
    linkedName: String? = null,
) {
    BackHandler { onEvent(HomeEvent.Dismiss) }
    Sheet(onDismiss = { onEvent(HomeEvent.Dismiss) }) {
        BasicText(
            name ?: "コマ",
            style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(16.dp),
        )
        if (panel.panel.content is PanelContent.Folder) {
            val id = panel.panel.id
            // 直接起動のフォルダと再生中のコマは押しても開かないので、中身を並べ替える入口をここに置く
            if (directLaunch || showingPlayer) MenuRow("フォルダを開く") { onEvent(HomeEvent.OpenOverlay(Overlay.Folder(id))) }
            if (showingPlayer) MenuRow("再生中のアプリを開く") { onEvent(HomeEvent.OpenPlayerApp) }
            MenuRow("アプリを追加") { onEvent(HomeEvent.OpenOverlay(Overlay.AddApps(id))) }
            // 切り替えてもメニューを閉じない（スイッチで切り替わったことが見えるように）
            ToggleMenuRow("直接起動", directLaunch) { onEvent(HomeEvent.EditFolders { FolderEdits.setDirectLaunch(it, id.value, !directLaunch) }) }
            ToggleMenuRow("音楽プレイヤー", musicPlayer) { onEvent(HomeEvent.SetMusicPlayer(id, !musicPlayer)) }
            MenuRow("名前を変更") { onEvent(HomeEvent.OpenOverlay(Overlay.Rename(id))) }
            MenuRow(if (linkedName == null) "ドロワーと同期" else "ドロワーと同期（$linkedName）") { onEvent(HomeEvent.ShowLinkPanel(id)) }
        }
        extra.forEach { item ->
            MenuRow(item.label) {
                onEvent(HomeEvent.Dismiss)
                item.onClick()
            }
        }
        Divider()
        MenuRow("ランチャーの設定") { onEvent(HomeEvent.Perform(LauncherAction.OpenSettings)) }
    }
}

/** 追加したショートカットの削除の確認。固定の解除はランチャーから戻せず、ブラウザで追加し直すしかない。 */
@Composable
internal fun ConfirmRemoveSheet(
    app: AppKey,
    label: String,
    failed: Boolean,
    onEvent: (HomeEvent) -> Unit,
) {
    BackHandler { onEvent(HomeEvent.Dismiss) }
    Sheet(onDismiss = { onEvent(HomeEvent.Dismiss) }) {
        BasicText(
            "${label}を削除しますか。",
            style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(16.dp),
        )
        if (failed) {
            BasicText(
                "削除できませんでした。もう一度お試しください。",
                style = TextStyle(color = Color(0xFFEF9A9A), fontSize = 14.sp),
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
            )
        }
        MenuRow("キャンセル") { onEvent(HomeEvent.Dismiss) }
        MenuRow("削除", color = Color(0xFFEF9A9A)) { onEvent(HomeEvent.ConfirmRemoveShortcut(app)) }
    }
}

/** 音楽プレイヤーに必要な「通知へのアクセス」の説明。 */
@Composable
internal fun MusicAccessSheet(onEvent: (HomeEvent) -> Unit) {
    BackHandler { onEvent(HomeEvent.Dismiss) }
    Sheet(onDismiss = { onEvent(HomeEvent.Dismiss) }) {
        BasicText(
            "通知へのアクセスが必要です",
            style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(16.dp),
        )
        BasicText(
            "再生中の曲を表示するには、通知へのアクセスを許可してください。" +
                "許可できないときは、アプリ情報の右上のメニューから「制限付き設定を許可」を選んでから、もう一度お試しください。",
            style = TextStyle(color = Color(0xFFCCCCD4), fontSize = 14.sp),
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
        )
        MenuRow("通知へのアクセスを開く") { onEvent(HomeEvent.OpenMusicAccess) }
        MenuRow("アプリ情報を開く") { onEvent(HomeEvent.OpenAppInfoForAccess) }
        Divider()
        MenuRow("キャンセル") { onEvent(HomeEvent.Dismiss) }
    }
}

/** 全アプリ一覧の「フォルダを変更」。今のフォルダに印を付け、末尾の「新しいフォルダ」で名前を入れて作る。 */
@Composable
internal fun DrawerFolderMenuSheet(
    menu: Overlay.DrawerFolderMenu,
    onEvent: (HomeEvent) -> Unit,
) {
    BackHandler { onEvent(HomeEvent.Dismiss) }
    Sheet(onDismiss = { onEvent(HomeEvent.Dismiss) }) {
        BasicText("フォルダを変更", style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(16.dp))
        // 既定の 10 個が全て見える高さにする（420dp では末尾の「その他」と今のフォルダの印が隠れた）
        LazyColumn(Modifier.heightIn(max = 600.dp)) {
            items(menu.choices) { (id, name) ->
                val current = id == menu.current
                MenuRow(if (current) "✓ $name" else name, color = if (current) Color(0xFFFFD54F) else Color.White) {
                    onEvent(HomeEvent.MoveToDrawerFolder(menu.app, id))
                }
            }
        }
        Divider()
        MenuRow("新しいフォルダ") { onEvent(HomeEvent.OpenOverlay(Overlay.DrawerFolderName(menu.app, null, ""))) }
    }
}

@Composable
internal fun RenameSheet(
    current: String?,
    onSave: (String) -> Unit,
    onEvent: (HomeEvent) -> Unit,
) {
    BackHandler { onEvent(HomeEvent.Dismiss) }
    var text by remember { mutableStateOf(current.orEmpty()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Sheet(onDismiss = { onEvent(HomeEvent.Dismiss) }, modifier = Modifier.imePadding()) {
        BasicText("フォルダ名", style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(16.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            cursorBrush = SolidColor(Color.White),
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(Color(0xFF2A2A30), RoundedCornerShape(8.dp))
                .padding(12.dp)
                .focusRequester(focus),
        )
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            Spacer(Modifier.weight(1f))
            MenuRow("キャンセル", modifier = Modifier.width(110.dp)) { onEvent(HomeEvent.Dismiss) }
            MenuRow("保存", color = Color(0xFFFFD54F), modifier = Modifier.width(80.dp)) { onSave(text) }
        }
    }
}

/** 中央に出すメニューの土台。外側のタップで閉じる。 */
@Composable
private fun Sheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(300.dp)
                .background(Color(0xFF1E1E24), RoundedCornerShape(16.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(vertical = 8.dp),
            content = content,
        )
    }
}

/** コマの「ドロワーと同期」。紐付ける全アプリ一覧のフォルダを選ぶ。今のフォルダに印を付け、末尾の「同期しない」で外す。 */
@Composable
internal fun LinkPanelSheet(
    o: Overlay.LinkPanel,
    onEvent: (HomeEvent) -> Unit,
) {
    BackHandler { onEvent(HomeEvent.Dismiss) }
    Sheet(onDismiss = { onEvent(HomeEvent.Dismiss) }) {
        BasicText("ドロワーと同期", style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp))
        BasicText(
            "選んだフォルダと同じ中身を表示します。このコマで入れる・外す・並べ替えると、ドロワーのフォルダにも反映します",
            style = TextStyle(color = Color(0xFF9A9EAB), fontSize = 12.sp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(o.choices) { (id, name) ->
                val current = id == o.current
                MenuRow(if (current) "✓ $name" else name, color = if (current) Color(0xFFFFD54F) else Color.White) { onEvent(HomeEvent.LinkPanel(o.panelId, id)) }
            }
        }
        Divider()
        MenuRow(if (o.current == null) "✓ 同期しない" else "同期しない", color = if (o.current == null) Color(0xFFFFD54F) else Color.White) { onEvent(HomeEvent.LinkPanel(o.panelId, null)) }
    }
}

/** 自由に置くウィジェットの長押しのメニュー。移動と大きさの変更は「配置を調整」で行う。 */
@Composable
internal fun WidgetMenuSheet(
    /** 見出しに出すウィジェットの名前。 */
    title: String,
    /** 内蔵のウィジェット（時計・テキスト）なら、「変更」の代わりに「スタイル」を出す。 */
    builtin: Boolean,
    onStyle: () -> Unit,
    onAdjust: () -> Unit,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
    onEvent: (HomeEvent) -> Unit,
) {
    BackHandler { onEvent(HomeEvent.Dismiss) }
    Sheet(onDismiss = { onEvent(HomeEvent.Dismiss) }) {
        BasicText(
            title,
            style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(16.dp),
        )
        if (builtin) MenuRow("スタイル", onClick = onStyle)
        MenuRow("配置を調整", onClick = onAdjust)
        if (!builtin) MenuRow("変更", onClick = onReplace)
        MenuRow("削除", onClick = onRemove)
    }
}
