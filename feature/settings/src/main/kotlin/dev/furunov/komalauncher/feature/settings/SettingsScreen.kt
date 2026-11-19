package dev.furunov.komalauncher.feature.settings

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.furunov.komalauncher.model.apps.AppEntry
import dev.furunov.komalauncher.model.ports.BackupService
import dev.furunov.komalauncher.model.ports.LayoutImporter
import dev.furunov.komalauncher.model.settings.SettingsStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 設定画面が外から受け取るもの。実装は :app が渡す。 */
class SettingsDeps(
    val store: SettingsStore,
    /** フォルダのコマ（id と名前）。ジェスチャーで開くフォルダの選択肢に使う。 */
    val folderPanels: () -> List<Pair<String, String>>,
    val apps: StateFlow<List<AppEntry>>,
    val importer: LayoutImporter,
    val backup: BackupService,
    val openHomeSettings: () -> Unit,
    val onClose: () -> Unit,
)

private val Bg = Color(0xFF121216)
private val Card = Color(0xFF1E1E24)
private val Sub = Color(0xFF9A9AA2)
private val Accent = Color(0xFFFFD54F)

@Composable
fun SettingsScreen(deps: SettingsDeps) {
    val settings by deps.store.settings.collectAsStateWithLifecycle()
    val apps by deps.apps.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf<SettingRow?>(null) }
    val sections = remember { settingsSections() }

    // 絵の取り込みは、layout.json → 画像の順に選んでもらう
    var pendingLayout by remember { mutableStateOf<String?>(null) }
    val pickImage =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            scope.launch {
                message = deps.importer.import(uri?.toString(), pendingLayout) ?: "コマの絵を取り込みました"
                pendingLayout = null
            }
        }
    val pickLayout =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                pendingLayout = uri.toString()
                pickImage.launch(arrayOf("image/*"))
            }
        }
    val exportTo =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) scope.launch { message = deps.backup.export(uri.toString()) ?: "書き出しました" }
        }
    val importFrom =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch { message = deps.backup.import(uri.toString()) ?: "読み込みました" }
        }

    fun onAction(key: ActionKey) {
        when (key) {
            ActionKey.ImportArt -> pickLayout.launch(arrayOf("application/json", "text/plain", "*/*"))
            ActionKey.ExportBackup -> exportTo.launch("koma-launcher-backup.json")
            ActionKey.ImportBackup -> importFrom.launch(arrayOf("application/json", "*/*"))
            ActionKey.HomeApp -> deps.openHomeSettings()
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                BasicText(
                    "コマランチャーの設定",
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

                                    is SettingRow.Action -> row.subtitle

                                    else -> null
                                }
                            if (sub != null) BasicText(sub, style = TextStyle(color = Sub, fontSize = 13.sp))
                        }
                        when (row) {
                            is SettingRow.Toggle -> Switch(row.get(settings))

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
    BackHandler(enabled = choosing == null) { deps.onClose() }
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
private fun Switch(on: Boolean) {
    Box(
        Modifier
            .width(44.dp)
            .background(if (on) Accent else Color(0xFF44444C), RoundedCornerShape(12.dp))
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(18.dp).background(Color.White, CircleShape)) }
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
