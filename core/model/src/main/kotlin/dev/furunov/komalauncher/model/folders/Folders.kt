package dev.furunov.komalauncher.model.folders

import dev.furunov.komalauncher.model.apps.AppKey
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.PanelContent
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class Folder(
    val apps: List<AppKey> = emptyList(),
    /** 利用者が付けた名前。null なら layout.json の label を使う。 */
    val name: String? = null,
)

/** フォルダの中身の全体。保存の単位でもある。キーは PanelId の文字列（JSON のキーにするため）。 */
@Serializable
data class Folders(
    val version: Int = 1,
    val byPanel: Map<String, Folder> = emptyMap(),
    /** コマの絵を替えて行き場を失ったアプリ。設定画面から別のコマへ移せる。 */
    val unassigned: List<AppKey> = emptyList(),
    /** layout.json の初期値を取り込み済みのコマ。2 回目以降に初期値で上書きしないため。 */
    val seeded: Set<String> = emptySet(),
) {
    fun appsOf(panelId: String): List<AppKey> = byPanel[panelId]?.apps.orEmpty()
}

interface FolderStore {
    val folders: StateFlow<Folders>

    fun update(transform: (Folders) -> Folders)
}

/**
 * フォルダの編集操作。すべて純粋な関数で、UI はこれを呼ぶだけにする。
 * 範囲外の位置や存在しないアプリが渡されても例外にせず、できる範囲で処理するか元の値を返す。
 * ドラッグの途中で一覧が更新されるなど、UI からはずれた値が来うるため。
 */
object FolderEdits {
    fun add(
        f: Folders,
        panelId: String,
        app: AppKey,
        index: Int? = null,
    ): Folders {
        val folder = f.byPanel[panelId] ?: Folder()
        if (app in folder.apps) return f
        val at = (index ?: folder.apps.size).coerceIn(0, folder.apps.size)
        val apps = folder.apps.toMutableList().apply { add(at, app) }
        return f.copy(byPanel = f.byPanel + (panelId to folder.copy(apps = apps)), unassigned = f.unassigned - app)
    }

    fun remove(
        f: Folders,
        panelId: String,
        app: AppKey,
    ): Folders {
        val folder = f.byPanel[panelId] ?: return f
        if (app !in folder.apps) return f
        return f.copy(byPanel = f.byPanel + (panelId to folder.copy(apps = folder.apps - app)))
    }

    fun reorder(
        f: Folders,
        panelId: String,
        from: Int,
        to: Int,
    ): Folders {
        val folder = f.byPanel[panelId] ?: return f
        if (from !in folder.apps.indices) return f
        val apps = folder.apps.toMutableList()
        val app = apps.removeAt(from)
        apps.add(to.coerceIn(0, apps.size), app)
        return f.copy(byPanel = f.byPanel + (panelId to folder.copy(apps = apps)))
    }

    /** 別のコマへ移す。同じコマへの移動は並べ替えとして扱う。 */
    fun move(
        f: Folders,
        fromPanel: String,
        app: AppKey,
        toPanel: String,
        index: Int? = null,
    ): Folders {
        if (fromPanel == toPanel) {
            val from = f.appsOf(fromPanel).indexOf(app)
            return if (from < 0) f else reorder(f, fromPanel, from, index ?: f.appsOf(fromPanel).lastIndex)
        }
        if (app !in f.appsOf(fromPanel)) return f
        if (app in f.appsOf(toPanel)) return remove(f, fromPanel, app)
        return add(remove(f, fromPanel, app), toPanel, app, index)
    }

    fun rename(
        f: Folders,
        panelId: String,
        name: String?,
    ): Folders {
        val folder = f.byPanel[panelId] ?: Folder()
        return f.copy(byPanel = f.byPanel + (panelId to folder.copy(name = name?.takeIf { it.isNotBlank() })))
    }

    /**
     * layout.json に合わせて整える。
     * - 初めて見るフォルダのコマには、layout.json の初期値を 1 度だけ入れる
     * - layout.json から消えたコマの中身は unassigned へ移す（消さない）
     * 見本のレイアウトに戻っているとき（JSON の誤り）は呼ばないこと。書き損じ 1 つで割り当てを失うため。
     */
    fun reconcile(
        f: Folders,
        layout: Layout,
        resolve: (String) -> AppKey?,
    ): Folders {
        val folderPanels = layout.panels.filter { it.content is PanelContent.Folder }
        val liveIds = folderPanels.map { it.id.value }.toSet()
        var result = f
        for (panel in folderPanels) {
            val id = panel.id.value
            if (id in result.seeded) continue
            val initial = (panel.content as PanelContent.Folder).initialApps.mapNotNull(resolve).distinct()
            val existing = result.appsOf(id)
            val folder = (result.byPanel[id] ?: Folder()).copy(apps = existing + initial.filterNot { it in existing })
            result = result.copy(byPanel = result.byPanel + (id to folder), seeded = result.seeded + id)
        }
        val orphans = result.byPanel.filterKeys { it !in liveIds }
        if (orphans.isNotEmpty()) {
            val moved = orphans.values.flatMap { it.apps }
            result =
                result.copy(
                    byPanel = result.byPanel - orphans.keys,
                    unassigned = (result.unassigned + moved).distinct(),
                )
        }
        return result
    }
}
