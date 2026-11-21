package dev.furunov.komalauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.furunov.komalauncher.model.media.SpectrumRecording

/**
 * 空間オーディオがオンのとき、再生中のアプリのセッションに直接付けた Visualizer で音を取れるかを確かめる実験（2026-11-20）。
 * 例: am broadcast -n dev.furunov.komalauncher/.DebugReceiver -a dev.furunov.komalauncher.DEBUG_VIS --ei session 18097
 * セッションを省くと、再生中の設定（AudioPlaybackConfiguration）から番号を取れるかだけを調べる。
 */
object DebugVisualizer {
    private const val TAG = "KomaVisTest"

    fun handle(
        context: Context,
        session: Int,
        listen: Boolean,
        use: Int,
        scaling: Int,
        logMs: Long,
        intervalMs: Long,
        recordSec: Int,
    ) {
        probeSessions(context)
        Thread { probeDump() }.start()
        if (listen) listenSessions(context.applicationContext)
        if (session > 0) Thread { capture(session) }.start()
        // ホームの棒を、このセッションから取る（無音が続くと 3 秒ごとに作り直すので、そのときに切り替わる）
        val spectrum = (context.applicationContext as KomaApplication).container.spectrum
        // 倍率の決め方を切り替える（0: NORMALIZED、1: AS_PLAYED）
        if (scaling >= 0) spectrum.scalingMode = scaling
        if (session < 0) {
            spectrum.logIntervalMs = intervalMs
            spectrum.logUntil = android.os.SystemClock.elapsedRealtime() + logMs
        }
        if (use >= 0) {
            (context.applicationContext as KomaApplication).container.spectrum.session = use
            Log.i(TAG, "ホームの棒のセッションを $use にした")
        }
        if (recordSec > 0) record(context, spectrum, recordSec)
    }

    /**
     * ホームの棒が読む生の値を [seconds] 秒、アプリの files/ に書く。ホームが前面にあって再生中の間だけ書かれる。
     * 例: am broadcast -n dev.furunov.komalauncher/.DebugReceiver -a dev.furunov.komalauncher.DEBUG_VIS --ei record 90
     * 取り出し: adb exec-out run-as dev.furunov.komalauncher cat files/vis-（時刻）.bin > rec.bin
     */
    private fun record(
        context: Context,
        spectrum: dev.furunov.komalauncher.platform.media.VisualizerSpectrum,
        seconds: Int,
    ) {
        spectrum.recorder?.close()
        val name = "vis-${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.bin"
        val writer = SpectrumRecording.Writer(java.io.File(context.filesDir, name).outputStream())
        spectrum.recorder = writer
        Log.i(TAG, "記録を始めた: files/$name（$seconds 秒）")
        // read と同じ画面のスレッドで閉じる
        Handler(Looper.getMainLooper()).postDelayed({
            if (spectrum.recorder === writer) spectrum.recorder = null
            writer.close()
            Log.i(TAG, "記録を終えた: files/$name")
        }, seconds * 1000L)
    }

    /** 音楽アプリが再生の始めと終わりに送る、イコライザー向けの知らせを 60 秒受け取る。 */
    private fun listenSessions(context: Context) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    c: Context,
                    i: Intent,
                ) {
                    Log.i(TAG, "知らせ: ${i.action} session=${i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)} package=${i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)}")
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        Log.i(TAG, "知らせを待っています（60 秒）")
        Handler(Looper.getMainLooper()).postDelayed({ context.unregisterReceiver(receiver) }, 60_000)
    }

    /** DUMP の権限があれば、アプリから dumpsys audio を読めるか（読めればセッションの番号が分かる）。 */
    private fun probeDump() {
        try {
            // dumpsys のコマンドは SELinux で拒まれるので、サービスの binder に直接 dump を頼む
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "audio") as android.os.IBinder
            val pipe = android.os.ParcelFileDescriptor.createPipe()
            val reader = Thread {
                binder.dump(pipe[1].fileDescriptor, emptyArray())
                pipe[1].close()
            }
            reader.start()
            val lines = android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).bufferedReader().readLines()
            val players = lines.filter { "new player" in it }
            Log.i(TAG, "dumpsys audio: ${lines.size} 行、new player ${players.size} 行。最後: ${players.lastOrNull()} 先頭: ${lines.firstOrNull()}")
        } catch (e: Throwable) {
            Log.i(TAG, "dumpsys audio を読めない（${e.javaClass.simpleName}: ${e.message}）")
        }
    }

    /** 再生中の設定からセッションの番号を取れるか（getSessionId は非公開）。 */
    private fun probeSessions(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java)
        val configs = audio.activePlaybackConfigurations
        Log.i(TAG, "再生中の設定: ${configs.size} 件")
        for (c in configs) {
            val id =
                try {
                    c.javaClass.getMethod("getSessionId").invoke(c)
                } catch (e: Throwable) {
                    "取得できない（${e.javaClass.simpleName}: ${e.message}）"
                }
            Log.i(TAG, "usage=${c.audioAttributes.usage} session=$id")
        }
    }

    private fun capture(session: Int) {
        val v =
            try {
                Visualizer(session).apply {
                    captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                    enabled = true
                }
            } catch (e: Throwable) {
                Log.i(TAG, "セッション $session に付けられない（${e.javaClass.simpleName}: ${e.message}）")
                return
            }
        val fft = ByteArray(v.captureSize)
        // 帯域ごとの大きさ（dB）を 0.1 秒ごとに 3 秒記録する
        val rate = v.samplingRate / 1000
        repeat(30) {
            Thread.sleep(100)
            v.getFft(fft)
            val bins = fft.size / 2
            // 0〜125, 125〜250, 250〜500, ... の 1 オクターブごとの平均の大きさ（dB）
            val octaves = generateSequence(125.0) { it * 2 }.takeWhile { it <= 16000 }.map { hz ->
                val lo = (hz / 2 / (rate / 2.0) * bins).toInt().coerceIn(1, bins - 1)
                val hi = (hz / (rate / 2.0) * bins).toInt().coerceIn(lo + 1, bins)
                var sum = 0.0
                for (k in lo until hi) sum += fft[2 * k].toDouble() * fft[2 * k] + fft[2 * k + 1].toDouble() * fft[2 * k + 1]
                (10 * kotlin.math.log10(1e-9 + sum / (hi - lo))).toInt()
            }.toList()
            Log.i(TAG, "帯域dB=$octaves")
        }
        v.enabled = false
        v.release()
    }
}
