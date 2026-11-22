package dev.furunov.komalauncher.model.apps

import dev.furunov.komalauncher.model.settings.DrawerMove
import dev.furunov.komalauncher.model.settings.DrawerSettings

/** 全アプリ一覧のタブに並べるフォルダ 1 つ。 */
data class DrawerFolder(
    val id: String,
    val name: String,
    val apps: List<AppEntry>,
    /** 一覧の左端に描く記号の id（[DrawerFolders.symbols] のどれか）。 */
    val symbol: String = DrawerFolders.FOLDER_SYMBOL,
)

/**
 * 全アプリ一覧のフォルダの分け方。初めは Android の分類（[AppCategory]）で分け、分類の無いアプリは「その他」に入れる。
 * 利用者が移したアプリ（[DrawerSettings.moved]）は移した先に入れる。フォルダは中身があるときだけ並べるので、空にすれば消える。
 */
object DrawerFolders {
    const val OTHER = "other"

    /** 利用者が作ったフォルダの id の頭。既定のフォルダの id と重ならないようにする。 */
    private const val CUSTOM_PREFIX = "u"

    // 名前は全角 5 文字以内にする。全アプリ一覧の左の列（幅 68dp・10.5sp）で、それより長いと末尾が省略される

    /** 既定のフォルダの id と名前。タブはこの順に並べ、利用者が作ったフォルダは「その他」の前に入れる。 */
    val builtIn: List<Pair<String, String>> =
        listOf(
            AppCategory.GAME.id to "ゲーム",
            AppCategory.AUDIO.id to "音楽",
            AppCategory.VIDEO.id to "動画",
            AppCategory.IMAGE.id to "写真",
            AppCategory.SOCIAL.id to "連絡",
            AppCategory.BROWSER.id to "ブラウザ",
            AppCategory.NEWS.id to "ニュース",
            AppCategory.MAPS.id to "地図",
            AppCategory.PRODUCTIVITY.id to "仕事",
            AppCategory.ACCESSIBILITY.id to "補助",
            OTHER to "その他",
        )
    private val builtInNames = builtIn.toMap()

    const val FOLDER_SYMBOL = "folder"

    /** 選べる記号の id。絵は :feature:drawer が描く。並びは選ぶ画面の順。 */
    val symbols: List<String> =
        listOf(
            "game", "music", "mic", "video", "photo", "chat", "globe", "map", "plane", "food",
            "bag", "money", "home", "book", "work", "spark", "tool", "news", "access", "star", "heart", FOLDER_SYMBOL, "dots",
        )

    private val builtInSymbols: Map<String, String> =
        mapOf(
            AppCategory.GAME.id to "game",
            AppCategory.AUDIO.id to "music",
            AppCategory.VIDEO.id to "video",
            AppCategory.IMAGE.id to "photo",
            AppCategory.SOCIAL.id to "chat",
            AppCategory.BROWSER.id to "globe",
            AppCategory.NEWS.id to "news",
            AppCategory.MAPS.id to "map",
            AppCategory.PRODUCTIVITY.id to "work",
            AppCategory.ACCESSIBILITY.id to "access",
            OTHER to "dots",
        )

    fun folderOf(
        app: AppEntry,
        s: DrawerSettings,
    ): String = s.moved.firstOrNull { it.app == app.key }?.folder ?: automaticFolderOf(app)

    private fun automaticFolderOf(app: AppEntry): String = app.category?.id ?: OTHER

    fun nameOf(
        id: String,
        s: DrawerSettings,
    ): String = s.folderNames[id] ?: builtInNames[id] ?: id

    /** 記号の id。利用者が選んだもの、既定のフォルダの記号、どれでもなければフォルダの記号の順に決める。 */
    fun symbolOf(
        id: String,
        s: DrawerSettings,
    ): String = s.folderSymbols[id]?.takeIf { it in symbols } ?: builtInSymbols[id] ?: FOLDER_SYMBOL

    /** 記号を変える。 */
    fun setSymbol(
        s: DrawerSettings,
        id: String,
        symbol: String,
    ): DrawerSettings = s.copy(folderSymbols = s.folderSymbols + (id to symbol))

