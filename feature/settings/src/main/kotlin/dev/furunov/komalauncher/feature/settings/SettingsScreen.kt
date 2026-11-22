package dev.furunov.komalauncher.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.furunov.komalauncher.model.apps.AppEntry
import dev.furunov.komalauncher.model.ports.AutoBackup
import dev.furunov.komalauncher.model.ports.AutoBackupState
import dev.furunov.komalauncher.model.ports.RestoreService
import dev.furunov.komalauncher.model.settings.SettingsStore
import dev.furunov.komalauncher.ui.controls.ToggleSwitch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 設定画面が外から受け取るもの。実装は :app が渡す。 */
class SettingsDeps(
    val store: SettingsStore,
    /** フォルダのコマ（id と名前）。ジェスチャーで開くフォルダの選択肢に使う。 */
    val folderPanels: () -> List<Pair<String, String>>,
    val apps: StateFlow<List<AppEntry>>,
    val restore: RestoreService,
    val autoBackup: AutoBackup,
    val openHomeSettings: () -> Unit,
    /** 音楽プレイヤーに要る「通知へのアクセス」が許可されているか。 */
    val hasMusicAccess: () -> Boolean,
    /** 実際の音の取得の状態。「音に合わせて動かす」の下に表示する。まだ一度も開始していなければ null。 */
    val audioStatus: StateFlow<String?>,
    val openMusicAccess: () -> Unit,
    val openEditor: () -> Unit,
    val onClose: () -> Unit,
    /** 登録してあるのに入っていないアプリ（パッケージ名と名前）。機種変更の後に入れ直すために並べる。 */
    val missingApps: () -> List<Pair<String, String>> = { emptyList() },
    /** Play ストアでアプリ（パッケージ名）のページを開く。 */
    val openStorePage: (String) -> Unit = {},
)

private val Bg = Color(0xFF121216)
private val Card = Color(0xFF1E1E24)
private val Sub = Color(0xFF9A9AA2)
private val Accent = Color(0xFFFFD54F)

