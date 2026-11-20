package dev.furunov.komalauncher.model.ports

import kotlinx.coroutines.flow.StateFlow

/**
 * 設定画面から使う、ファイルの取り込みと書き出し。uri は Storage Access Framework で利用者が選んだもの。
 * 文字列で受け渡し、Android の Uri 型を設定画面のモジュールに出さない。
 */
interface LayoutImporter {
    /**
     * 利用者が選んだフォルダ（tree の URI）から、layout.json・画像・切り抜きを取り込む。
     * layout.json があればコマ割りごと置き換え、無ければ今のコマ割りが参照する画像と切り抜きだけを補充する。
     * 利用者に見せる文（結果か失敗の理由）を返す。
     */
    suspend fun importFolder(treeUri: String): String
}

interface BackupService {
    /** フォルダの中身・設定・layout.json・ウィジェットの提供元・起動回数を 1 つのファイルに書き出す。 */
    suspend fun export(uri: String): String?

    /** 書き出したファイルを読み込む。利用者に見せる文（失敗の理由や、ウィジェットの置き直しの案内）を返す。 */
    suspend fun import(uri: String): String?
}

/**
 * 利用者が選んだフォルダ（pCloud などのクラウドのフォルダも選べる）へ、バックアップを自動で書く。
 * 書くのはホームが裏に回ったときで、前回から内容が変わったときだけ。起動回数だけの変化は 1 日に 1 回にまとめる。
 */
interface AutoBackup {
    val state: StateFlow<AutoBackupState>

    /** フォルダ [treeUri] に既存のバックアップがあれば、その URI。設定画面が、上書きする前に復元するかを尋ねるのに使う。 */
    suspend fun existingBackup(treeUri: String): String?

    /**
     * 保存先のフォルダを決める。null でオフにする。利用者に見せる文（失敗の理由）を返す。
     * [writeNow] が true ならすぐに 1 回書く。false なら、次にホームが裏に回ったときに書く（復元した直後に使う）。
     * 上書きの前の内容は、日付ごとの世代として 7 つまで残す。
     */
    suspend fun setFolder(
        treeUri: String?,
        writeNow: Boolean = true,
    ): String?

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