    /** 全てのフォルダの id の並び（空のものも含む）。並べ替えたタブの順（[DrawerSettings.tabOrder]）を先にする。 */
    private fun order(s: DrawerSettings): List<String> {
        val custom = s.folderNames.keys.filter { it.startsWith(CUSTOM_PREFIX) }.sortedBy { it.removePrefix(CUSTOM_PREFIX).toIntOrNull() ?: Int.MAX_VALUE }
        val default = builtIn.map { it.first }.dropLast(1) + custom + OTHER
        return (s.tabOrder.filter { it in default } + default).distinct()
    }

    /**
     * タブに並べるフォルダ。中身の無いフォルダは除く。
     * 各フォルダの中は、並べ替えた順（[DrawerSettings.appOrder]）を先にし、残りは [apps] の並び（ラベル順）にする。
     */
    fun of(
        apps: List<AppEntry>,
        s: DrawerSettings,
    ): List<DrawerFolder> {
        val byFolder = apps.groupBy { folderOf(it, s) }
        // 消えたフォルダ（名前の無い u の id）へ移したアプリは、id のままの名前で末尾に並べ、失わないようにする
        val ids = order(s).let { known -> known + byFolder.keys.filterNot { it in known } }
        return ids.mapNotNull { id -> byFolder[id]?.let { DrawerFolder(id, nameOf(id, s), sorted(it, s.appOrder[id].orEmpty()), symbolOf(id, s)) } }
    }

    private fun sorted(
        apps: List<AppEntry>,
        saved: List<AppKey>,
    ): List<AppEntry> {
        if (saved.isEmpty()) return apps
        val rank = saved.withIndex().associate { it.value to it.index }
        // sortedBy は安定なので、並べ替えていないアプリはラベル順のまま後ろに残る
        return apps.sortedBy { rank[it.key] ?: Int.MAX_VALUE }
    }

    /** フォルダ [folderId] の中の順を [apps] にする。表示している全てのアプリを渡す。 */
    fun reorderApps(
        s: DrawerSettings,
        folderId: String,
        apps: List<AppKey>,
    ): DrawerSettings = s.copy(appOrder = s.appOrder + (folderId to apps))

    /** タブの順を [ids] にする。表示している全てのフォルダの id を渡す。 */
    fun reorderTabs(
        s: DrawerSettings,
        ids: List<String>,
    ): DrawerSettings = s.copy(tabOrder = ids)

    /** [list] の [from] 番目を [to] 番目へ移す。範囲外の [from] なら元のまま返す。並べ替えの途中の指の位置から呼ぶ。 */
    fun <T> moveItem(
        list: List<T>,
        from: Int,
        to: Int,
    ): List<T> {
        if (from !in list.indices) return list
        return list.toMutableList().apply { add(to.coerceIn(0, lastIndex), removeAt(from)) }
    }

    /** 「フォルダを変更」で選べるフォルダ（空のものも含む）の id と名前。 */
    fun choices(s: DrawerSettings): List<Pair<String, String>> = order(s).map { it to nameOf(it, s) }

    /** [app] を [folder] へ移す。自動で入るフォルダへ戻したときは、移した記録を消す。 */
    fun move(
        s: DrawerSettings,
        app: AppEntry,
        folder: String,
    ): DrawerSettings {
        val rest = s.moved.filterNot { it.app == app.key }
        return s.copy(moved = if (folder == automaticFolderOf(app)) rest else rest + DrawerMove(app.key, folder))
    }

    /** 名前 [name] のフォルダを作り、その id を返す。同じ名前のフォルダがあれば、それを返す。 */
    fun create(
        s: DrawerSettings,
        name: String,
    ): Pair<DrawerSettings, String> {
        val trimmed = name.trim()
        choices(s).firstOrNull { it.second == trimmed }?.let { return s to it.first }
        val next = (s.folderNames.keys.filter { it.startsWith(CUSTOM_PREFIX) }.mapNotNull { it.removePrefix(CUSTOM_PREFIX).toIntOrNull() }.maxOrNull() ?: 0) + 1
        val id = CUSTOM_PREFIX + next
        return s.copy(folderNames = s.folderNames + (id to trimmed)) to id
    }

    /** 名前を変える。既定のフォルダを空の名前にすると、既定の名前に戻す。利用者が作ったフォルダは空の名前にしない。 */
    fun rename(
        s: DrawerSettings,
        id: String,
        name: String,
    ): DrawerSettings {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() && id in builtInNames -> s.copy(folderNames = s.folderNames - id)
            trimmed.isEmpty() -> s
            else -> s.copy(folderNames = s.folderNames + (id to trimmed))
        }
    }
}
