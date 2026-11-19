package dev.furunov.komalauncher.platform.store

import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 小さな JSON ファイルに 1 つの値を保存する。フォルダの中身と設定で共有する。
 *
 * - Room や DataStore を使わないのは、依存とメモリを増やさないため。保存する値は数 KB で、
 *   全体をメモリに持って丸ごと書き直せば足りる。
 * - AtomicFile で書くので、書き込みの途中で強制終了しても前の版が残る。
 * - ドラッグで並べ替えると短い間に何度も変わるので、[debounceMs] の間まとめてから書く。
 */
@OptIn(FlowPreview::class)
class JsonFileStore<T>(
    file: File,
    private val serializer: KSerializer<T>,
    default: () -> T,
    scope: CoroutineScope,
    debounceMs: Long = 300,
) {
    private val atomic = AtomicFile(file)
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val state = MutableStateFlow(read() ?: default())
    val value: StateFlow<T> = state

    init {
        state
            .drop(1)
            .debounce(debounceMs)
            .onEach { write(it) }
            .launchIn(CoroutineScope(scope.coroutineContext + Dispatchers.IO))
    }

    fun update(transform: (T) -> T) = state.update(transform)

    private fun read(): T? = try {
        if (!atomic.baseFile.exists()) {
            null
        } else {
            json.decodeFromString(serializer, atomic.readFully().decodeToString())
        }
    } catch (e: Exception) {
        // 読めないファイルは消さずに退避する。次の書き込みで上書きされて手掛かりが消えないように
        Log.w(TAG, "${atomic.baseFile} を読めないので既定値で始める", e)
        atomic.baseFile.copyTo(File(atomic.baseFile.path + ".broken"), overwrite = true)
        null
    }

    private fun write(value: T) {
        val out = atomic.startWrite()
        try {
            out.write(json.encodeToString(serializer, value).encodeToByteArray())
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            Log.e(TAG, "${atomic.baseFile} に書けない", e)
        }
    }

    private companion object {
        const val TAG = "JsonFileStore"
    }
}
