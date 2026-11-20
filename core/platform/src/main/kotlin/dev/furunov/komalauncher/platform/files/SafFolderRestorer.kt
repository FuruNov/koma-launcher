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
import dev.furunov.komalauncher.model.ports.RestoreService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 利用者が選んだフォルダ（pCloud などのクラウドのフォルダも選べる）から復元する。
 * フォルダにあるものをすべて取り込む。どれも無くてよい。
 * - koma-launcher-backup.json: フォルダの中身・設定・起動回数・コマ割りを戻す（自動バックアップが書いたもの）
 * - layout.json: コマ割り。バックアップのコマ割りより優先する。取り込んだ後 layout.json.imported に名前を変え、1 回だけ適用する
 * - <名前>.(webp|jpg|jpeg|png): 画像。art/ の下にあってもよい
 * - mask/<名前>.(png|webp): 白が人物の切り抜き（BiRefNet などの出力）
 * - inv/<名前>.(png|webp): 白が人物以外の切り抜き（モックアップの INV）
 * - <名前>.mask.* と <名前>.inv.*: 取り込み口と同じ名前の切り抜き
 *
 * 画像と切り抜きは、最終的なコマ割りが参照する分だけを取り込み口へ写し、最後にコマ割りを取り込み口の layout.json に書く。
 * 書いた後の取り込みは FileLayoutStore が行う。
 */
