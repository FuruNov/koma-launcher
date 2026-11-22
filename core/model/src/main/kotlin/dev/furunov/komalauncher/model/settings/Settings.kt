package dev.furunov.komalauncher.model.settings

import dev.furunov.komalauncher.model.apps.AppKey
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** ホーム画面が見分けるジェスチャー。ホーム画面はこれを出すだけで、何を実行するかは知らない。 */
@Serializable
enum class GestureKind {
    SwipeUp,
    SwipeDown,
    TwoFingerSwipeUp,
    TwoFingerSwipeDown,
    PinchIn,
    DoubleTapGutter,
    LongPressGutter,
}

/**
 * ジェスチャーに割り当てられる操作。操作を増やすときは、ここに 1 つ加え、
 * ActionPerformer の実装に実行の処理を 1 つ登録する。設定画面の選択肢は [LauncherAction.simpleChoices] から作る。
 */
@Serializable
sealed interface LauncherAction {
    @Serializable
    @SerialName("none")
    data object None : LauncherAction

    @Serializable
    @SerialName("drawer")
    data object OpenDrawer : LauncherAction

    @Serializable
    @SerialName("notifications")
    data object ExpandNotifications : LauncherAction

    @Serializable
    @SerialName("quick_settings")
    data object ExpandQuickSettings : LauncherAction

    @Serializable
    @SerialName("home_menu")
    data object OpenHomeMenu : LauncherAction

    @Serializable
    @SerialName("settings")
    data object OpenSettings : LauncherAction

    @Serializable
    @SerialName("lock")
    data object LockScreen : LauncherAction

    @Serializable
    @SerialName("folder")
    data class OpenFolder(
        val panelId: String,
    ) : LauncherAction

    @Serializable
    @SerialName("app")
    data class LaunchApp(
        val app: AppKey,
    ) : LauncherAction

    companion object {
        /** 引数の要らない操作。設定画面の選択肢の並び順でもある。 */
        val simpleChoices: List<LauncherAction> =
            listOf(None, OpenDrawer, ExpandNotifications, ExpandQuickSettings, OpenHomeMenu, OpenSettings, LockScreen)
    }
}

@Serializable
data class Appearance(
    val folderColumns: Int = 4,
    val iconSizeDp: Int = 52,
    val showLabels: Boolean = true,
    /** フォルダの背景の不透明度（0〜1）。 */
    val folderOpacity: Float = 0.92f,
    /** 指の下のコマを光らせる色（ARGB）。 */
    val highlightColor: Long = 0xFFFFD54F,
    /** コマの間の余白の色（ARGB）。 */
    val gutterColor: Long = 0xFF111111,
    /** アイコンの形。既定は端末の標準（Android のアイコンをそのまま描く）。 */
    val iconShape: IconShape = IconShape.SYSTEM,
    /** アイコンの縁。形の縁の内側に描く。 */
    val iconRim: IconRim = IconRim.NONE,
    /** true なら、アイコンの下に光の向きと反対の影を落とす。 */
    val iconShadow: Boolean = false,
    /** アイコンの縁の太さ（標準の太さに対する百分率、50〜200）。 */
    val iconRimPercent: Int = 100,
) {
    val iconStyle: IconStyle get() = IconStyle(iconShape, iconRim, iconShadow, iconRimPercent.coerceIn(50, 200) / 100f)
}

@Serializable
enum class IconShape {
    @SerialName("system")
    SYSTEM,

    @SerialName("circle")
    CIRCLE,

    @SerialName("rounded")
    ROUNDED,

    @SerialName("squircle")
    SQUIRCLE,

    @SerialName("tear")
    TEAR,

    @SerialName("bubble")
    BUBBLE,

    @SerialName("koma")
    KOMA,
}

@Serializable
enum class IconRim {
    @SerialName("none")
    NONE,

    @SerialName("ink")
    INK,

    @SerialName("sticker")
    STICKER,
}

