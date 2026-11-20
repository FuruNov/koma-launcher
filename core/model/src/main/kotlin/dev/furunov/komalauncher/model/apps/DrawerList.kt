package dev.furunov.komalauncher.model.apps

/** 全アプリ一覧に並べるものを決める。 */
object DrawerList {
    /**
     * 入力が空のときに上に並べる「よく使う」。起動したことのあるアプリを回数の多い順に最大 [limit] 個。
     * 隠したアプリと、もう入っていないアプリ（[apps] に無い）は除く。
     */
    fun frequent(
        apps: List<AppEntry>,
        hidden: Set<AppKey>,
        launches: Map<AppKey, Int>,
        limit: Int,
    ): List<AppEntry> = apps
        .filter { it.key !in hidden && (launches[it.key] ?: 0) > 0 }
        .sortedByDescending { launches.getValue(it.key) }
        .take(limit)
}
