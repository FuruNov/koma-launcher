package dev.furunov.komalauncher.platform.files

import android.content.Context
import android.net.Uri
import dev.furunov.komalauncher.model.folders.FolderStore
import dev.furunov.komalauncher.model.folders.Folders
import dev.furunov.komalauncher.model.layout.LayoutParser
import dev.furunov.komalauncher.model.layout.LayoutResult
import dev.furunov.komalauncher.model.ports.BackupService
import dev.furunov.komalauncher.model.ports.LayoutImporter
import dev.furunov.komalauncher.model.settings.Settings
import dev.furunov.komalauncher.model.settings.SettingsStore
import dev.furunov.komalauncher.model.widgets.WidgetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 利用者が選んだ画像と layout.json を、layout.json の置き場所へ写す。
 * 写す前に layout.json を読んで確かめ、壊れていれば写さない（見本に戻って割り当ての整理が止まるのを避ける）。
 */
class SafLayoutImporter(
    private val context: Context,
    private val directory: File,
) : LayoutImporter {
    override suspend fun import(
        imageUri: String?,
        layoutUri: String?,
    ): String? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val layoutText = layoutUri?.let { u -> resolver.openInputStream(Uri.parse(u))?.use { it.readBytes().decodeToString() } }
        val layoutFile = File(directory, "layout.json")
        val layout =
            when {
                layoutText != null ->
                    when (val r = LayoutParser.parse(layoutText)) {
                        is LayoutResult.Ok -> r.layout
                        is LayoutResult.Error -> return@withContext r.message
                    }

                layoutFile.exists() -> (LayoutParser.parse(layoutFile.readText()) as? LayoutResult.Ok)?.layout

                else -> null
            }
        if (imageUri != null) {
            val name = layout?.image ?: return@withContext "画像の名前を決める layout.json がありません。先に layout.json を取り込んでください"
            resolver.openInputStream(Uri.parse(imageUri))?.use { input ->
                File(directory, name).outputStream().use { input.copyTo(it) }
            } ?: return@withContext "画像を読めません"
        }
        // layout.json は最後に書く。監視が先に layout.json を読むと、まだ無い画像を探しに行くため
        layoutText?.let { layoutFile.writeText(it) }
        null
    }
}

@Serializable
private data class BackupFile(
    val version: Int = 1,
    val folders: Folders,
    val settings: Settings,
    val layout: String? = null,
    /** コマ → ウィジェットの提供元。appWidgetId は端末ごとに違うので書かない。 */
    val widgets: Map<String, String> = emptyMap(),
)

class SafBackupService(
    private val context: Context,
    private val layoutDirectory: File,
    private val folders: FolderStore,
    private val settings: SettingsStore,
    private val widgets: WidgetStore,
) : BackupService {
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

    override suspend fun export(uri: String): String? = withContext(Dispatchers.IO) {
        val layout = File(layoutDirectory, "layout.json").takeIf { it.exists() }?.readText()
        val data =
            BackupFile(
                folders = folders.folders.value,
                settings = settings.settings.value,
                layout = layout,
                widgets = widgets.assignments.value.byPanel.mapValues { it.value.provider },
            )
        context.contentResolver.openOutputStream(Uri.parse(uri), "wt")?.use {
            it.write(json.encodeToString(BackupFile.serializer(), data).encodeToByteArray())
        } ?: return@withContext "書き出し先を開けません"
        null
    }

    override suspend fun import(uri: String): String? = withContext(Dispatchers.IO) {
        val text =
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes().decodeToString() }
                ?: return@withContext "ファイルを読めません"
        val data =
            try {
                json.decodeFromString(BackupFile.serializer(), text)
            } catch (e: Exception) {
                return@withContext "バックアップの形式が違います: ${e.message?.lineSequence()?.firstOrNull()}"
            }
        data.layout?.let { l ->
            if (LayoutParser.parse(l) is LayoutResult.Error) return@withContext "バックアップの中の layout.json が壊れています"
            File(layoutDirectory, "layout.json").writeText(l)
        }
        folders.update { data.folders }
        settings.update { data.settings }
        if (data.widgets.isEmpty()) {
            null
        } else {
            // ウィジェットの配置し直しには許可の画面が要るので、ここでは行わず、どのコマに何があったかを知らせる
            "ウィジェットはコマを長押しして置き直してください（${data.widgets.size} 個）"
        }
    }
}
