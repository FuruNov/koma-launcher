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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import dev.furunov.komalauncher.model.layout.PanelContent
import dev.furunov.komalauncher.model.layout.PanelId
import dev.furunov.komalauncher.model.settings.LauncherAction
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
        BasicText("フォルダから外す", style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold))
    }
}

internal val REMOVE_ZONE_HEIGHT = 64.dp

/** 外した・移した直後に数秒だけ出す「元に戻す」。 */
@Composable
internal fun UndoBar(
    onUndo: () -> Unit,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
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

/** アイコンを長押しして、動かさずに離したときのメニュー。 */
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
            MenuRow("アプリ情報") { onEvent(HomeEvent.AppInfo(menu.app)) }
            menu.fromPanel?.let { panel ->
                MenuRow("フォルダから外す") { onEvent(HomeEvent.Drop(menu.app, panel, DropTarget.Remove)) }
            }
            MenuRow("アンインストール", color = Color(0xFFEF9A9A)) { onEvent(HomeEvent.Uninstall(menu.app)) }
        }
    }
}

@Composable
private fun MenuRow(
    text: String,
    color: Color = Color.White,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClick: () -> Unit,
) {
    BasicText(
        text,
        style = TextStyle(color = color, fontSize = 15.sp),
        modifier = modifier.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
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
    extra: List<PanelMenuItem>,
    onEvent: (HomeEvent) -> Unit,
) {
    BackHandler { onEvent(HomeEvent.Dismiss) }
    Sheet(onDismiss = { onEvent(HomeEvent.Dismiss) }) {
        BasicText(
            name ?: "コマ",
            style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(16.dp),
        )
        if (panel.panel.content is PanelContent.Folder) {
            MenuRow("名前を変更") { onEvent(HomeEvent.OpenOverlay(Overlay.Rename(panel.panel.id))) }
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

@Composable
internal fun RenameSheet(
    panelId: PanelId,
    current: String?,
    onEvent: (HomeEvent) -> Unit,
) {
    BackHandler { onEvent(HomeEvent.Dismiss) }
    var text by remember { mutableStateOf(current.orEmpty()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Sheet(onDismiss = { onEvent(HomeEvent.Dismiss) }, modifier = Modifier.imePadding()) {
        BasicText("フォルダの名前", style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(16.dp))
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
            MenuRow("取り消す", modifier = Modifier.width(110.dp)) { onEvent(HomeEvent.Dismiss) }
            MenuRow("保存", color = Color(0xFFFFD54F), modifier = Modifier.width(80.dp)) { onEvent(HomeEvent.Rename(panelId, text)) }
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
