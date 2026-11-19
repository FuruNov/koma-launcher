package dev.furunov.komalauncher.feature.settings

import dev.furunov.komalauncher.model.apps.AppEntry
import dev.furunov.komalauncher.model.settings.AnimationCatalog
import dev.furunov.komalauncher.model.settings.GestureKind
import dev.furunov.komalauncher.model.settings.LauncherAction
import dev.furunov.komalauncher.model.settings.Settings

/**
 * 設定画面の 1 行。画面はこの表を並べるだけで作る。
 * 項目を増やすときは [settingsSections] に 1 行加える。画面のコードは触らない。
 */
sealed interface SettingRow {
    val title: String

    class Toggle(
        override val title: String,
        val get: (Settings) -> Boolean,
        val set: (Settings, Boolean) -> Settings,
    ) : SettingRow

    class Stepper(
        override val title: String,
        val range: IntRange,
        val step: Int = 1,
        val unit: String = "",
        val get: (Settings) -> Int,
        val set: (Settings, Int) -> Settings,
    ) : SettingRow

    class Choice(
        override val title: String,
        val options: List<Option>,
        val get: (Settings) -> String,
        val set: (Settings, String) -> Settings,
    ) : SettingRow

    /** ジェスチャーに操作を割り当てる行。選択肢は [actionOptions] から作る。 */
    class Gesture(
        override val title: String,
        val gesture: GestureKind,
    ) : SettingRow

    /** 押すと何かをする行（ファイルの取り込みなど）。動作は画面側が [key] で決める。 */
    class Action(
        override val title: String,
        val subtitle: String,
        val key: ActionKey,
    ) : SettingRow
}

data class Option(
    val id: String,
    val label: String,
)

enum class ActionKey { ImportArt, ExportBackup, ImportBackup, HomeApp }

data class SettingSection(
    val title: String,
    val rows: List<SettingRow>,
)

val gestureTitles: Map<GestureKind, String> =
    mapOf(
        GestureKind.SwipeUp to "上へスワイプ",
        GestureKind.SwipeDown to "下へスワイプ",
        GestureKind.TwoFingerSwipeUp to "2 本指で上へスワイプ",
        GestureKind.TwoFingerSwipeDown to "2 本指で下へスワイプ",
        GestureKind.PinchIn to "ピンチイン",
        GestureKind.DoubleTapGutter to "コマの間をダブルタップ",
        GestureKind.LongPressGutter to "コマの間を長押し",
    )

