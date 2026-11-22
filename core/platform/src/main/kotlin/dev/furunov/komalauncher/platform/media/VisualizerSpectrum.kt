package dev.furunov.komalauncher.platform.media

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.util.Log
import dev.furunov.komalauncher.model.media.AudioSpectrum
import dev.furunov.komalauncher.model.media.PowerSpectrum
import dev.furunov.komalauncher.model.media.SpectrumRecording
import dev.furunov.komalauncher.model.media.SpectrumTuning
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

    /**
     * 取得の倍率の決め方。NORMALIZED は、オーディオの処理単位（数〜20ms）ごとに最大の標本が 0.99 になるよう倍率をかける
     * （AOSP の EffectVisualizer.cpp で確認した）。AS_PLAYED は音量をかけた後の音をそのまま 8 ビットにするので、値がメディアの音量に比例する。
     * 実機（2026-11-21、YT Music）では、AS_PLAYED は音量 2/30 で音楽の中身が 8 ビットの 1 段階より小さくなって消え、10/30 でも
     * 8kHz より上が雑音の大きさ（-70dB ほど）まで下がった。全帯域がそろって上下する量は、NORMALIZED と比べて 1 割ほどしか減らなかったので、
     * NORMALIZED を使う。debug ビルドの実験で切り替えると、すぐに反映する。
     */
    var scalingMode: Int = Visualizer.SCALING_MODE_NORMALIZED
        set(value) {
            field = value
            visualizer?.let { scalingResult = runCatching { it.setScalingMode(value) }.getOrDefault(Visualizer.ERROR) }
            Log.i(TAG, "倍率の決め方を $value にした（結果 $scalingResult）")
        }

    /** この時刻（elapsedRealtime）まで、読んだ値を 0.1 秒ごとにログへ書く。棒の見え方を調べるための debug ビルドの実験用。 */
    @Volatile
    var logUntil: Long = 0L

    /** ログを書く間隔（ミリ秒）。 */
    @Volatile
    var logIntervalMs: Long = 100L
    private var lastLog = 0L

    private val statusFlow = MutableStateFlow<String?>(null)

    /**
     * 最後に分かった取得の状態（開始できたか、音を受け取っているか、無音だけか）。まだ一度も開始していなければ null。
     * 端末によって取得できないことがあり、Release の APK では adb のログを見られないので、設定画面に表示して原因を確認する。
     */
    val status: StateFlow<String?> = statusFlow

    /** 直前に読んだ値が無音だったか。状態が変わったときだけ [status] を書き換える（毎フレーム書かないように）。 */
    private var lastSilent: Boolean? = null

    /** 最後の setScalingMode の戻り値。失敗したら状態の表示に添える。 */
    private var scalingResult = Visualizer.SUCCESS

    /** 読んだ波形を入れる配列。大きさは Visualizer の取得の大きさ。 */
    private var buffer = ByteArray(CAPTURE_SIZE)

    /** 波形から計算した帯域ごとのパワー。長さは [buffer] の半分。 */
    private var power = FloatArray(CAPTURE_SIZE / 2)

    private val powerSpectrum = PowerSpectrum()

    /**
     * NORMALIZED は、曲間やフェードアウトのごく小さい音も最大まで大きくするので、波形からは無音に近いことが分からない。
     * そこで、正規化の前の RMS（MEASUREMENT_MODE_PEAK_RMS、最大 25 処理単位の平均）を計測し、[SpectrumTuning.isQuiet] で判断する。
     * 計測を有効にできなければ、この判断はしない。
     */
    private var measuring = false
    private val measurement = Visualizer.MeasurementPeakRms()
    private val tuning = SpectrumTuning()

    /** 直前に計測した、音量の分を戻した RMS（dB）。ログに書く。 */
    private var levelDb = Float.NaN

    /** メディアの音量（dB、0 以下）。毎フレーム問い合わせないよう、[VOLUME_CHECK_MS] ごとに取得し直す。 */
    private var volumeDb = 0f
    private var volumeCheckedAt = 0L

    /**
     * 読んだ生の値（波形、RMS、音量）を書く先。debug ビルドの実験で、記録を PC で再生して棒の計算を比べるために使う（tools/vis-replay）。
     * 画面のスレッドからだけ書き換える。
     */
    var recorder: SpectrumRecording.Writer? = null

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
            // ほかのアプリが制御している Visualizer では変えられないことがある。そのときは状態に表示する
            scalingResult = v.setScalingMode(scalingMode)
            measuring = runCatching { v.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS) == Visualizer.SUCCESS }.getOrDefault(false)
            Log.i(TAG, "RMS の計測: ${if (measuring) "有効" else "無効"}")
            buffer = ByteArray(v.captureSize)
            power = FloatArray(v.captureSize / 2)
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

    override fun read(): FloatArray? {
        val v = visualizer ?: return null
        val wave = buffer
        return try {
            // getFft は使わない。窓関数が無く、127 を超えた値を半分にするので（[PowerSpectrum]）、波形から自分で計算する
            val code = v.getWaveForm(wave)
            if (code != Visualizer.SUCCESS) {
                if (lastSilent != null) report("読み取りに失敗しました（コード $code）")
                lastSilent = null
                return null
            }
            // 無音（または音が届いていない）とき、Visualizer は 128（0 の位置）だけを返す
            val silent = wave.all { it == SILENT }
            // mRms はミリベル
            val rmsDb = if (measuring && v.getMeasurementPeakRms(measurement) == Visualizer.SUCCESS) measurement.mRms / 100f else Float.NaN
            val volume = mediaVolumeDb()
            levelDb = rmsDb - volume
            recorder?.write(SystemClock.elapsedRealtimeNanos(), sampleRateHz, rmsDb, volume, wave)
            // 曲間などのごく小さい音も、棒にしない。取得の状態（[status]）は、波形が無音かどうかだけで決める
            if (silent || tuning.isQuiet(rmsDb, volume)) power.fill(0f) else powerSpectrum.compute(wave, power)
            logOctaves(v, power)
            if (silent != lastSilent) {
                lastSilent = silent
                report(if (silent) "無音だけが返っています（${now()} から）${spatialHint()}" else "音を受け取っています（${now()} に確認）${scalingHint()}")
            }
            power
        } catch (e: IllegalStateException) {
            report("読み取りに失敗しました（${e.message}）")
            null
        }
    }

    /**
     * 今の出力先でのメディアの音量（dB）。Visualizer が受け取る音は、すでに音量をかけた後の音なので、その分を戻すのに使う。
     * Bluetooth の機器が音量を受け持つ場合は端末の側で音量をかけないので、実際より大きく戻すことになるが、無音の判断が甘くなるだけで済む。
     */
    // lint は AudioDeviceInfo.type を入力機器も含む定数として扱い、出力機器の定数を求める getStreamVolumeDb への受け渡しを誤りとする。
    // ここでは出力機器（isSink）の種類だけを渡し、無ければ内蔵スピーカーにするので、WrongConstant の指摘には該当しない
    @SuppressLint("WrongConstant")
    private fun mediaVolumeDb(): Float {
        val now = SystemClock.elapsedRealtime()
        if (now - volumeCheckedAt < VOLUME_CHECK_MS) return volumeDb
        volumeCheckedAt = now
        val a = audio ?: return 0f
        val db =
            runCatching {
                val device =
                    a.getAudioDevicesForAttributes(MEDIA).firstOrNull { it.isSink }?.type
                        ?: AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                a.getStreamVolumeDb(AudioManager.STREAM_MUSIC, a.getStreamVolume(AudioManager.STREAM_MUSIC), device)
            }.onFailure { Log.i(TAG, "音量を取得できない（$it）") }
                .getOrDefault(0f)
                .coerceIn(-96f, 0f)
        if (db != volumeDb) Log.i(TAG, "メディアの音量: $db dB")
        volumeDb = db
        return volumeDb
    }

    /**
     * 空間オーディオ（HyperOS ではイマーシブサウンド）がオンだと、音楽の音は専用の経路を通り、セッション 0 の Visualizer に届かない
     * （2026-11-20 に実機で確認した。オフにすると取得できた）。無音の理由として示す。
     */
    private fun spatialHint(): String {
        val s = audio?.spatializer ?: return ""
        return if (s.isAvailable && s.isEnabled) "。イマーシブサウンド（空間オーディオ）がオンのときは取得できません" else ""
    }

    private fun scalingHint(): String = if (scalingResult == Visualizer.SUCCESS) "" else "。倍率の設定に失敗しました（コード $scalingResult）"

    /** 1 オクターブごとの平均の大きさ（dB、全振幅の正弦波が 0dB）を書く。 */
    private fun logOctaves(
        v: Visualizer,
        power: FloatArray,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now > logUntil || now - lastLog < logIntervalMs) return
        lastLog = now
        val nyquist = v.samplingRate / 2000.0
        val bins = power.size
        val db =
            generateSequence(62.5) { it * 2 }.takeWhile { it <= 16000 }.map { hz ->
                val lo = (hz / 2 / nyquist * bins).toInt().coerceIn(1, bins - 1)
                val hi = (hz / nyquist * bins).toInt().coerceIn(lo + 1, bins)
                var sum = 0.0
                for (k in lo until hi) sum += power[k]
                (10 * log10(1e-12 + sum / (hi - lo))).toInt()
            }.joinToString(" ")
        Log.i(TAG, "帯域dB(62〜16k)=$db RMS=${levelDb.toInt()} 音量=${volumeDb.toInt()}")
    }

    private fun report(s: String) {
        statusFlow.value = s
        Log.i(TAG, s)
    }

    private fun now(): String = LocalTime.now().format(TIME)

    private companion object {
        const val CAPTURE_SIZE = 1024
        const val SILENT = 0x80.toByte()

        const val VOLUME_CHECK_MS = 500L
        val MEDIA: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        const val TAG = "KomaVisualizer"
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
