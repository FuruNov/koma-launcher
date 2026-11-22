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
 * - AtomicFile で書くので、書き込みの途中で強制終了しても前のバージョンが残る。
 * - ドラッグで並べ替えると短い間に何度も変わるので、[debounceMs] の間まとめてから書く。
 *   まとめている間にプロセスが止まると失われるので、ホームが裏に回ったら [flush] ですぐ書く。
 * - 読めないファイルは .broken に退避し、デフォルト値で始めたことを [brokenFile] で知らせる。
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

    /** 読めずにデフォルト値で始めたとき、退避したファイル。画面で知らせるために使う。 */
    var brokenFile: File? = null
        private set
    private val state = MutableStateFlow(read() ?: default())
    val value: StateFlow<T> = state

    // 最後に書いた値。flush で書く必要があるかを見分ける。書き込みは IO のコルーチンと flush の両方から来るので、lock で順番にする
    private val lock = Any()
    private var written: T = state.value

    init {
        state
            .drop(1)
            .debounce(debounceMs)
            .onEach { write(it) }
            .launchIn(CoroutineScope(scope.coroutineContext + Dispatchers.IO))
    }

    fun update(transform: (T) -> T) = state.update(transform)

    /** まとめている途中の変更を、いま書く。呼び出し元のスレッドで書く（数 KB なので短い）。 */
    fun flush() = write(state.value)

    private fun read(): T? = try {
        if (!atomic.baseFile.exists()) {
            null
        } else {
            json.decodeFromString(serializer, atomic.readFully().decodeToString())
        }
    } catch (e: Exception) {
        // 読めないファイルは消さずに退避する。次の書き込みで上書きされて手掛かりが消えないように
        Log.w(TAG, "${atomic.baseFile} を読めないので既定値で始める", e)
        val broken = File(atomic.baseFile.path + ".broken")
        atomic.baseFile.copyTo(broken, overwrite = true)
        brokenFile = broken
        null
    }

    private fun write(value: T) {
        synchronized(lock) {
            if (value === written) return
            val out = atomic.startWrite()
            try {
                out.write(json.encodeToString(serializer, value).encodeToByteArray())
                atomic.finishWrite(out)
                written = value
            } catch (e: Exception) {
                atomic.failWrite(out)
                Log.e(TAG, "${atomic.baseFile} に書けない", e)
            }
        }
    }

    private companion object {
        const val TAG = "JsonFileStore"
    }
}
