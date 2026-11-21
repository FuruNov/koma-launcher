package dev.furunov.komalauncher.model.media

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * ビジュアライザーが読んだ生の値の記録。debug 版が実機で書き、tools/vis-replay が PC で読んで、棒の計算を設定ごとに比べる。
 * 画面のフレームごとに 1 件書く（Visualizer は同じ波形を続けて返すことがあるが、アプリと同じ間隔で再生できるよう、そのまま残す）。
 */
object SpectrumRecording {
    class Frame(
        /** 読んだ時刻（ナノ秒、elapsedRealtimeNanos）。 */
        val timeNanos: Long,
        val sampleRateHz: Int,
        /** 正規化の前の RMS（dB、音量をかけた後）。計測できなければ NaN。 */
        val rmsDb: Float,
        /** メディアの音量（dB、0 以下）。 */
        val volumeDb: Float,
        /** Visualizer.getWaveForm の形式の波形（符号なし 8 ビット、128 が 0）。 */
        val wave: ByteArray,
    )

    /** 書き始めに 1 回だけ書く、形式の印と版。 */
    private const val MAGIC = 0x4B564953 // "KVIS"
    private const val VERSION = 1

    class Writer(
        out: OutputStream,
    ) : AutoCloseable {
        private val data = DataOutputStream(out.buffered())

        init {
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
        }

        fun write(
            timeNanos: Long,
            sampleRateHz: Int,
            rmsDb: Float,
            volumeDb: Float,
            wave: ByteArray,
        ) {
            data.writeLong(timeNanos)
            data.writeInt(sampleRateHz)
            data.writeFloat(rmsDb)
            data.writeFloat(volumeDb)
            data.writeInt(wave.size)
            data.write(wave)
        }

        override fun close() = data.close()
    }

    fun read(input: InputStream): List<Frame> {
        val data = DataInputStream(input.buffered())
        require(data.readInt() == MAGIC) { "ビジュアライザーの記録ではない" }
        val version = data.readInt()
        require(version == VERSION) { "記録の版 $version には対応していない" }
        val frames = mutableListOf<Frame>()
        while (true) {
            val t =
                try {
                    data.readLong()
                } catch (_: EOFException) {
                    break
                }
            val rate = data.readInt()
            val rms = data.readFloat()
            val volume = data.readFloat()
            val wave = ByteArray(data.readInt())
            data.readFully(wave)
            frames += Frame(t, rate, rms, volume, wave)
        }
        return frames
    }
}
