package dev.furunov.komalauncher.platform.files

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import dev.furunov.komalauncher.model.layout.LayoutParser
import dev.furunov.komalauncher.model.layout.LayoutResult
import dev.furunov.komalauncher.model.layout.LayoutShape
import dev.furunov.komalauncher.model.ports.LayoutImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 利用者が選んだフォルダ（pCloud などのクラウドのフォルダも選べる）から、layout.json・画像・切り抜きを取り込み口へ写す。
 * 写した後の取り込みは FileLayoutStore が行う。layout.json を最後に書くと取り込みが始まる。
 *
 * フォルダの形は scripts/import-mockup.sh が読む形に合わせる。
 * - layout.json（任意）
 * - <名前>.(webp|jpg|jpeg|png): 画像。art/ の下にあってもよい
 * - mask/<名前>.(png|webp): 白が人物の切り抜き（BiRefNet などの出力）
 * - inv/<名前>.(png|webp): 白が人物以外の切り抜き（モックアップの INV）
 * - <名前>.mask.* と <名前>.inv.*: 取り込み口と同じ名前の切り抜き
 *
 * layout.json が無ければ、今のコマ割りを取り込み口に書き直し、参照している画像と切り抜きだけを補充する。
 * 機種変更で、バックアップからコマ割りを戻した後に、画像を入れ直す使い方のため。
 */
class SafFolderImporter(
    private val context: Context,
    private val importDirectory: File,
    /** いまのレイアウトの layout.json の文（見本なら null）。 */
    private val currentLayout: () -> String?,
) : LayoutImporter {
    private class Child(
        val id: String,
        val name: String,
        val isDirectory: Boolean,
    )

    override suspend fun importFolder(treeUri: String): String = withContext(Dispatchers.IO) {
        val tree = Uri.parse(treeUri)
        val resolver = context.contentResolver
        val files = HashMap<String, Uri>()
        var layoutUri: Uri? = null
        try {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            for (c in children(resolver, tree, rootId)) {
                when {
                    c.isDirectory -> {
                        val rename: ((String) -> String?)? =
                            when (c.name.lowercase()) {
                                "art" -> ::asIs
                                "mask" -> { name -> maskName(name, "mask") }
                                "inv" -> { name -> maskName(name, "inv") }
                                else -> null
                            }
                        if (rename != null) {
                            children(resolver, tree, c.id).filter { !it.isDirectory }.forEach { f ->
                                rename(f.name)?.let { files.putIfAbsent(it, DocumentsContract.buildDocumentUriUsingTree(tree, f.id)) }
                            }
                        }
                    }

                    c.name == LAYOUT -> layoutUri = DocumentsContract.buildDocumentUriUsingTree(tree, c.id)

                    else -> asIs(c.name)?.let { files.putIfAbsent(it, DocumentsContract.buildDocumentUriUsingTree(tree, c.id)) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "$tree の中を読めない", e)
            return@withContext "フォルダを読み込めません（${e.message ?: e::class.simpleName}）"
        }

        val text =
            try {
                layoutUri?.let { u -> resolver.openForRead(u)?.use { it.readBytes().decodeToString() } ?: return@withContext "layout.json を読み込めません" }
            } catch (e: Exception) {
                Log.w(TAG, "$layoutUri を読めない", e)
                return@withContext "layout.json を読み込めません"
            } ?: currentLayout() ?: return@withContext "フォルダに layout.json がありません。画像だけを補充するには、先にコマ割りを作成するか、バックアップから復元してください"
        val layout =
            when (val r = LayoutParser.parse(text)) {
                is LayoutResult.Ok -> r.layout
                is LayoutResult.Error -> return@withContext r.message
            }

        // 取り込み口の art/ に、ArtImporter が探す名前で写す
        val art = File(importDirectory, "art").apply { mkdirs() }
        var images = 0
        var masks = 0
        val notFound = ArrayList<String>()
        try {
            for (id in layout.images) {
                val image =
                    if (layout.shape is LayoutShape.Fixed) {
                        files.keys.firstOrNull { it == id }
                    } else {
                        IMAGE_EXTENSIONS.map { "$id.$it" }.firstOrNull { it in files }
                    }
                if (image != null) {
                    copy(resolver, files.getValue(image), File(art, image))
                    images++
                } else {
                    notFound.add(id)
                }
                if (layout.shape is LayoutShape.Tree) {
                    val mask = listOf("inv", "mask").flatMap { kind -> MASK_EXTENSIONS.map { "$id.$kind.$it" } }.firstOrNull { it in files }
                    if (mask != null) {
                        copy(resolver, files.getValue(mask), File(art, mask))
                        masks++
                    }
                }
            }
            // layout.json は最後に書く。書き終えたときに取り込みが始まり、そのとき画像が揃っているように
            File(importDirectory, LAYOUT).writeText(text)
        } catch (e: Exception) {
            Log.w(TAG, "$tree から写せない", e)
            return@withContext "ファイルをコピーできません（${e.message ?: e::class.simpleName}）"
        }

        buildString {
            append("画像 $images 枚、切り抜き $masks 枚を読み込みました")
            if (layoutUri == null) append("。コマ割りは変更していません")
            if (notFound.isNotEmpty()) append("。フォルダに無い画像: ${notFound.joinToString()}")
        }
    }

    private fun children(
        resolver: ContentResolver,
        tree: Uri,
        folderId: String,
    ): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        return resolver.query(uri, columns, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(Child(c.getString(0), c.getString(1), c.getString(2) == Document.MIME_TYPE_DIR)) }
        }.orEmpty()
    }

    private fun copy(
        resolver: ContentResolver,
        from: Uri,
        to: File,
    ) {
        val input = resolver.openForRead(from) ?: error("$from を開けない")
        input.use { i -> to.outputStream().use { i.copyTo(it) } }
    }

    private companion object {
        const val TAG = "SafFolderImporter"
        const val LAYOUT = "layout.json"
        val IMAGE_EXTENSIONS = listOf("webp", "jpg", "jpeg", "png")
        val MASK_EXTENSIONS = listOf("png", "webp")

        /** 画像か、取り込み口と同じ名前の切り抜き。拡張子は小文字にそろえる（ArtImporter は小文字で探す）。 */
        fun asIs(name: String): String? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return if (ext in IMAGE_EXTENSIONS) name.substringBeforeLast('.') + "." + ext else null
        }

        /** mask/ と inv/ の <名前>.* を、取り込み口の <名前>.mask.* と <名前>.inv.* にする。 */
        fun maskName(
            name: String,
            kind: String,
        ): String? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return if (ext in MASK_EXTENSIONS) name.substringBeforeLast('.') + ".$kind.$ext" else null
        }
    }
}
