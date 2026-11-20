package dev.furunov.komalauncher.platform.files

import android.content.Context
import android.net.Uri
import dev.furunov.komalauncher.model.folders.FolderStore
import dev.furunov.komalauncher.model.folders.Folders
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.LayoutParser
import dev.furunov.komalauncher.model.layout.LayoutResult
import dev.furunov.komalauncher.model.layout.LayoutShape
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
 * 利用者が選んだ画像と layout.json を、取り込み口（外部ファイル領域）へ写す。写した後の取り込みは FileLayoutStore が行う。
 * 写す前に layout.json を読んで確かめ、壊れていれば写さない。
 */
class SafLayoutImporter(
    private val context: Context,
    private val importDirectory: File,
    /** いま表示しているレイアウト。画像だけを取り込むとき、置き換える絵の名前を決めるのに使う。 */
    private val current: () -> Layout?,
) : LayoutImporter {
    override suspend fun import(
        imageUri: String?,
        layoutUri: String?,
    ): String? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val layoutText = layoutUri?.let { u -> resolver.openInputStream(Uri.parse(u))?.use { it.readBytes().decodeToString() } }
        val layout =
            when {
                layoutText != null ->
                    when (val r = LayoutParser.parse(layoutText)) {
                        is LayoutResult.Ok -> r.layout
                        is LayoutResult.Error -> return@withContext r.message
                    }

                else -> current()
            }
        if (imageUri != null) {
            // 1 枚の絵を選ぶ取り込みは version 1 のためのもの。version 2 のコマごとの画像は、編集画面（段階 2）か adb で art/ に置く
            val name =
                (layout?.shape as? LayoutShape.Fixed)?.image
                    ?: return@withContext "画像の名前を決める version 1 の layout.json がありません。コマごとの画像は adb で art/ に置いてください"
            resolver.openInputStream(Uri.parse(imageUri))?.use { input ->
                File(importDirectory, name).outputStream().use { input.copyTo(it) }
            } ?: return@withContext "画像を読めません"
        }
        // layout.json は最後に書く。書き終えたときに取り込みが始まり、そのとき画像が揃っているように
        val text = layoutText ?: layout?.let(LayoutParser::encode)
        text?.let { File(importDirectory, "layout.json").writeText(it) }
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
    /** 読み込んだレイアウトを置く取り込み口。置けば FileLayoutStore が取り込む。 */
    private val importDirectory: File,
    /** いまのレイアウトの layout.json の文（見本なら null）。 */
    private val currentLayout: () -> String?,
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
        val layout = currentLayout()
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
            File(importDirectory, "layout.json").writeText(l)
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
