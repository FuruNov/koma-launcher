package dev.furunov.komalauncher.model.ports

/**
 * 設定画面から使う、ファイルの取り込みと書き出し。uri は Storage Access Framework で利用者が選んだもの。
 * 文字列で受け渡し、Android の Uri 型を設定画面のモジュールに出さない。
 */
interface LayoutImporter {
    /** 画像と layout.json を取り込む。どちらかだけでもよい。利用者に見せる文（失敗の理由）を返し、何もなければ null。 */
    suspend fun import(
        imageUri: String?,
        layoutUri: String?,
    ): String?
}

interface BackupService {
    /** フォルダの中身・設定・layout.json・ウィジェットの提供元を 1 つのファイルに書き出す。 */
    suspend fun export(uri: String): String?

    /** 書き出したファイルを読み込む。利用者に見せる文（失敗の理由や、ウィジェットの置き直しの案内）を返す。 */
    suspend fun import(uri: String): String?
}