class SafFolderRestorer(
    private val context: Context,
    private val importDirectory: File,
    private val backup: BackupContent,
    /** いまのレイアウトの layout.json の文（見本なら null）。 */
    private val currentLayout: () -> String?,
) : RestoreService {
    private class Child(
        val id: String,
        val name: String,
        val isDirectory: Boolean,
    )

    private class Listing {
        /** 取り込み口の art/ に置く名前 → 元のファイル。 */
        val files = HashMap<String, Uri>()
        var backup: Uri? = null
        var layout: Uri? = null
        var layoutImported: Uri? = null
    }

    override suspend fun restoreFolder(treeUri: String): String = withContext(Dispatchers.IO) {
        val tree = Uri.parse(treeUri)
        val resolver = context.contentResolver
        val listing =
            try {
                scan(resolver, tree)
            } catch (e: Exception) {
                Log.w(TAG, "$tree の中を読めない", e)
                return@withContext "フォルダを読み込めません（${e.message ?: e::class.simpleName}）"
            }

        // 読めるかを先にすべて確かめ、途中で失敗して一部だけ戻った状態にしない
        val parsed =
            listing.backup?.let { u ->
                val text = readText(resolver, u) ?: return@withContext "$BACKUP を読み込めません"
                try {
                    backup.parse(text)
                } catch (e: Exception) {
                    return@withContext "$BACKUP の形式が正しくありません: ${e.message?.lineSequence()?.firstOrNull()}"
                }
            }
        val design = listing.layout?.let { u -> readText(resolver, u) ?: return@withContext "$LAYOUT を読み込めません" }
        val text = design ?: parsed?.layout ?: currentLayout()
        val layout =
            text?.let {
                when (val r = LayoutParser.parse(it)) {
                    is LayoutResult.Ok -> r.layout
                    is LayoutResult.Error -> return@withContext if (design != null) "$LAYOUT: ${r.message}" else "$BACKUP のコマ割り: ${r.message}"
                }
            }
        if (parsed == null && design == null && listing.files.isEmpty()) {
            return@withContext "フォルダに $BACKUP・$LAYOUT・画像のいずれもありません"
        }

        parsed?.let(backup::apply)

        var images = 0
        var masks = 0
        val notFound = ArrayList<String>()
        if (layout != null && text != null) {
            val art = File(importDirectory, "art").apply { mkdirs() }
            try {
                for (id in layout.images) {
                    val image =
                        if (layout.shape is LayoutShape.Fixed) {
                            listing.files.keys.firstOrNull { it == id }
                        } else {
                            IMAGE_EXTENSIONS.map { "$id.$it" }.firstOrNull { it in listing.files }
                        }
                    if (image != null) {
                        copy(resolver, listing.files.getValue(image), File(art, image))
                        images++
                    } else {
                        notFound.add(id)
                    }
                    if (layout.shape is LayoutShape.Tree) {
                        val mask = listOf("inv", "mask").flatMap { kind -> MASK_EXTENSIONS.map { "$id.$kind.$it" } }.firstOrNull { it in listing.files }
                        if (mask != null) {
                            copy(resolver, listing.files.getValue(mask), File(art, mask))
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
        }

        val renamed = listing.layout?.let { markImported(resolver, it, listing.layoutImported) } ?: true

        buildList {
            if (parsed != null) add("設定を復元しました")
            if (design != null) add("$LAYOUT のコマ割りを適用しました")
            add("画像 $images 枚、切り抜き $masks 枚を読み込みました")
            if (notFound.isNotEmpty()) add("フォルダに無い画像: ${notFound.joinToString()}")
            // ウィジェットの配置し直しには許可の画面が要るので、ここでは行わず、どのコマに何があったかを知らせる
            if ((parsed?.widgetCount ?: 0) > 0) add("ウィジェットはコマを長押しして再配置してください（${parsed?.widgetCount} 個）")
            if (!renamed) add("$LAYOUT の名前を変更できなかったため、次の復元でも適用されます")
        }.joinToString("。")
    }

    /** 適用した layout.json を layout.json.imported に名前を変える。前の .imported があれば先に消す。 */
    private fun markImported(
        resolver: ContentResolver,
        layout: Uri,
        previous: Uri?,
    ): Boolean = try {
        previous?.let { DocumentsContract.deleteDocument(resolver, it) }
        DocumentsContract.renameDocument(resolver, layout, "$LAYOUT.imported") != null
    } catch (e: Exception) {
        Log.w(TAG, "$layout の名前を変えられない", e)
        false
    }

    private fun scan(
        resolver: ContentResolver,
        tree: Uri,
    ): Listing {
        val listing = Listing()
        fun uriOf(c: Child) = DocumentsContract.buildDocumentUriUsingTree(tree, c.id)
        for (c in children(resolver, tree, DocumentsContract.getTreeDocumentId(tree))) {
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
                            rename(f.name)?.let { listing.files.putIfAbsent(it, uriOf(f)) }
                        }
                    }
                }

                c.name == BACKUP -> listing.backup = uriOf(c)

                c.name == LAYOUT -> listing.layout = uriOf(c)

                c.name == "$LAYOUT.imported" -> listing.layoutImported = uriOf(c)

                else -> asIs(c.name)?.let { listing.files.putIfAbsent(it, uriOf(c)) }
            }
        }
        return listing
    }

    /**
     * フォルダの直下の一覧。クラウドのプロバイダは、一覧をネットワークから取る間、EXTRA_LOADING を付けた途中の一覧を返すことがある。
     * そのときは少し待って読み直す。
     */
    private fun children(
        resolver: ContentResolver,
        tree: Uri,
        folderId: String,
    ): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        for (attempt in 1..LOADING_TRIES) {
            val (loading, list) =
                resolver.query(uri, columns, null, null, null)?.use { c ->
                    val list = buildList { while (c.moveToNext()) add(Child(c.getString(0), c.getString(1), c.getString(2) == Document.MIME_TYPE_DIR)) }
                    (c.extras?.getBoolean(DocumentsContract.EXTRA_LOADING) == true) to list
                } ?: return emptyList()
            if (!loading || attempt == LOADING_TRIES) return list
            Thread.sleep(LOADING_WAIT_MS)
        }
        return emptyList()
    }

    private fun readText(
        resolver: ContentResolver,
        uri: Uri,
    ): String? = try {
        resolver.openForRead(uri)?.use { it.readBytes().decodeToString() }
    } catch (e: Exception) {
        Log.w(TAG, "$uri を読めない", e)
        null
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
        const val TAG = "SafFolderRestorer"
        const val BACKUP = "koma-launcher-backup.json"
        const val LAYOUT = "layout.json"
        const val LOADING_TRIES = 20
        const val LOADING_WAIT_MS = 500L
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
