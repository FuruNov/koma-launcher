package dev.furunov.komalauncher.platform.files

import dev.furunov.komalauncher.model.apps.LaunchStore
import dev.furunov.komalauncher.model.apps.Launches
import dev.furunov.komalauncher.model.folders.FolderStore
import dev.furunov.komalauncher.model.folders.Folders
import dev.furunov.komalauncher.model.settings.Settings
import dev.furunov.komalauncher.model.settings.SettingsStore
import dev.furunov.komalauncher.model.widgets.WidgetStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class BackupFile(
    val version: Int = 1,
    val folders: Folders,
    val settings: Settings,
    val layout: String? = null,
    /** コマ → ウィジェットの提供元。appWidgetId は端末ごとに違うので書かない。 */
    val widgets: Map<String, String> = emptyMap(),
    /** アプリごとの起動回数（全アプリ一覧の「よく使う」）。古いバックアップには無い。 */
    val launches: Launches? = null,
)

/**
 * バックアップのファイル（koma-launcher-backup.json）の中身を作り、読み、戻す。
 * 中身はフォルダの中身・設定・layout.json・ウィジェットの提供元・起動回数。画像は容量が大きいので含めない。
 */
class BackupContent(
    /** いまのレイアウトの layout.json の文（見本なら null）。 */
    private val currentLayout: () -> String?,
    private val folders: FolderStore,
    private val settings: SettingsStore,
    private val widgets: WidgetStore,
    private val launches: LaunchStore,
) {
    /** 読んだバックアップ。[apply] で戻す。 */
    class Parsed internal constructor(
        internal val data: BackupFile,
    ) {
        val layout: String? get() = data.layout
        val widgetCount: Int get() = data.widgets.size
    }

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

    /** 読めなければ例外を投げる。 */
    fun parse(text: String): Parsed = Parsed(json.decodeFromString(BackupFile.serializer(), text))

    /** フォルダの中身・設定・起動回数を戻す。コマ割りは呼び出し側が取り込み口に書く（画像と一緒に取り込むため）。 */
    fun apply(parsed: Parsed) {
        val data = parsed.data
        folders.update { data.folders }
        settings.update { data.settings }
        data.launches?.let { l -> launches.update { l } }
    }
}