@Composable
fun SettingsScreen(deps: SettingsDeps) {
    val settings by deps.store.settings.collectAsStateWithLifecycle()
    val apps by deps.apps.collectAsStateWithLifecycle()
    val autoBackup by deps.autoBackup.state.collectAsStateWithLifecycle()
    val audioStatus by deps.audioStatus.collectAsStateWithLifecycle()
    var autoBackupMenu by remember { mutableStateOf(false) }
    var hiddenMenu by remember { mutableStateOf(false) }
    var missingMenu by remember { mutableStateOf(false) }
    // 既存のバックアップがあった保存先のフォルダ（tree の URI）。復元か上書きかを尋ねる
    var existingBackup by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf<SettingRow?>(null) }
    val sections = remember { settingsSections() }
    val context = LocalContext.current

    // 通知へのアクセスは Android の設定画面で変わるので、この画面に戻るたびに読み直す
    var musicAccess by remember { mutableStateOf(deps.hasMusicAccess()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { musicAccess = deps.hasMusicAccess() }
    }

    // 権限を求めている行。許可されたらオンにする
    var askingFor by remember { mutableStateOf<SettingRow.PermissionToggle?>(null) }
    val requestPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val row = askingFor
            if (granted && row != null) deps.store.update { row.set(it, true) }
            // 「今後表示しない」で断られていると、ダイアログが出ずにすぐ断られる。何も起きないように見えないよう、理由を表示する
            if (!granted) message = "マイクの権限が無いため、オンにできません。Android の設定のアプリ情報の「権限」から許可できます"
            askingFor = null
        }

    fun togglePermission(row: SettingRow.PermissionToggle) {
        when {
            row.get(deps.store.settings.value) -> deps.store.update { row.set(it, false) }

            context.checkSelfPermission(row.permission) == PackageManager.PERMISSION_GRANTED -> deps.store.update { row.set(it, true) }

            else -> {
                askingFor = row
                requestPermission.launch(row.permission)
            }
        }
    }

    val pickRestoreFolder =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) scope.launch { message = deps.restore.restoreFolder(uri.toString()) }
        }
    val pickBackupFolder =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                scope.launch {
                    val tree = uri.toString()
                    val existing = deps.autoBackup.existingBackup(tree)
                    if (existing == null) message = deps.autoBackup.setFolder(tree) ?: "バックアップしました" else existingBackup = tree
                }
            }
        }

    fun onAction(key: ActionKey) {
        when (key) {
            ActionKey.EditLayout -> deps.openEditor()
            ActionKey.MusicAccess -> deps.openMusicAccess()
            ActionKey.RestoreFolder -> pickRestoreFolder.launch(null)
            ActionKey.AutoBackup -> if (autoBackup.folder == null) pickBackupFolder.launch(null) else autoBackupMenu = true
            ActionKey.HomeApp -> deps.openHomeSettings()
            ActionKey.HiddenApps -> if (settings.drawer.hidden.isEmpty()) message = "アプリドロワーでアプリを長押しし、「非表示」を選ぶと、ここに表示されます" else hiddenMenu = true
            ActionKey.MissingApps -> missingMenu = true
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                BasicText(
                    "Koma Launcher の設定",
                    style = TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(20.dp),
                )
            }
            sections.forEach { section ->
                item(key = section.title) {
                    BasicText(
                        section.title,
                        style = TextStyle(color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
                    )
                }
                items(section.rows, key = { section.title + it.title }) { row ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable {
                                when (row) {
                                    is SettingRow.Toggle -> deps.store.update { row.set(it, !row.get(it)) }
                                    is SettingRow.PermissionToggle -> togglePermission(row)
                                    is SettingRow.Action -> onAction(row.key)
                                    is SettingRow.Choice, is SettingRow.Gesture -> choosing = row
                                    is SettingRow.Stepper -> Unit
                                }
                            }.padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            BasicText(row.title, style = TextStyle(color = Color.White, fontSize = 16.sp))
                            val sub =
                                when (row) {
                                    is SettingRow.Choice -> row.options.firstOrNull { it.id == row.get(settings) }?.label ?: row.get(settings)

                                    is SettingRow.Gesture ->
                                        actionLabel(
                                            settings.actionFor(row.gesture),
                                            folderLabel = { id -> deps.folderPanels().firstOrNull { it.first == id }?.second ?: id },
                                            appLabel = { a -> apps.firstOrNull { it.key == a.app }?.label ?: a.app.packageName },
                                        )

                                    is SettingRow.Action ->
                                        if (row.key == ActionKey.MusicAccess) {
                                            if (musicAccess) "許可済み" else "未許可"
                                        } else if (row.key == ActionKey.AutoBackup) {
                                            autoBackupSummary(autoBackup)
                                        } else if (row.key == ActionKey.HiddenApps) {
                                            if (settings.drawer.hidden.isEmpty()) "なし" else "${settings.drawer.hidden.size} 個・選ぶと再表示"
                                        } else if (row.key == ActionKey.MissingApps) {
                                            deps.missingApps().size.let { if (it == 0) "なし" else "$it 個・機種変更の後に入れ直す" }
                                        } else {
                                            row.subtitle
                                        }

                                    // 端末によっては取得できないので、オンのときは取得の状態も表示する（Release の APK では adb のログを見られないため）
                                    is SettingRow.PermissionToggle ->
                                        if (row.permission == Manifest.permission.RECORD_AUDIO && row.get(settings) && audioStatus != null) "${row.subtitle}\n状態: $audioStatus" else row.subtitle

                                    else -> null
                                }
                            if (sub != null) BasicText(sub, style = TextStyle(color = Sub, fontSize = 13.sp))
                        }
                        when (row) {
                            is SettingRow.Toggle -> ToggleSwitch(row.get(settings))

                            is SettingRow.PermissionToggle -> ToggleSwitch(row.get(settings))

                            is SettingRow.Stepper ->
                                Stepper(row.get(settings), row.unit) { delta ->
                                    deps.store.update { s -> row.set(s, (row.get(s) + delta * row.step).coerceIn(row.range)) }
                                }

                            else -> Unit
                        }
                    }
                }
            }
        }

        choosing?.let { row ->
            val options: List<Pair<String, () -> Unit>> =
                when (row) {
                    is SettingRow.Choice -> row.options.map { o -> o.label to { deps.store.update { row.set(it, o.id) } } }

                    is SettingRow.Gesture ->
                        actionOptions(deps.folderPanels(), apps).map { (action, label) ->
                            label to { deps.store.update { it.copy(gestures = it.gestures + (row.gesture to action)) } }
                        }

                    else -> emptyList()
                }
            ChoiceDialog(row.title, options) { choosing = null }
        }

        if (autoBackupMenu) {
            ChoiceDialog(
                "自動バックアップ",
                listOf(
                    "今すぐバックアップ" to { scope.launch { message = deps.autoBackup.backupNow() ?: "バックアップしました" } },
                    "保存先を変更" to { pickBackupFolder.launch(null) },
                    "オフにする" to { scope.launch { deps.autoBackup.setFolder(null) } },
                ),
            ) { autoBackupMenu = false }
        }

        if (missingMenu) {
            MissingAppsScreen(
                missing = deps.missingApps(),
                columns = settings.drawer.columns,
                onOpen = deps.openStorePage,
                onClose = { missingMenu = false },
            )
        }

        if (hiddenMenu) {
            HiddenAppsScreen(
                hidden = settings.drawer.hidden,
                apps = apps,
                columns = settings.drawer.columns,
                onShow = { key -> deps.store.update { it.copy(drawer = it.drawer.copy(hidden = it.drawer.hidden - key)) } },
                onClose = { hiddenMenu = false },
            )
        }

        existingBackup?.let { tree ->
            ChoiceDialog(
                "このフォルダには既存のバックアップがあります",
                listOf(
                    "このバックアップから復元" to {
                        scope.launch {
                            val restored = deps.restore.restoreFolder(tree)
                            // 復元したコマ割りは少し後に反映されるので、すぐには書かず、次にホームを離れたときに書く
                            message = deps.autoBackup.setFolder(tree, writeNow = false) ?: restored
                        }
                    },
                    "上書き" to { scope.launch { message = deps.autoBackup.setFolder(tree) ?: "バックアップしました。以前の内容は日付付きのファイルに保存しました" } },
                    "キャンセル" to {},
                ),
            ) { existingBackup = null }
        }

        message?.let {
            Box(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp)) {
                BasicText(
                    it,
                    style = TextStyle(color = Color.White, fontSize = 14.sp),
                    modifier =
                    Modifier
                        .background(Color(0xF0303036), RoundedCornerShape(12.dp))
                        .clickable { message = null }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
    BackHandler(enabled = choosing == null && !autoBackupMenu && !hiddenMenu && !missingMenu && existingBackup == null) { deps.onClose() }
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .background(Card, RoundedCornerShape(16.dp))
                .clickable(enabled = false) {}
                .padding(vertical = 8.dp),
        ) {
            BasicText(title, style = TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(16.dp))
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(options) { (label, pick) ->
                    BasicText(
                        label,
                        style = TextStyle(color = Color.White, fontSize = 15.sp),
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                pick()
                                onDismiss()
                            }.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Stepper(
    value: Int,
    unit: String,
    onStep: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StepButton("−") { onStep(-1) }
        BasicText("$value$unit", style = TextStyle(color = Color.White, fontSize = 15.sp), modifier = Modifier.width(64.dp).padding(horizontal = 4.dp))
        StepButton("＋") { onStep(1) }
    }
}

@Composable
private fun StepButton(
    text: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(36.dp).background(Card, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { BasicText(text, style = TextStyle(color = Color.White, fontSize = 18.sp)) }
}

private val backupTime = DateTimeFormatter.ofPattern("M/d HH:mm")

private fun autoBackupSummary(s: AutoBackupState): String {
    val at = s.lastBackupAt
    return when {
        s.folder == null -> "オフ（pCloud などのフォルダに保存）"
        s.error != null -> "エラー: ${s.error}"
        at != null -> "保存先: ${s.folder}・最終: ${backupTime.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))}"
        else -> "保存先: ${s.folder}"
    }
}
