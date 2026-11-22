package dev.furunov.komalauncher.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.furunov.komalauncher.model.apps.AppEntry
import dev.furunov.komalauncher.model.folders.FolderEdits
import dev.furunov.komalauncher.model.folders.FolderStore
import dev.furunov.komalauncher.model.folders.Folders
import dev.furunov.komalauncher.model.layout.Panel
import kotlinx.coroutines.flow.StateFlow

/**
 * 編集画面からフォルダの中身と設定を変えるための依存。ホームの長押しのメニューと同じ [FolderStore] を変える。
 * フォルダの変更はレイアウトの「元に戻す」の対象にしない（保存先が別なので）。
 */
class EditorFolders(
    val store: FolderStore,
    val apps: StateFlow<List<AppEntry>>,
    /** 音楽プレイヤーに要る「通知へのアクセス」が許可されているか。 */
    val hasMusicAccess: () -> Boolean,
    val openMusicAccess: () -> Unit,
)

/** 選択中のフォルダのコマの設定（アプリ・直接起動・音楽プレイヤー）。名前は NameFields で変える。 */
@Composable
internal fun FolderControls(
    deps: EditorFolders,
    folders: Folders,
    panel: Panel,
    onAddApps: () -> Unit,
) {
    val id = panel.id.value
    val folder = folders.byPanel[id]
    // 通知へのアクセスは Android の設定画面で変わるので、この画面に戻るたびに読み直す
    var musicAccess by remember { mutableStateOf(deps.hasMusicAccess()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { musicAccess = deps.hasMusicAccess() }
    }

    ChipRow("アプリ") {
        Chip("アプリを追加（${folder?.apps?.size ?: 0} 個）", onClick = onAddApps)
    }
    // オンなら、押したときにフォルダを開かず先頭のアプリを起動する
    ToggleRow("直接起動", folder?.directLaunch == true) { v -> deps.store.update { FolderEdits.setDirectLaunch(it, id, v) } }
    val music = folder?.musicPlayer == true
    ToggleRow("音楽プレイヤー", music) { v -> deps.store.update { FolderEdits.setMusicPlayer(it, id, v) } }
    if (music && !musicAccess) {
        Hint("再生中の曲を表示するには、通知へのアクセスを許可してください")
        ChipRow("") { Chip("通知へのアクセスを開く", onClick = deps.openMusicAccess) }
    }
}