fun settingsSections(): List<SettingSection> = listOf(
    SettingSection(
        "ジェスチャー",
        GestureKind.entries.map { SettingRow.Gesture(gestureTitles[it] ?: it.name, it) },
    ),
    SettingSection(
        "見た目",
        listOf(
            SettingRow.Stepper("フォルダの列数", 2..6, get = { it.appearance.folderColumns }) { s, v ->
                s.copy(appearance = s.appearance.copy(folderColumns = v))
            },
            SettingRow.Stepper("アイコンの大きさ", 36..72, step = 4, unit = "dp", get = { it.appearance.iconSizeDp }) { s, v ->
                s.copy(appearance = s.appearance.copy(iconSizeDp = v))
            },
            SettingRow.Toggle("アイコンの名前を表示", { it.appearance.showLabels }) { s, v ->
                s.copy(appearance = s.appearance.copy(showLabels = v))
            },
            SettingRow.Stepper(
                "フォルダの背景の不透明度",
                50..100,
                step = 5,
                unit = "%",
                get = { (it.appearance.folderOpacity * 100).toInt() },
            ) { s, v -> s.copy(appearance = s.appearance.copy(folderOpacity = v / 100f)) },
            SettingRow.Choice(
                "運ぶときに光る色",
                listOf(
                    Option("FFFFD54F", "黄"),
                    Option("FF4FC3F7", "水色"),
                    Option("FFF48FB1", "桃"),
                    Option("FFFFFFFF", "白"),
                ),
                get = { it.appearance.highlightColor.toString(16).uppercase() },
            ) { s, v -> s.copy(appearance = s.appearance.copy(highlightColor = v.toLong(16))) },
            SettingRow.Choice(
                "コマの間の色",
                listOf(Option("FF111111", "黒"), Option("FFFFFFFF", "白"), Option("FF2B2B33", "濃い灰")),
                get = { it.appearance.gutterColor.toString(16).uppercase() },
            ) { s, v -> s.copy(appearance = s.appearance.copy(gutterColor = v.toLong(16))) },
        ),
    ),
    SettingSection(
        "アニメーション",
        listOf(
            SettingRow.Toggle("アニメーションを使う", { it.animation.enabled }) { s, v -> s.copy(animation = s.animation.copy(enabled = v)) },
            SettingRow.Choice(
                "ロック解除のとき",
                AnimationCatalog.enter.map { Option(it.id, it.label) },
                get = { it.animation.enter },
            ) { s, v -> s.copy(animation = s.animation.copy(enter = v)) },
            SettingRow.Choice(
                "コマを押したとき",
                AnimationCatalog.press.map { Option(it.id, it.label) },
                get = { it.animation.press },
            ) { s, v -> s.copy(animation = s.animation.copy(press = v)) },
            SettingRow.Stepper("速さ（小さいほど速い）", 50..200, step = 25, unit = "%", get = { (it.animation.speed * 100).toInt() }) { s, v ->
                s.copy(animation = s.animation.copy(speed = v / 100f))
            },
            SettingRow.Stepper("コマごとの間隔", 0..150, step = 10, unit = "ms", get = { it.animation.staggerMs }) { s, v ->
                s.copy(animation = s.animation.copy(staggerMs = v))
            },
        ),
    ),
    SettingSection(
        "全アプリ一覧",
        listOf(
            SettingRow.Stepper("列数", 3..6, get = { it.drawer.columns }) { s, v -> s.copy(drawer = s.drawer.copy(columns = v)) },
        ),
    ),
    SettingSection(
        "コマの絵とバックアップ",
        listOf(
            SettingRow.Action("コマの絵を取り込む", "layout.json と画像を選ぶ", ActionKey.ImportArt),
            SettingRow.Action("バックアップを書き出す", "フォルダ・設定・layout.json を 1 つのファイルに", ActionKey.ExportBackup),
            SettingRow.Action("バックアップを読み込む", "ウィジェットは置き直しが要ります", ActionKey.ImportBackup),
            SettingRow.Action("既定のホームアプリ", "Android の設定を開く", ActionKey.HomeApp),
        ),
    ),
)

/** ジェスチャーに割り当てられる操作の選択肢。フォルダとアプリは、いまの状態から並べる。 */
fun actionOptions(
    folders: List<Pair<String, String>>,
    apps: List<AppEntry>,
): List<Pair<LauncherAction, String>> = LauncherAction.simpleChoices.map { it to actionLabel(it) } +
    folders.map { (id, label) -> LauncherAction.OpenFolder(id) to "フォルダを開く: $label" } +
    apps.map { LauncherAction.LaunchApp(it.key) to "アプリを起動: ${it.label}" }

fun actionLabel(
    a: LauncherAction,
    folderLabel: (String) -> String = { it },
    appLabel: (LauncherAction.LaunchApp) -> String = { it.app.packageName },
): String = when (a) {
    LauncherAction.None -> "何もしない"
    LauncherAction.OpenDrawer -> "全アプリ一覧"
    LauncherAction.ExpandNotifications -> "通知センター"
    LauncherAction.ExpandQuickSettings -> "クイック設定"
    LauncherAction.OpenHomeMenu -> "ホームのメニュー"
    LauncherAction.OpenSettings -> "ランチャーの設定"
    LauncherAction.LockScreen -> "画面ロック（ユーザー補助の許可が要る）"
    is LauncherAction.OpenFolder -> "フォルダを開く: ${folderLabel(a.panelId)}"
    is LauncherAction.LaunchApp -> "アプリを起動: ${appLabel(a)}"
}
