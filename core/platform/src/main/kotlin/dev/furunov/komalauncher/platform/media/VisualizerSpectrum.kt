package dev.furunov.komalauncher.platform.media

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.util.Log
import dev.furunov.komalauncher.model.media.AudioSpectrum
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.log10

/**
 * 端末が出している音全体（セッション 0）の周波数を Visualizer で読む。録音の権限が要る。
 * 端末や音楽アプリによっては無音しか返らないので、その判断は画面側（5 秒続けて無音なら切り替える）に任せる。
 * アプリで 1 つだけ作り（AppContainer）、取得の状態を [status] で設定画面に見せる。
 */
class VisualizerSpectrum(
    context: Context,
) : AudioSpectrum {
    private val audio = context.getSystemService(AudioManager::class.java)

    private var visualizer: Visualizer? = null

    /**
     * [start] に成功して、まだ [stop] していない呼び出し元の数。0 になったときだけ解放する。
     * 一時停止から再生に戻ると、画面側の新しい処理が [start] した後で、止まりきっていなかった古い処理が [stop] を呼ぶ。
     * 数えずに解放すると、新しい処理が解放済みの Visualizer を読み続け、棒が音に反応しなくなる。
     * 画面のスレッドからだけ呼ぶ。
     */
    private var users = 0

    /** 取得するセッション。0 は端末全体の音。実験で、再生中のアプリのセッションに付け替える。 */
    @Volatile
    var session: Int = 0

    /** この時刻（elapsedRealtime）まで、読んだ値を 0.1 秒ごとにログへ書く。棒の見え方を調べるための debug 版の実験用。 */
    @Volatile
    var logUntil: Long = 0L
    private var lastLog = 0L

    private val statusFlow = MutableStateFlow<String?>(null)

    /**
     * 最後に分かった取得の状態（開始できたか、音を受け取っているか、無音だけか）。まだ一度も開始していなければ null。
     * 端末によって取得できないことがあり、Release の APK では adb のログを見られないので、設定画面に表示して原因を確かめる。
     */
    val status: StateFlow<String?> = statusFlow

    /** 直前に読んだ値が無音だったか。状態が変わったときだけ [status] を書き換える（毎フレーム書かないように）。 */
    private var lastSilent: Boolean? = null

    /** 読んだ値を入れる配列。大きさは Visualizer の取得の大きさ。 */
    private var buffer = ByteArray(CAPTURE_SIZE)

    override fun start(): Boolean {
        if (visualizer != null) {
            users++
            return true
        }
        var created: Visualizer? = null
        return try {
            val v = Visualizer(session)
            created = v
            // 同じセッションの Visualizer は中身を共有する。ほかのアプリ（HyperOS の音楽の表示など）がすでに有効にしていると、
            // 取得の大きさを変えられない（変えようとすると例外になる）ので、その大きさのまま読む
            if (!v.enabled) {
                v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(CAPTURE_SIZE)
                v.enabled = true
            }
            buffer = ByteArray(v.captureSize)
            visualizer = v
            users = 1
            // 無音が続いて作り直したときは、無音が始まった時刻を残すため、表示を変えない
            if (lastSilent == null) report("開始しました（まだ音を受け取っていません）")
            true
        } catch (e: Exception) {
            // 権限が無い（SecurityException）、端末が対応していない（RuntimeException）など。作ったものは解放する（残すと有効のまま残り、次も失敗する）
            created?.release()
            report("開始できません（${e.javaClass.simpleName}: ${e.message}）")
            false
        }
    }

    override fun stop() {
        if (users > 0) users--
        if (users > 0) return
        visualizer?.let {
            // ほかのアプリが有効にした Visualizer は、こちらから無効にできないことがある
            runCatching { it.enabled = false }
            it.release()
        }
        visualizer = null
    }

    // Visualizer の samplingRate はミリヘルツ
    override val sampleRateHz: Int get() = visualizer?.samplingRate?.div(1000) ?: 48_000

    override fun read(): ByteArray? {
        val v = visualizer ?: return null
        val out = buffer
        return try {
            val code = v.getFft(out)
            if (code != Visualizer.SUCCESS) {
                if (lastSilent != null) report("読み取りに失敗しました（コード $code）")
                lastSilent = null
                return null
            }
            logOctaves(v, out)
            val silent = out.all { it == 0.toByte() }
            if (silent != lastSilent) {
                lastSilent = silent
                report(if (silent) "無音だけが返っています（${now()} から）${spatialHint()}" else "音を受け取っています（${now()} に確認）")
            }
            out
        } catch (e: IllegalStateException) {
            report("読み取りに失敗しました（${e.message}）")
            null
        }
    }

    /**
     * 空間オーディオ（HyperOS ではイマーシブサウンド）がオンだと、音楽の音は専用の経路を通り、セッション 0 の Visualizer に届かない
     * （2026-11-20 に実機で確かめた。オフにすると取得できた）。無音の理由として示す。
     */
    private fun spatialHint(): String {
        val s = audio?.spatializer ?: return ""
        return if (s.isAvailable && s.isEnabled) "。イマーシブサウンド（空間オーディオ）がオンのときは取得できません" else ""
    }

    /** 1 オクターブごとの平均の大きさ（dB）を書く。 */
    private fun logOctaves(
        v: Visualizer,
        fft: ByteArray,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now > logUntil || now - lastLog < 100) return
        lastLog = now
        val nyquist = v.samplingRate / 2000.0
        val bins = fft.size / 2
        val db =
            generateSequence(62.5) { it * 2 }.takeWhile { it <= 16000 }.map { hz ->
                val lo = (hz / 2 / nyquist * bins).toInt().coerceIn(1, bins - 1)
                val hi = (hz / nyquist * bins).toInt().coerceIn(lo + 1, bins)
                var sum = 0.0
                for (k in lo until hi) sum += fft[2 * k].toDouble() * fft[2 * k] + fft[2 * k + 1].toDouble() * fft[2 * k + 1]
                (10 * log10(1e-9 + sum / (hi - lo))).toInt()
            }.joinToString(" ")
        Log.i(TAG, "帯域dB(62〜16k)=$db")
    }

    private fun report(s: String) {
        statusFlow.value = s
        Log.i(TAG, s)
    }

    private fun now(): String = LocalTime.now().format(TIME)

    private companion object {
        const val CAPTURE_SIZE = 1024
        const val TAG = "KomaVisualizer"
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