/** アイコンの描き方。設定から作り、アイコンのキャッシュのキーにも使う。 */
data class IconStyle(
    val shape: IconShape,
    val rim: IconRim,
    val shadow: Boolean,
    /** 縁の太さの倍率（1 で標準）。 */
    val rimScale: Float = 1f,
) {
    /** 端末の標準で、縁も影も無い（Android のアイコンをそのまま描く）。 */
    val isPlain: Boolean get() = shape == IconShape.SYSTEM && rim == IconRim.NONE && !shadow
}

@Serializable
data class AnimationSettings(
    val enabled: Boolean = true,
    val enter: String = "slide-overshoot",
    val press: String = "squash",
    /** 1 が標準。大きいほどゆっくり。 */
    val speed: Float = 1f,
    val staggerMs: Int = 50,
)

@Serializable
data class MusicSettings(
    /** 一時停止からこの分数が過ぎたら、プレイヤーの表示をやめて、いつものコマに戻す。 */
    val pauseTimeoutMinutes: Int = 30,
    /** true なら、ビジュアライザーを実際の音（Visualizer）に合わせて動かす。マイクの権限が要る。 */
    val reactToAudio: Boolean = false,
)

@Serializable
data class DrawerSettings(
    val columns: Int = 5,
    val hidden: Set<AppKey> = emptySet(),
    /** 利用者が別のフォルダへ移したアプリ。Android の分類による自動の分け方より優先する（DrawerFolders）。 */
    val moved: List<DrawerMove> = emptyList(),
    /** フォルダの id → 名前。利用者が作ったフォルダ（id が u で始まる）と、名前を変えた既定のフォルダだけを持つ。 */
    val folderNames: Map<String, String> = emptyMap(),
    /** フォルダの id → 並べ替えたアプリの順。ここに無いアプリ（後から入れたものなど）は、その後ろに名前順で並べる。 */
    val appOrder: Map<String, List<AppKey>> = emptyMap(),
    /** 並べ替えたタブ（フォルダの id）の順。ここに無いフォルダは、その後ろに既定の順で並べる。 */
    val tabOrder: List<String> = emptyList(),
)

/** AppKey は JSON のキーにできないので、Map でなく組の並びで持つ。 */
@Serializable
data class DrawerMove(
    val app: AppKey,
    val folder: String,
)

@Serializable
data class Settings(
    val version: Int = 1,
    val gestures: Map<GestureKind, LauncherAction> = DEFAULT_GESTURES,
    val appearance: Appearance = Appearance(),
    val animation: AnimationSettings = AnimationSettings(),
    val drawer: DrawerSettings = DrawerSettings(),
    val music: MusicSettings = MusicSettings(),
) {
    fun actionFor(g: GestureKind): LauncherAction = gestures[g] ?: DEFAULT_GESTURES[g] ?: LauncherAction.None

    companion object {
        val DEFAULT_GESTURES: Map<GestureKind, LauncherAction> =
            mapOf(
                GestureKind.SwipeUp to LauncherAction.OpenDrawer,
                GestureKind.SwipeDown to LauncherAction.ExpandNotifications,
                GestureKind.TwoFingerSwipeDown to LauncherAction.ExpandQuickSettings,
                GestureKind.LongPressGutter to LauncherAction.OpenHomeMenu,
            )
    }
}

interface SettingsStore {
    val settings: StateFlow<Settings>

    fun update(transform: (Settings) -> Settings)
}

/**
 * アニメーションの案の名前。実装は :feature:home にあり、ここの id で引く。
 * 設定画面がホームのモジュールに依存せずに選択肢を並べられるよう、名前だけをここに置く。
 * 案を増やすときは、ここに 1 行と、:feature:home の PanelAnimations に実装を 1 つ加える。
 */
object AnimationCatalog {
    data class Entry(
        val id: String,
        val label: String,
    )

    val enter =
        listOf(
            Entry("slide-overshoot", "スライドイン"),
            Entry("pop-in", "ポップ"),
            Entry("drop", "ドロップ"),
        )
    val press =
        listOf(
            Entry("squash", "バウンス"),
            Entry("lift", "リフト"),
        )
}
