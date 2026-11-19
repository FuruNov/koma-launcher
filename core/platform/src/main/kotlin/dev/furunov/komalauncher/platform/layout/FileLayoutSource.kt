package dev.furunov.komalauncher.platform.layout

import android.content.Context
import android.os.FileObserver
import dev.furunov.komalauncher.model.layout.LayoutParser
import dev.furunov.komalauncher.model.layout.LayoutResult
import dev.furunov.komalauncher.model.ports.LayoutSource
import dev.furunov.komalauncher.model.ports.LayoutState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 端末の外部ファイル領域（/sdcard/Android/data/<pkg>/files/）の layout.json を読む。
 * adb push で置き換えたら、再起動せずに読み直す。無いか壊れていれば assets の見本を使う。
 */
class FileLayoutSource(
    private val context: Context,
    private val scope: CoroutineScope,
) : LayoutSource {
    val directory: File = requireNotNull(context.getExternalFilesDir(null)) { "外部ファイル領域を使えない" }
    private val file = File(directory, FILE_NAME)
    private val sample: LayoutState.Fallback by lazy {
        val text = context.assets.open("sample/layout.json").bufferedReader().use { it.readText() }
        val layout = (LayoutParser.parse(text) as LayoutResult.Ok).layout
        LayoutState.Fallback(layout, error = null)
    }
    private val mutable = MutableStateFlow<LayoutState>(load())
    override val state: StateFlow<LayoutState> = mutable

    // FileObserver は参照が切れると GC で止まるので、フィールドで持ち続ける
    @Suppress("unused")
    private val observer =
        object : FileObserver(directory, CLOSE_WRITE or MOVED_TO or DELETE) {
            override fun onEvent(
                event: Int,
                path: String?,
            ) {
                if (path == FILE_NAME || path?.endsWith(".webp") == true || path?.endsWith(".png") == true) reloadSoon()
            }
        }.also { it.startWatching() }

    private fun reloadSoon() {
        // adb push は書き込みを細かく分けることがあるので、少し待ってから読む
        scope.launch(Dispatchers.IO) {
            delay(200)
            mutable.value = load()
        }
    }

    private fun load(): LayoutState {
        if (!file.exists()) return sample
        return when (val r = LayoutParser.parse(file.readText())) {
            is LayoutResult.Ok -> {
                val image = r.layout.image?.let { File(directory, it) }?.takeIf { it.exists() }
                LayoutState.Loaded(r.layout, image?.absolutePath)
            }

            is LayoutResult.Error -> sample.copy(error = r.message)
        }
    }

    private companion object {
        const val FILE_NAME = "layout.json"
    }
}
