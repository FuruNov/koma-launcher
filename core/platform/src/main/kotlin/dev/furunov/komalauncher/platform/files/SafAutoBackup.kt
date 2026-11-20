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
import java.time.LocalDate

/**
 * 利用者が ACTION_OPEN_DOCUMENT_TREE で選んだフォルダへ、[BackupContent.encode] の文を書く。
 * pCloud や Google ドライブのアプリが入っていれば、そのフォルダも選べる。アップロードはそれぞれのアプリが行う。
 *
 * 保存先の URI と最後に書いた内容のハッシュは noBackupFilesDir に置く。
 * URI の権限は端末ごとのものなので、バックアップのファイルにも、Android の自動バックアップにも含めない。
 */
class SafAutoBackup(
    private val context: Context,
    private val backup: BackupContent,
) : AutoBackup {
    @Serializable
    private data class Saved(
        val treeUri: String? = null,
        val folder: String? = null,
        val lastBackupAt: Long? = null,
        val error: String? = null,
        /** 最後に書けた内容の SHA-256。同じなら書かない。 */
        val hash: String? = null,
        /** 起動回数を除いた内容の SHA-256。起動回数だけの変化と、それ以外の変化を見分ける。 */
        val coreHash: String? = null,
    )

    private val file = AtomicFile(File(context.noBackupFilesDir, "auto-backup.json"))
    private val json = Json { ignoreUnknownKeys = true }
    private var saved = read()
    private val mutable = MutableStateFlow(saved.toState())
    override val state: StateFlow<AutoBackupState> = mutable.asStateFlow()

    // 設定画面の操作と、ホームが裏に回ったときの書き込みが重ならないようにする
    private val mutex = Mutex()

    override suspend fun existingBackup(treeUri: String): String? = withContext(Dispatchers.IO) {
        try {
            list(Uri.parse(treeUri))[FILE_NAME]?.toString()
        } catch (e: Exception) {
            Log.w(TAG, "$treeUri の中を読めない", e)
            null
        }
    }

    override suspend fun setFolder(
        treeUri: String?,
        writeNow: Boolean,
    ): String? = mutex.withLock {
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
            // hash が空なので、すぐに書かなくても、次にホームが裏に回ったときに書く
            save(Saved(treeUri = treeUri, folder = folderName(uri) ?: "選択したフォルダ"))
            if (writeNow) writeCurrent() else null
        }
    }

    override suspend fun backupNow(): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (saved.treeUri == null) "保存先のフォルダが未設定です" else writeCurrent()
        }
    }

    override suspend fun backupIfChanged() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (saved.treeUri == null) return@withContext
                val text = backup.encode()
                val core = backup.encode(withLaunches = false)
                // 起動回数はアプリを起動するたびに変わる。それだけの変化なら、書くのは 1 日に 1 回にする
                val launchesDue = sha256(text) != saved.hash && System.currentTimeMillis() - (saved.lastBackupAt ?: 0L) > DAY_MS
                // 前回失敗していれば、内容が同じでも書き直す
                if (saved.error == null && sha256(core) == saved.coreHash && !launchesDue) return@withContext
                write(text, core)
            }
        }
    }

    private fun writeCurrent(): String? = write(backup.encode(), backup.encode(withLaunches = false))

    /** [text] を書く。[core] は起動回数を除いた内容。失敗の理由を返す。結果は [saved] に記録する。 */
    private fun write(
        text: String,
        core: String,
    ): String? {
        val tree = Uri.parse(saved.treeUri ?: return null)
        val error =
            try {
                val files = list(tree)
                files[FILE_NAME]?.let { keepGeneration(tree, it, files) }
                val out = (files[FILE_NAME] ?: create(tree, FILE_NAME))?.let { context.contentResolver.openOutputStream(it, "wt") }
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
                saved.copy(lastBackupAt = System.currentTimeMillis(), error = null, hash = sha256(text), coreHash = sha256(core))
            } else {
                saved.copy(error = error)
            },
        )
        return error
    }

    /**
     * 上書きの前の内容を、その日の世代（koma-launcher-backup-<日付>.json）に写す。その日の世代が既にあれば写さない。
     * 誤って消した状態を書いてしまっても、前日までの内容を戻せるように。世代は新しい [GENERATIONS] 個を残す。
     * 世代を書けなくても、本体の書き込みは続ける。
     */
    private fun keepGeneration(
        tree: Uri,
        current: Uri,
        files: Map<String, Uri>,
    ) {
        val resolver = context.contentResolver
        val name = "koma-launcher-backup-${LocalDate.now()}.json"
        try {
            if (name !in files) {
                val bytes = resolver.openForRead(current)?.use { it.readBytes() } ?: return
                create(tree, name)?.let { doc -> resolver.openOutputStream(doc, "wt")?.use { it.write(bytes) } }
            }
        } catch (e: Exception) {
            Log.w(TAG, "$name を書けない", e)
            return
        }
        (files.keys + name).filter { GENERATION.matches(it) }.sortedDescending().drop(GENERATIONS).forEach { old ->
            try {
                files[old]?.let { DocumentsContract.deleteDocument(resolver, it) }
            } catch (e: Exception) {
                Log.w(TAG, "$old を消せない", e)
            }
        }
    }

    /** フォルダの直下のファイルの名前 → URI。多くのプロバイダは selection を無視するので、すべての子を読む。 */
    private fun list(tree: Uri): Map<String, Uri> {
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return context.contentResolver.query(children, columns, null, null, null)?.use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(1), DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))) }
        }.orEmpty()
    }

    private fun create(
        tree: Uri,
        name: String,
    ): Uri? {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return DocumentsContract.createDocument(context.contentResolver, parent, "application/json", name)
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
        const val GENERATIONS = 7
        const val DAY_MS = 24 * 60 * 60 * 1000L
        val GENERATION = Regex("""koma-launcher-backup-\d{4}-\d{2}-\d{2}\.json""")
        const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
