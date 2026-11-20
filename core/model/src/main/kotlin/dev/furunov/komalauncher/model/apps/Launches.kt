package dev.furunov.komalauncher.model.apps

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * アプリごとの起動回数。検索の並べ替えと、全アプリ一覧の「よく使う」に使う。端末の外には送らない。
 * キーは "パッケージ名/クラス名"（JSON のキーにするため）。
 */
@Serializable
data class Launches(
    val version: Int = 1,
    val counts: Map<String, Int> = emptyMap(),
) {
    fun countOf(key: AppKey): Int = counts[key.id()] ?: 0

    fun record(key: AppKey): Launches = copy(counts = counts + (key.id() to countOf(key) + 1))

    fun asMap(): Map<AppKey, Int> = counts.entries.mapNotNull { (k, v) -> parseId(k)?.let { it to v } }.toMap()
}

private fun AppKey.id() = "$packageName/$className"

private fun parseId(id: String): AppKey? = id.indexOf('/').takeIf { it > 0 }?.let { AppKey(id.substring(0, it), id.substring(it + 1)) }

interface LaunchStore {
    val launches: StateFlow<Launches>

    fun update(transform: (Launches) -> Launches)
}

/** 起動を数える [AppCatalog]。一覧・フォルダ・コマ・ジェスチャーのどこから起動しても、ここを通る。ショートカットの起動は数えない。 */
class CountingAppCatalog(
    private val inner: AppCatalog,
    private val store: LaunchStore,
) : AppCatalog by inner {
    override fun launch(key: AppKey) {
        inner.launch(key)
        store.update { it.record(key) }
    }
}
