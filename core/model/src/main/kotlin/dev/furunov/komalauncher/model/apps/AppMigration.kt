package dev.furunov.komalauncher.model.apps

import dev.furunov.komalauncher.model.folders.Folders
import dev.furunov.komalauncher.model.settings.DrawerSettings

/**
 * 機種変更やアプリの入れ直しで、保存してあるアプリの記録が今の端末と食い違ったときの扱い。
 * - 起動の入口（Activity）の名前だけが違う同じアプリは、今の入口に読み替える（[remapping]、[remap]）
 * - 登録してあるのにまだ入っていないアプリを並べ、入れ直せるようにする（[missingPackages]、[names]）
 */
object AppMigration {
    /**
     * 読み替える記録。今の端末に無い入口のうち、同じパッケージの入口がちょうど 1 つ入っているものを、その入口に読み替える。
     * 同じパッケージに入口が複数あるとき（電話と連絡先など）は、どれに当たるか分からないので読み替えない。ショートカットも読み替えない。
     */
    fun remapping(
        stored: Collection<AppKey>,
        installed: List<AppEntry>,
    ): Map<AppKey, AppKey> {
        val present = installed.mapTo(HashSet()) { it.key }
        val byPackage = installed.filterNot { it.key.isShortcut }.groupBy { it.key.packageName }
        return stored
            .filter { !it.isShortcut && it !in present }
            .mapNotNull { key -> byPackage[key.packageName]?.singleOrNull()?.let { key to it.key } }
            .toMap()
    }

    fun remap(
        f: Folders,
        m: Map<AppKey, AppKey>,
    ): Folders {
        if (m.isEmpty()) return f
        fun List<AppKey>.mapped() = map { m[it] ?: it }.distinct()
        return f.copy(byPanel = f.byPanel.mapValues { (_, folder) -> folder.copy(apps = folder.apps.mapped()) }, unassigned = f.unassigned.mapped())
    }

    fun remap(
        d: DrawerSettings,
        m: Map<AppKey, AppKey>,
    ): DrawerSettings {
        if (m.isEmpty()) return d
        return d.copy(
            hidden = d.hidden.mapTo(LinkedHashSet()) { m[it] ?: it },
            moved = d.moved.map { mv -> m[mv.app]?.let { mv.copy(app = it) } ?: mv }.distinctBy { it.app },
            appOrder = d.appOrder.mapValues { (_, l) -> l.map { m[it] ?: it }.distinct() },
        )
    }

    /** コマのフォルダと全アプリ一覧に登録してあるアプリ（ショートカットを除く）。 */
    fun registered(
        f: Folders,
        d: DrawerSettings,
    ): Set<AppKey> = (f.byPanel.values.flatMap { it.apps } + f.unassigned + d.moved.map { it.app } + d.appOrder.values.flatten())
        .filterNot { it.isShortcut }
        .toSet()

    /** 登録してあるのに、今の端末に入っていないアプリのパッケージ名。名前の順に並べるのは呼び出し側。 */
    fun missingPackages(
        f: Folders,
        d: DrawerSettings,
        installed: List<AppEntry>,
    ): List<String> {
        val present = installed.mapTo(HashSet()) { it.key.packageName }
        return registered(f, d).map { it.packageName }.filterNot { it in present }.distinct().sorted()
    }

    /**
     * 登録してあるアプリのパッケージ名 → 名前。入っているアプリは今の名前にし、入っていないアプリは [known] に覚えている名前を残す。
     * 設定に保存してバックアップに含め、新しい端末で入っていないアプリの名前を出すために使う。
     */
    fun names(
        f: Folders,
        d: DrawerSettings,
        installed: List<AppEntry>,
        known: Map<String, String>,
    ): Map<String, String> {
        val current = installed.filterNot { it.key.isShortcut }.associate { it.key.packageName to it.label }
        return registered(f, d).map { it.packageName }.distinct().mapNotNull { p -> (current[p] ?: known[p])?.let { p to it } }.toMap()
    }
}
