package dev.furunov.komalauncher.model.ports

import kotlinx.coroutines.flow.StateFlow

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

/**
 * 利用者が選んだフォルダ（pCloud などのクラウドのフォルダも選べる）へ、バックアップを自動で書く。
 * 書くのはホームが裏に回ったときで、前回から内容が変わったときだけ。
 */
interface AutoBackup {
    val state: StateFlow<AutoBackupState>

    /** 保存先のフォルダを決め、すぐに 1 回書く。null でオフにする。利用者に見せる文（失敗の理由）を返す。 */
    suspend fun setFolder(treeUri: String?): String?

    /** 内容が変わっていなくても、いま書く。 */
    suspend fun backupNow(): String?

    /** 前回から内容が変わっていれば書く。 */
    suspend fun backupIfChanged()
}

data class AutoBackupState(
    /** 保存先のフォルダの名前。オフなら null。 */
    val folder: String? = null,
    /** 最後に書けた時刻（エポックミリ秒）。 */
    val lastBackupAt: Long? = null,
    /** 最後に失敗したときの理由。次に書けたら null に戻る。 */
    val error: String? = null,
)
