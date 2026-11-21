package dev.furunov.komalauncher

import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.furunov.komalauncher.model.folders.FolderEdits
import dev.furunov.komalauncher.model.layout.PanelId
import dev.furunov.komalauncher.model.ports.LayoutState
import dev.furunov.komalauncher.ui.art.PanelPicker

/**
 * ブラウザなど（Vivaldi の PWA など）からの「ホーム画面に追加」の依頼を受ける。依頼は既定のホームアプリにだけ届く。
 * アイコンと名前、コマ割りの縮小図を表示し、選んだフォルダのコマの末尾に入れる。コマを選ばなければ、全アプリ一覧と検索にだけ並ぶ。
 * 「キャンセル」と戻るボタンでは accept を呼ばないので、何も追加されない。
 */
class PinShortcutActivity : ComponentActivity() {
    private val container by lazy { (application as KomaApplication).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcherApps = getSystemService(LauncherApps::class.java)
        val request = launcherApps.getPinItemRequest(intent)
        val info = request?.shortcutInfo
        if (request == null || request.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT || !request.isValid || info == null) {
            finish()
            return
        }
        val label = (info.shortLabel ?: info.longLabel ?: info.id).toString()
        val iconPx = (48 * resources.displayMetrics.density).toInt()
        val icon =
            launcherApps.getShortcutIconDrawable(info, resources.displayMetrics.densityDpi)?.let { d ->
                Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888).also {
                    d.setBounds(0, 0, iconPx, iconPx)
                    d.draw(Canvas(it))
                }.asImageBitmap()
            }
        // ホームは画面全体に描くので、同じ大きさで縮小図を作る
        val bounds = windowManager.currentWindowMetrics.bounds
        val gutter = container.settingsStore.settings.value.appearance.gutterColor

        setContent {
            val layoutState by container.layoutStore.state.collectAsState()
            var selected by remember { mutableStateOf<PanelId?>(null) }
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
                        screenWidth = bounds.width().toFloat(),
                        screenHeight = bounds.height().toFloat(),
                        selected = selected,
                        onSelect = { selected = it },
                        modifier = Modifier.heightIn(max = 360.dp).align(Alignment.CenterHorizontally),
                    )
                    BasicText("コマを選ばないと、アプリの一覧にだけ追加します", style = TextStyle(color = Color(0xFF9A9EAB), fontSize = 12.sp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton("キャンセル", ::finish)
                        TextButton("追加") {
                            if (request.accept()) {
                                selected?.let { panel ->
                                    container.folderStore.update { FolderEdits.add(it, panel.value, AppKey.shortcut(info.`package`, info.id)) }
                                    container.flushStores()
                                }
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
