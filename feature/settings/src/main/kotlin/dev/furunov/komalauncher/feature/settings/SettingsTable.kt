package dev.furunov.komalauncher.feature.settings

import dev.furunov.komalauncher.model.apps.AppEntry
import dev.furunov.komalauncher.model.settings.AnimationCatalog
import dev.furunov.komalauncher.model.settings.GestureKind
import dev.furunov.komalauncher.model.settings.IconRim
import dev.furunov.komalauncher.model.settings.IconShape
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

    /** Android の権限が要るオン・オフ。オンにするときに権限が無ければ求め、断られたらオフのままにする。 */
    class PermissionToggle(
        override val title: String,
        val subtitle: String,
        val permission: String,
        val get: (Settings) -> Boolean,
        val set: (Settings, Boolean) -> Settings,
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

enum class ActionKey { EditLayout, MusicAccess, RestoreFolder, AutoBackup, HomeApp }

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
        GestureKind.DoubleTapGutter to "余白をダブルタップ",
        GestureKind.LongPressGutter to "余白を長押し",
    )

fun settingsSections(): List<SettingSection> = listOf(
    SettingSection(
        "コマ割り",
        listOf(SettingRow.Action("コマ割りを編集", "コマごとの設定と、すき間・枠などの全体の設定", ActionKey.EditLayout)),
    ),
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
            SettingRow.Toggle("アプリ名を表示", { it.appearance.showLabels }) { s, v ->
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
                "ドラッグ時の強調色",
                listOf(
                    Option("FFFFD54F", "黄"),
                    Option("FF4FC3F7", "水色"),
                    Option("FFF48FB1", "ピンク"),
                    Option("FFFFFFFF", "白"),
                ),
                get = { it.appearance.highlightColor.toString(16).uppercase() },
            ) { s, v -> s.copy(appearance = s.appearance.copy(highlightColor = v.toLong(16))) },
            // 端末の標準の形は取得できないので、縁か影を付けると角丸の四角として切り抜く（IconOutline）
            SettingRow.Choice(
                "アイコンの形",
                listOf(
                    Option(IconShape.SYSTEM.name, "端末の標準"),
                    Option(IconShape.CIRCLE.name, "円"),
                    Option(IconShape.ROUNDED.name, "角丸の四角"),
                    Option(IconShape.SQUIRCLE.name, "スクワークル"),
                    Option(IconShape.TEAR.name, "涙形"),
                    Option(IconShape.BUBBLE.name, "吹き出し"),
                    Option(IconShape.KOMA.name, "コマ形"),
                ),
                get = { it.appearance.iconShape.name },
            ) { s, v -> s.copy(appearance = s.appearance.copy(iconShape = IconShape.valueOf(v))) },
            SettingRow.Choice(
                "アイコンの縁",
                listOf(Option(IconRim.NONE.name, "なし"), Option(IconRim.INK.name, "墨の線"), Option(IconRim.STICKER.name, "白フチ")),
                get = { it.appearance.iconRim.name },
            ) { s, v -> s.copy(appearance = s.appearance.copy(iconRim = IconRim.valueOf(v))) },
            SettingRow.Stepper(
                "アイコンの縁の太さ",
                50..200,
                step = 25,
                unit = "%",
                get = { it.appearance.iconRimPercent },
            ) { s, v -> s.copy(appearance = s.appearance.copy(iconRimPercent = v)) },
            SettingRow.Toggle("アイコンの影", { it.appearance.iconShadow }) { s, v ->
                s.copy(appearance = s.appearance.copy(iconShadow = v))
            },
            // すき間の色はコマ割りの編集画面の「全体」で変える。レイアウトの色が設定の色より優先され、
            // 見本から書き出したレイアウトはいつも色を持つので、ここで変えても反映されなかった
        ),
    ),
    SettingSection(
        "アニメーション",
        listOf(
            SettingRow.Toggle("アニメーションを使う", { it.animation.enabled }) { s, v -> s.copy(animation = s.animation.copy(enabled = v)) },
            SettingRow.Choice(
                "ロック解除時",
                AnimationCatalog.enter.map { Option(it.id, it.label) },
                get = { it.animation.enter },
            ) { s, v -> s.copy(animation = s.animation.copy(enter = v)) },
            SettingRow.Choice(
                "コマのタップ時",
                AnimationCatalog.press.map { Option(it.id, it.label) },
                get = { it.animation.press },
            ) { s, v -> s.copy(animation = s.animation.copy(press = v)) },
            SettingRow.Stepper("所要時間", 50..200, step = 25, unit = "%", get = { (it.animation.speed * 100).toInt() }) { s, v ->
                s.copy(animation = s.animation.copy(speed = v / 100f))
            },
            SettingRow.Stepper("コマごとの遅延", 0..150, step = 10, unit = "ms", get = { it.animation.staggerMs }) { s, v ->
                s.copy(animation = s.animation.copy(staggerMs = v))
            },
        ),
    ),
    SettingSection(
        "音楽プレイヤー",
        listOf(
            // サブタイトルは画面側が許可の状態（許可済み・未許可）で書き換える
            SettingRow.Action("通知へのアクセス", "", ActionKey.MusicAccess),
            SettingRow.Stepper("一時停止から元に戻すまで", 5..120, step = 5, unit = "分", get = { it.music.pauseTimeoutMinutes }) { s, v ->
                s.copy(music = s.music.copy(pauseTimeoutMinutes = v))
            },
            SettingRow.PermissionToggle(
                "音に合わせて動かす",
                "マイクの権限を使います。音の録音・保存はしません",
                android.Manifest.permission.RECORD_AUDIO,
                { it.music.reactToAudio },
            ) { s, v -> s.copy(music = s.music.copy(reactToAudio = v)) },
        ),
    ),
    SettingSection(
        "全アプリ一覧",
        listOf(
            SettingRow.Stepper("列数", 3..6, get = { it.drawer.columns }) { s, v -> s.copy(drawer = s.drawer.copy(columns = v)) },
        ),
    ),
    SettingSection(
        "バックアップ",
        listOf(
            SettingRow.Action("自動バックアップ", "", ActionKey.AutoBackup),
            SettingRow.Action("フォルダから復元", "設定・コマ割り・画像・切り抜きを読み込み", ActionKey.RestoreFolder),
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
    LauncherAction.None -> "なし"
    LauncherAction.OpenDrawer -> "全アプリ一覧"
    LauncherAction.ExpandNotifications -> "通知センター"
    LauncherAction.ExpandQuickSettings -> "クイック設定"
    LauncherAction.OpenHomeMenu -> "ホームのメニュー"
    LauncherAction.OpenSettings -> "ランチャーの設定"
    LauncherAction.LockScreen -> "画面ロック（ユーザー補助の権限が必要）"
    is LauncherAction.OpenFolder -> "フォルダを開く: ${folderLabel(a.panelId)}"
    is LauncherAction.LaunchApp -> "アプリを起動: ${appLabel(a)}"
}
