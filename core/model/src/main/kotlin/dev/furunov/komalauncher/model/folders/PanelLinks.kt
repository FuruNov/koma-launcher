package dev.furunov.komalauncher.model.folders

import dev.furunov.komalauncher.model.apps.AppEntry
import dev.furunov.komalauncher.model.apps.AppKey
import dev.furunov.komalauncher.model.apps.DrawerFolders
import dev.furunov.komalauncher.model.settings.DrawerSettings

/**
 * コマのフォルダと全アプリ一覧のフォルダの紐付け（Folder.drawerFolder）。
 * 紐付けたコマの中身は、全アプリ一覧のフォルダから毎回組み立てる。コマの中身として保存はしない。
 * コマでの編集は、編集の前後の中身を比べて、全アプリ一覧の設定（DrawerSettings）に書き戻す。
 */
object PanelLinks {
    /** 表示に使う中身。紐付けたコマの apps を、全アプリ一覧のフォルダの中身（非表示のアプリを除く）に置き換える。 */
    fun effective(
        f: Folders,
        drawer: DrawerSettings,
        apps: List<AppEntry>,
    ): Folders {
        if (f.byPanel.values.none { it.drawerFolder != null }) return f
        val visible = apps.filter { it.key !in drawer.hidden }
        val folders = DrawerFolders.of(visible, drawer).associateBy { it.id }
        return f.copy(
            byPanel =
            f.byPanel.mapValues { (_, folder) ->
                val link = folder.drawerFolder ?: return@mapValues folder
                folder.copy(apps = folders[link]?.apps?.map { it.key }.orEmpty())
            },
        )
    }

    /**
     * 編集の前後（どちらも [effective] の中身）を比べ、紐付けたコマの差分を全アプリ一覧の設定に書き戻す。
     * 入れたアプリはそのフォルダへ移し、外したアプリは「その他」へ移し、並びが変わったらフォルダの中の順にする。
     */
    fun sync(
        before: Folders,
        after: Folders,
        drawer: DrawerSettings,
        entryOf: (AppKey) -> AppEntry,
    ): DrawerSettings {
        var d = drawer
        for ((id, folder) in after.byPanel) {
            val link = folder.drawerFolder ?: continue
            val old = before.appsOf(id)
            val new = folder.apps
            if (old == new) continue
            new.filterNot { it in old }.forEach { d = DrawerFolders.move(d, entryOf(it), link) }
            // 「その他」から外したアプリは行き先が無いので、そのまま残す
            if (link != DrawerFolders.OTHER) old.filterNot { it in new }.forEach { d = DrawerFolders.move(d, entryOf(it), DrawerFolders.OTHER) }
            d = DrawerFolders.reorderApps(d, link, new)
        }
        return d
    }

    /** 保存する中身。紐付けたコマは、保存してあった自分の apps を残す（表示に使った中身は全アプリ一覧のもの）。ほかは [after] のまま。 */
    fun storable(
        stored: Folders,
        after: Folders,
    ): Folders = after.copy(
        byPanel =
        after.byPanel.mapValues { (id, folder) ->
            if (folder.drawerFolder == null) folder else folder.copy(apps = stored.appsOf(id))
        },
    )

    /**
     * コマ [panelId] を全アプリ一覧のフォルダ [drawerFolder] に紐付ける。null なら紐付けを外し、そのとき表示していた中身（[shown]）をコマの中身として残す。
     * 紐付けたときは、コマがもともと持っていた中身を保存したまま残す（外したときに戻すため）。
     */
    fun link(
        stored: Folders,
        shown: Folders,
        panelId: String,
        drawerFolder: String?,
    ): Folders {
        val folder = stored.byPanel[panelId] ?: Folder()
        val next =
            if (drawerFolder == null) folder.copy(drawerFolder = null, apps = shown.appsOf(panelId)) else folder.copy(drawerFolder = drawerFolder)
        return stored.copy(byPanel = stored.byPanel + (panelId to next))
    }
}
