package dev.furunov.komalauncher

import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.furunov.komalauncher.model.apps.AppKey
import dev.furunov.komalauncher.model.apps.PinnedShortcuts
import dev.furunov.komalauncher.model.folders.FolderEdits
import dev.furunov.komalauncher.model.layout.PanelId
import dev.furunov.komalauncher.model.ports.LayoutState
import dev.furunov.komalauncher.platform.apps.IconRenderer
import dev.furunov.komalauncher.ui.art.PanelPicker
import kotlinx.coroutines.launch

/**
 * ブラウザなど（Vivaldi の PWA など）からの「ホーム画面に追加」の依頼を受ける。依頼はデフォルトのホームアプリにだけ届く。
 * アイコンと名前、コマ割りのプレビューを表示し、選んだフォルダのコマの末尾に入れる。コマを選ばなければ、全アプリ一覧と検索にだけ並ぶ。
 * 「キャンセル」と戻るボタンでは accept を呼ばないので、何も追加されない。
 */
class PinShortcutActivity : ComponentActivity() {
    private val container by lazy { (application as KomaApplication).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcherApps = getSystemService(LauncherApps::class.java)
        // この画面はほかのアプリから開けるので、壊れた Intent で取り出しに失敗しても、ホームと同じプロセスを落とさない
        val request = runCatching { launcherApps.getPinItemRequest(intent) }.getOrNull()
        val info = request?.shortcutInfo
        // 仕事用プロファイルのショートカットは一覧に並べないので、受けない
        if (request == null || request.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT || !request.isValid || info == null || info.userHandle != Process.myUserHandle()) {
            finish()
            return
        }
        val label = (info.shortLabel ?: info.longLabel ?: info.id).toString()
        // 同じページを追加し直すと、ブラウザは別の id で依頼してくる。追加したら古い方の固定を解除し、フォルダの位置を引き継ぐ
        val duplicates = PinnedShortcuts.sameAs(container.appCatalog.apps.value, info.`package`, label, info.id)
        val iconPx = (48 * resources.displayMetrics.density).toInt()
        val icon =
            // 追加した後と同じ見た目にするため、設定のアイコンの形で描く
            launcherApps.getShortcutIconDrawable(info, resources.displayMetrics.densityDpi)?.let { d ->
                IconRenderer.icon(d, iconPx, container.settingsStore.settings.value.appearance.iconStyle).asImageBitmap()
            }
        // ホームは縦向きに固定して画面全体に描くので、プレビューも縦向きの大きさで作る。この画面は回転するので、横向きのときは幅と高さを入れ替える
        val bounds = windowManager.currentWindowMetrics.bounds
        val homeWidth = minOf(bounds.width(), bounds.height()).toFloat()
        val homeHeight = maxOf(bounds.width(), bounds.height()).toFloat()
        val gutter = container.settingsStore.settings.value.appearance.gutterColor

        setContent {
            val layoutState by container.layoutStore.state.collectAsState()
            // 画面を回しても選んだコマを保つ（PanelId は文字列で保存する）
            var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
            val selected = selectedId?.let(::PanelId)
            // 「追加」を素早く 2 回押すと、2 回目の accept が false を返して失敗の表示になるので、1 回目の後は無視する
            var adding by remember { mutableStateOf(false) }
            val loaded = layoutState as? LayoutState.Loaded
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = ::finish),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .padding(24.dp)
                        .background(Color(0xFF1E1E24), RoundedCornerShape(16.dp))
                        .clickable(enabled = false) {}
                        // 横向きでは高さが足りず、下のボタンが画面の外に出るので、中身をスクロールできるようにする
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BasicText("ホーム画面に追加", style = TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(48.dp)) }
                        Spacer(Modifier.width(12.dp))
                        BasicText(label, style = TextStyle(color = Color.White, fontSize = 16.sp))
                    }
                    BasicText("追加するコマ", style = TextStyle(color = Color(0xFFCCCCD4), fontSize = 14.sp))
                    PanelPicker(
                        layout = layoutState.layout,
                        images = loaded?.images.orEmpty(),
                        revision = loaded?.revision ?: 0L,
                        defaultGutter = gutter,
                        screenWidth = homeWidth,
                        screenHeight = homeHeight,
                        selected = selected,
                        onSelect = { selectedId = it.value },
                        modifier = Modifier.heightIn(max = 360.dp).align(Alignment.CenterHorizontally),
                    )
                    BasicText("コマを選ばないと、アプリドロワーにだけ追加します", style = TextStyle(color = Color(0xFF9A9EAB), fontSize = 12.sp))
                    if (duplicates.isNotEmpty()) {
                        BasicText("同じ名前の「$label」は追加済みです。追加すると、追加済みのものと置き換えます", style = TextStyle(color = Color(0xFFFFE082), fontSize = 12.sp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton("キャンセル", ::finish)
                        TextButton("追加") {
                            if (adding) return@TextButton
                            adding = true
                            if (request.accept()) {
                                val key = AppKey.shortcut(info.`package`, info.id)
                                if (duplicates.isNotEmpty() || selected != null) {
                                    container.folderStore.update { f ->
                                        val replaced = FolderEdits.replace(f, duplicates, key)
                                        selected?.let { FolderEdits.add(replaced, it.value, key) } ?: replaced
                                    }
                                    container.flushStores()
                                }
                                // この画面はすぐ閉じるので、解除はアプリ全体のスコープで行う。失敗しても古い方が残るだけなので知らせない
                                if (duplicates.isNotEmpty()) container.scope.launch { duplicates.forEach { container.appCatalog.unpin(it) } }
                            } else {
                                Toast.makeText(this@PinShortcutActivity, "追加できませんでした", Toast.LENGTH_SHORT).show()
                            }
                            finish()
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun TextButton(
    text: String,
    onClick: () -> Unit,
) {
    BasicText(
        text,
        style = TextStyle(color = Color(0xFFE0447A), fontSize = 15.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
