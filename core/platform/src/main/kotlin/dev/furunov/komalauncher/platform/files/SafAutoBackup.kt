package dev.furunov.komalauncher.platform.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import android.util.Log
import dev.furunov.komalauncher.model.ports.AutoBackup
import dev.furunov.komalauncher.model.ports.AutoBackupState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * 利用者が ACTION_OPEN_DOCUMENT_TREE で選んだフォルダへ、[SafBackupService.encode] の文を書く。
 * pCloud や Google ドライブのアプリが入っていれば、そのフォルダも選べる。アップロードはそれぞれのアプリが行う。
 *
 * 保存先の URI と最後に書いた内容のハッシュは noBackupFilesDir に置く。
 * URI の権限は端末ごとのものなので、バックアップのファイルにも、Android の自動バックアップにも含めない。
 */
class SafAutoBackup(
    private val context: Context,
    private val backup: SafBackupService,
) : AutoBackup {
    @Serializable
    private data class Saved(
        val treeUri: String? = null,
        val folder: String? = null,
        val lastBackupAt: Long? = null,
        val error: String? = null,
        /** 最後に書けた内容の SHA-256。同じなら書かない。 */
        val hash: String? = null,
    )

    private val file = AtomicFile(File(context.noBackupFilesDir, "auto-backup.json"))
    private val json = Json { ignoreUnknownKeys = true }
    private var saved = read()
    private val mutable = MutableStateFlow(saved.toState())
    override val state: StateFlow<AutoBackupState> = mutable.asStateFlow()

    // 設定画面の操作と、ホームが裏に回ったときの書き込みが重ならないようにする
    private val mutex = Mutex()

    override suspend fun setFolder(treeUri: String?): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val old = saved.treeUri
            if (treeUri == null) {
                old?.let(::release)
                save(Saved())
                return@withContext null
            }
            val uri = Uri.parse(treeUri)
            try {
                context.contentResolver.takePersistableUriPermission(uri, FLAGS)
            } catch (e: SecurityException) {
                Log.w(TAG, "$uri の権限を保持できない", e)
                return@withContext "このフォルダの権限を保持できません"
            }
            if (old != null && old != treeUri) release(old)
            save(Saved(treeUri = treeUri, folder = folderName(uri) ?: "選択したフォルダ"))
            write(backup.encode())
        }
    }

    override suspend fun backupNow(): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (saved.treeUri == null) "保存先のフォルダが未設定です" else write(backup.encode())
        }
    }

    override suspend fun backupIfChanged() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (saved.treeUri == null) return@withContext
                val text = backup.encode()
                // 前回失敗していれば、内容が同じでも書き直す
                if (saved.error == null && sha256(text) == saved.hash) return@withContext
                write(text)
            }
        }
    }

    /** 失敗の理由を返す。結果は [saved] に記録する。 */
    private fun write(text: String): String? {
        val tree = Uri.parse(saved.treeUri ?: return null)
        val error =
            try {
                val out = findOrCreate(tree)?.let { context.contentResolver.openOutputStream(it, "wt") }
                if (out == null) {
                    "保存先のファイルを作成できません"
                } else {
                    out.use { it.write(text.encodeToByteArray()) }
                    null
                }
            } catch (e: Exception) {
                // 権限の取り消し（SecurityException）や、クラウドのアプリの削除・ログアウトがここに来る
                Log.w(TAG, "$tree に書けない", e)
                "保存先に書き込めません（${e.javaClass.simpleName}）"
            }
        save(
            if (error == null) {
                saved.copy(lastBackupAt = System.currentTimeMillis(), error = null, hash = sha256(text))
            } else {
                saved.copy(error = error)
            },
        )
        return error
    }

    /** フォルダの直下の [FILE_NAME] を探し、無ければ作る。 */
    private fun findOrCreate(tree: Uri): Uri? {
        val resolver = context.contentResolver
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        // 多くのプロバイダは selection を無視するので、すべての子を読んで名前で比べる
        resolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == FILE_NAME) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
        }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        return DocumentsContract.createDocument(resolver, parent, "application/json", FILE_NAME)
    }

    private fun folderName(tree: Uri): String? = try {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "$tree の名前を読めない", e)
        null
    }

    private fun release(treeUri: String) {
        try {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(treeUri), FLAGS)
        } catch (e: SecurityException) {
            Log.w(TAG, "$treeUri の権限は既にない", e)
        }
    }

    private fun read(): Saved = try {
        if (file.baseFile.exists()) json.decodeFromString(Saved.serializer(), file.readFully().decodeToString()) else Saved()
    } catch (e: Exception) {
        // 壊れていたらオフで始める。利用者がもう一度フォルダを選べば直る
        Log.w(TAG, "${file.baseFile} を読めない", e)
        Saved()
    }

    private fun save(value: Saved) {
        saved = value
        mutable.value = value.toState()
        val out = file.startWrite()
        try {
            out.write(json.encodeToString(Saved.serializer(), value).encodeToByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.e(TAG, "${file.baseFile} に書けない", e)
        }
    }

    private fun Saved.toState() = AutoBackupState(folder = folder?.takeIf { treeUri != null }, lastBackupAt = lastBackupAt, error = error)

    private companion object {
        const val TAG = "SafAutoBackup"
        const val FILE_NAME = "koma-launcher-backup.json"
        const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
