package dev.furunov.komalauncher.model.apps

/** 固定したショートカット（ブラウザの PWA など）の扱い。 */
object PinnedShortcuts {
    /**
     * 追加しようとしているショートカットと同じものとみなす、固定済みのショートカット。同じパッケージで同じ名前のものを同じとみなす。
     * Vivaldi は同じページを 2 回追加したとき、別の id のショートカットを 2 つ作った（2026-11-22 に実機の記録で確認）。id では重複を見分けられない。
     */
    fun sameAs(
        apps: List<AppEntry>,
        packageName: String,
        label: String,
        newId: String,
    ): List<AppKey> = apps
        .filter { it.key.isShortcut && it.key.packageName == packageName && it.label == label && it.key.shortcutId != newId }
        .map { it.key }
}
