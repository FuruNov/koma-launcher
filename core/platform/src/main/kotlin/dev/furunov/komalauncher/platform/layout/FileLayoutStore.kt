package dev.furunov.komalauncher.platform.layout

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.FileObserver
import android.util.Log
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.LayoutParser
import dev.furunov.komalauncher.model.layout.LayoutResult
import dev.furunov.komalauncher.model.layout.PanelArt
import dev.furunov.komalauncher.model.ports.LayoutState
import dev.furunov.komalauncher.model.ports.LayoutStore
import dev.furunov.komalauncher.platform.store.JsonFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.nullable
import java.io.File

/**
 * レイアウトはアプリの内部（files/layout.json と files/art/）に持つ。段階 2 の編集画面はここを書き換える。
 *
 * 外部ファイル領域（/sdcard/Android/data/<pkg>/files/）の layout.json は取り込み口にする。
 * adb push・設定画面の取り込み・バックアップの読み込みでここに置かれたら、確かめてから画像を内部へ写し、
 * 内部のレイアウトを置き換える。取り込んだファイルは layout.json.imported に名前を変える
 * （残しておくと、次の起動で編集の結果を上書きするため）。
 */
class FileLayoutStore(
    private val context: Context,
    private val scope: CoroutineScope,
) : LayoutStore {
    /** 取り込み口。 */
    val importDirectory: File = requireNotNull(context.getExternalFilesDir(null)) { "外部ファイル領域を使えない" }
    private val importFile = File(importDirectory, FILE_NAME)
    private val art = ArtImporter(File(context.filesDir, "art"))
    private val store = JsonFileStore(File(context.filesDir, FILE_NAME), LayoutParser.serializer.nullable, { null }, scope)
    private val importError = MutableStateFlow<String?>(null)
    private val revision = MutableStateFlow(0L)
    private val importLock = Mutex()

    private val sample: Layout by lazy {
        val text = context.assets.open("sample/layout.json").bufferedReader().use { it.readText() }
        (LayoutParser.parse(text) as LayoutResult.Ok).layout
    }

    /** 読めずに見本で始めたとき、退避したファイル。 */
    val brokenFile: File? get() = store.brokenFile

    override val state: StateFlow<LayoutState> =
        combine(store.value, importError, revision) { layout, error, rev -> stateOf(layout, error, rev) }
            // 画像のファイルの有無を調べるので、IO のスレッドで計算する
            .flowOn(Dispatchers.IO)
            .stateIn(scope, SharingStarted.Eagerly, stateOf(store.value.value, null, 0L))

    // FileObserver は参照が切れると GC で止まるので、フィールドで持ち続ける
    @Suppress("unused")
    private val observer =
        object : FileObserver(importDirectory, CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(
                event: Int,
                path: String?,
            ) {
                if (path == FILE_NAME) importSoon()
            }
        }.also { it.startWatching() }

    init {
        // アプリが止まっている間に置かれた layout.json も取り込む
        if (importFile.exists()) importSoon()
    }

    override fun update(transform: (Layout) -> Layout) = store.update { transform(it ?: sample) }

    override suspend fun addImage(uri: String): PanelArt? = withContext(Dispatchers.IO) {
        art.add(ImageDecoder.createSource(context.contentResolver, Uri.parse(uri)), uri)
    }

    override fun pruneImages() {
        val layout = store.value.value ?: return
        scope.launch(Dispatchers.IO) { importLock.withLock { art.prune(layout) } }
    }

    /** いまのレイアウトの layout.json の文。バックアップに使う。見本を表示しているときは null。 */
    fun currentText(): String? = store.value.value?.let(LayoutParser::encode)

    fun flush() = store.flush()

    private fun stateOf(
        layout: Layout?,
        error: String?,
        rev: Long,
    ): LayoutState {
        if (layout == null) {
            val broken = store.brokenFile?.let { "保存したレイアウトを読めなかったので、見本を表示しています（${it.name} に残してあります）" }
            return LayoutState.Fallback(sample, listOfNotNull(broken, error).joinToString("\n").ifEmpty { null })
        }
        return LayoutState.Loaded(layout, art.existing(layout), error, rev)
    }

    private fun importSoon() {
        // adb push は書き込みを細かく分けることがあるので、少し待ってから読む
        scope.launch(Dispatchers.IO) {
            delay(200)
            importLock.withLock { importNow() }
        }
    }

    private fun importNow() {
        if (!importFile.exists()) return
        val text =
            try {
                importFile.readText()
            } catch (e: Exception) {
                Log.w(TAG, "$importFile を読めない", e)
                return
            }
        val parsed =
            when (val r = LayoutParser.parse(text)) {
                is LayoutResult.Ok -> r.layout

                is LayoutResult.Error -> {
                    // 内部のレイアウトはそのまま。直した layout.json を置き直せば取り込み直す
                    importError.value = "layout.json を取り込めませんでした。前のレイアウトのままです: ${r.message}"
                    return
                }
            }
        val result = art.import(parsed, importDirectory)
        store.update { result.layout }
        store.flush()
        markImported()
        // 写し終えた元の画像は消す。取り込みのために置いたもので、大きいため
        result.consumed.forEach { it.delete() }
        revision.value++
        importError.value = result.missing.takeIf { it.isNotEmpty() }?.let { "画像が見つかりません: ${it.joinToString()}（art/ に置いてください）" }
    }

    /**
     * 取り込んだ外部の layout.json を .imported に名前を変える。残っていると、次の起動で同じファイルを取り込み直し、
     * 編集の結果を上書きするため、名前を変えられなければ消す。
     */
    private fun markImported() {
        val done = File(importDirectory, "$FILE_NAME.imported")
        done.delete()
        if (importFile.renameTo(done)) return
        Log.w(TAG, "$importFile の名前を変えられないので消す")
        if (!importFile.delete()) Log.e(TAG, "$importFile を消せない。次の起動で取り込み直す")
    }

    private companion object {
        const val TAG = "FileLayoutStore"
        const val FILE_NAME = "layout.json"
    }
}
