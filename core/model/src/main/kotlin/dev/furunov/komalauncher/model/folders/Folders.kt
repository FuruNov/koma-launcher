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
    /** true なら、コマを押したときにフォルダを開かず、先頭のアプリを起動する。 */
    val directLaunch: Boolean = false,
    /** true なら、音楽の再生中と一時停止中に、このコマをプレイヤーの表示にする。オンにできるのは 1 つのコマだけ。 */
    val musicPlayer: Boolean = false,
    /**
     * 紐付けた全アプリ一覧のフォルダの id。null でなければ、このコマの中身はそのフォルダの中身（順番も含む）になり、[apps] は使わない。
     * コマで入れる・外す・並べ替えると、全アプリ一覧のフォルダにも反映する（PanelLinks）。
     */
    val drawerFolder: String? = null,
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

    /**
     * 表示するフォルダの中身。今の一覧 [available] に無いアプリとショートカット（別の端末に復元した、アンインストールした、ブラウザの側で消した）を隠す。
     * データからは外さない。入れ直すと元の位置に戻り、ロックの解除前などで一時的に一覧に無いだけのこともあるため。
     * 一覧をまだ読み込めていない（空の）間は、全て返す。読み込む前にフォルダが空に見えないように。
     */
    fun shownAppsOf(
        panelId: String,
        available: Set<AppKey>,
    ): List<AppKey> = appsOf(panelId).filter { available.isEmpty() || it in available }

    /** 音楽プレイヤーにしたコマの id。どれもオフなら null。 */
    fun musicPanel(): String? = byPanel.entries.firstOrNull { it.value.musicPlayer }?.key
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

    /**
     * 全てのフォルダと「割り当てなし」で、[old] のどれかが最初にあった位置に [new] を置き、残りの [old] を外す。
     * 同じショートカットを追加し直したときに、古い方の位置を新しい方に引き継ぐ。[new] がすでにあるフォルダでは [old] を外すだけにする。
     */
    fun replace(
        f: Folders,
        old: List<AppKey>,
        new: AppKey,
    ): Folders {
        if (old.isEmpty()) return f
        val gone = old.toSet()

        fun swap(apps: List<AppKey>): List<AppKey> {
            val at = apps.indexOfFirst { it in gone }
            if (at < 0) return apps
            val rest = apps.toMutableList().apply { if (new !in apps) set(at, new) }
            return rest.filterNot { it in gone }
        }
        return f.copy(byPanel = f.byPanel.mapValues { (_, folder) -> folder.copy(apps = swap(folder.apps)) }, unassigned = swap(f.unassigned))
    }

    /** 入っていれば外し、無ければ末尾に入れる。アプリを選ぶ画面から呼ぶ。 */
    fun toggle(
        f: Folders,
        panelId: String,
        app: AppKey,
    ): Folders = if (app in f.appsOf(panelId)) remove(f, panelId, app) else add(f, panelId, app)

    /** 見えている並びの位置 [shownIndex] を、隠れた項目も含む [apps] の中の位置に直す。見えている数以上なら末尾。 */
    fun dataIndex(
        apps: List<AppKey>,
        shownIndex: Int,
        shown: (AppKey) -> Boolean,
    ): Int {
        var seen = 0
        apps.forEachIndexed { i, app ->
            if (shown(app)) {
                if (seen == shownIndex) return i
                seen++
            }
        }
        return apps.size
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

    fun setDirectLaunch(
        f: Folders,
        panelId: String,
        on: Boolean,
    ): Folders {
        val folder = f.byPanel[panelId] ?: Folder()
        return f.copy(byPanel = f.byPanel + (panelId to folder.copy(directLaunch = on)))
    }

    /** 音楽プレイヤーのコマを切り替える。オンにしたら、ほかのコマはオフにする（プレイヤーは 1 つだけ）。 */
    fun setMusicPlayer(
        f: Folders,
        panelId: String,
        on: Boolean,
    ): Folders {
        val others = if (on) f.byPanel.mapValues { (_, v) -> if (v.musicPlayer) v.copy(musicPlayer = false) else v } else f.byPanel
        val folder = others[panelId] ?: Folder()
        return f.copy(byPanel = others + (panelId to folder.copy(musicPlayer = on)))
    }

    /**
     * layout.json に合わせて整える。
     * - 初めて見るフォルダのコマには、layout.json の初期値を 1 度だけ入れる（[seedOnly]）
     * - layout.json から消えたコマの中身は unassigned へ移す（消さない）
     * 見本のレイアウトに戻っているとき（JSON の誤り）は呼ばないこと。書き損じ 1 つで割り当てを失うため。
     */
    fun reconcile(
        f: Folders,
        layout: Layout,
        resolve: (String) -> AppKey?,
    ): Folders {
        val result = seedOnly(f, layout, resolve)
        val liveIds = layout.panels.filter { it.content is PanelContent.Folder }.map { it.id.value }.toSet()
        val orphans = result.byPanel.filterKeys { it !in liveIds }
        if (orphans.isEmpty()) return result
        // 見本のコマの中身は利用者が選んだものではないので、unassigned に混ぜずに捨てる
        val moved = orphans.filterKeys { !it.startsWith(SAMPLE_PREFIX) }.values.flatMap { it.apps }
        return result.copy(
            byPanel = result.byPanel - orphans.keys,
            unassigned = (result.unassigned + moved).distinct(),
            seeded = result.seeded.filterNot { it.startsWith(SAMPLE_PREFIX) }.toSet(),
        )
    }

    /** 初めて見るフォルダのコマに、layout.json の初期値を 1 度だけ入れる。消えたコマの整理はしない。 */
    fun seedOnly(
        f: Folders,
        layout: Layout,
        resolve: (String) -> AppKey?,
    ): Folders {
        var result = f
        for (panel in layout.panels) {
            val content = panel.content as? PanelContent.Folder ?: continue
            val id = panel.id.value
            if (id in result.seeded) continue
            val initial = content.initialApps.mapNotNull(resolve).distinct()
            val existing = result.appsOf(id)
            val folder = (result.byPanel[id] ?: Folder()).copy(apps = existing + initial.filterNot { it in existing })
            result = result.copy(byPanel = result.byPanel + (id to folder), seeded = result.seeded + id)
        }
        return result
    }

    /** 見本のレイアウトのコマの id の接頭辞。利用者の layout.json の id と重ならないようにする。 */
    const val SAMPLE_PREFIX = "sample-"
}
