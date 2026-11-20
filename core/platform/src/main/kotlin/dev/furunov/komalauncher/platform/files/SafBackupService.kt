package dev.furunov.komalauncher.platform.files

import android.content.Context
import android.net.Uri
import dev.furunov.komalauncher.model.apps.LaunchStore
import dev.furunov.komalauncher.model.apps.Launches
import dev.furunov.komalauncher.model.folders.FolderStore
import dev.furunov.komalauncher.model.folders.Folders
import dev.furunov.komalauncher.model.layout.LayoutParser
import dev.furunov.komalauncher.model.layout.LayoutResult
import dev.furunov.komalauncher.model.ports.BackupService
import dev.furunov.komalauncher.model.settings.Settings
import dev.furunov.komalauncher.model.settings.SettingsStore
import dev.furunov.komalauncher.model.widgets.WidgetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class BackupFile(
    val version: Int = 1,
    val folders: Folders,
    val settings: Settings,
    val layout: String? = null,
    /** コマ → ウィジェットの提供元。appWidgetId は端末ごとに違うので書かない。 */
    val widgets: Map<String, String> = emptyMap(),
    /** アプリごとの起動回数（全アプリ一覧の「よく使う」）。古いバックアップには無い。 */
    val launches: Launches? = null,
)

class SafBackupService(
    private val context: Context,
    /** 読み込んだレイアウトを置く取り込み口。置けば FileLayoutStore が取り込む。 */
    private val importDirectory: File,
    /** いまのレイアウトの layout.json の文（見本なら null）。 */
    private val currentLayout: () -> String?,
    private val folders: FolderStore,
    private val settings: SettingsStore,
    private val widgets: WidgetStore,
    private val launches: LaunchStore,
) : BackupService {
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

    /**
     * バックアップのファイルの中身。同じ状態からは同じ文ができるので、自動バックアップが変化の有無を比べるのに使う。
     * [withLaunches] が false なら起動回数を除く。起動のたびに変わるので、自動バックアップはそれ以外の変化と分けて扱う。
     */
    fun encode(withLaunches: Boolean = true): String {
        val data =
            BackupFile(
                folders = folders.folders.value,
                settings = settings.settings.value,
                layout = currentLayout(),
                widgets = widgets.assignments.value.byPanel.mapValues { it.value.provider },
                launches = launches.launches.value.takeIf { withLaunches },
            )
        return json.encodeToString(BackupFile.serializer(), data)
    }

    override suspend fun export(uri: String): String? = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(Uri.parse(uri), "wt")?.use {
            it.write(encode().encodeToByteArray())
        } ?: return@withContext "書き出し先を開けません"
        null
    }

    override suspend fun import(uri: String): String? = withContext(Dispatchers.IO) {
        val text =
            context.contentResolver.openForRead(Uri.parse(uri))?.use { it.readBytes().decodeToString() }
                ?: return@withContext "ファイルを読み込めません"
        val data =
            try {
                json.decodeFromString(BackupFile.serializer(), text)
            } catch (e: Exception) {
                return@withContext "バックアップの形式が正しくありません: ${e.message?.lineSequence()?.firstOrNull()}"
            }
        data.layout?.let { l ->
            if (LayoutParser.parse(l) is LayoutResult.Error) return@withContext "バックアップ内の layout.json が破損しています"
            File(importDirectory, "layout.json").writeText(l)
        }
        folders.update { data.folders }
        settings.update { data.settings }
        data.launches?.let { l -> launches.update { l } }
        if (data.widgets.isEmpty()) {
            null
        } else {
            // ウィジェットの配置し直しには許可の画面が要るので、ここでは行わず、どのコマに何があったかを知らせる
            "ウィジェットはコマを長押しして再配置してください（${data.widgets.size} 個）"
        }
    }
}
