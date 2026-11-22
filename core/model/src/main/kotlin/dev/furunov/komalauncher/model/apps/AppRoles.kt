package dev.furunov.komalauncher.model.apps

/** アプリが開ける・扱えるもの。:core:platform が Intent の問い合わせで調べる。 */
enum class AppRole {
    /** Web のページ（http・https）を開ける。 */
    BROWSER,

    /** 電話をかけられる。 */
    DIAL,

    /** SMS かメールを送れる。 */
    MESSAGE,

    /** 地図の位置（geo:）を開ける。 */
    MAPS,

    /** 写真を撮れる。 */
    CAMERA,

    /** 画像を開ける。 */
    IMAGE,

    /** 動画を開ける。 */
    VIDEO,

    /** 音声を開ける。 */
    AUDIO,

    /** どんな種類のファイルでも開ける（ファイルマネージャーなど）。 */
    ANY_FILE,
}

/**
 * アプリの役割から、全アプリ一覧のフォルダの分類を決める。アプリが申告する分類（ApplicationInfo.category）は誤りや抜けが多いため。
 * 実機（2026-11-22）では Chrome と Vivaldi が SNS、カラオケの会員アプリがゲームを申告し、マップ・カメラ・Netflix は申告していなかった。
 */
object AppRoles {
    /**
     * ブラウザ・電話・SMS とメール・地図・カメラは、申告より優先する。
     * 画像・動画・音声を開けることは、申告が無いときだけ使う。画像を開けるメッセージアプリなどを写真に入れないため。
     * どんなファイルでも開けるアプリは、画像・動画・音声の判定に使わない。
     */
    fun categoryOf(
        roles: Set<AppRole>,
        declared: AppCategory?,
    ): AppCategory? = when {
        AppRole.BROWSER in roles -> AppCategory.BROWSER
        AppRole.DIAL in roles || AppRole.MESSAGE in roles -> AppCategory.SOCIAL
        AppRole.MAPS in roles -> AppCategory.MAPS
        AppRole.CAMERA in roles -> AppCategory.IMAGE
        declared != null || AppRole.ANY_FILE in roles -> declared
        AppRole.IMAGE in roles -> AppCategory.IMAGE
        AppRole.VIDEO in roles -> AppCategory.VIDEO
        AppRole.AUDIO in roles -> AppCategory.AUDIO
        else -> null
    }
}
