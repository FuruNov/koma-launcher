package dev.furunov.komalauncher.platform.media

import android.media.audiofx.Visualizer
import dev.furunov.komalauncher.model.media.AudioSpectrum

/**
 * 端末が出している音全体（セッション 0）の周波数を Visualizer で読む。録音の権限が要る。
 * 端末や音楽アプリによっては無音しか返らないので、その判断は画面側（5 秒続けて無音なら切り替える）に任せる。
 */
class VisualizerSpectrum : AudioSpectrum {
    private var visualizer: Visualizer? = null

    override fun start(): Boolean {
        if (visualizer != null) return true
        return try {
            // 画面側の計算は 1024 の FFT を前提にする。それより小さい端末では使わない
            if (Visualizer.getCaptureSizeRange()[1] < CAPTURE_SIZE) return false
            val v = Visualizer(0)
            v.captureSize = CAPTURE_SIZE
            v.enabled = true
            visualizer = v
            true
        } catch (e: Exception) {
            // 権限が無い（SecurityException）、端末が対応していない（RuntimeException）など
            false
        }
    }

    override fun stop() {
        visualizer?.let {
            it.enabled = false
            it.release()
        }
        visualizer = null
    }

    override fun read(out: ByteArray): Boolean {
        val v = visualizer ?: return false
        if (out.size != CAPTURE_SIZE) return false
        return try {
            v.getFft(out) == Visualizer.SUCCESS
        } catch (e: IllegalStateException) {
            false
        }
    }

    private companion object {
        const val CAPTURE_SIZE = 1024
    }
}
