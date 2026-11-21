package dev.furunov.komalauncher.model.media

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Visualizer.getWaveForm の形式の波形（符号なし 8 ビット、128 が 0）から、帯域ごとのパワーを計算する。
 *
 * Visualizer.getFft は窓関数をかけずに固定小数点で計算し、127 を超えた値を半分にする（AOSP の Visualizer.cpp の doFft）。
 * 大きい音の帯域ほど小さく読めることがあり、低音の大きい成分がほかの帯域に漏れるので、ハン窓をかけて自分で計算する。
 * 毎フレーム呼ぶので、作業用の配列は波形の長さが変わったときだけ作り直す。画面のスレッドからだけ呼ぶ。
 */
class PowerSpectrum {
    private var n = 0
    private var window = FloatArray(0)
    private var re = FloatArray(0)
    private var im = FloatArray(0)
    private var cosTable = FloatArray(0)
    private var sinTable = FloatArray(0)

    /**
     * [wave]（長さは 2 のべき乗）の 0〜標本化周波数の半分を、長さ [wave] の半分の [out] に書く。
     * 値は振幅の 2 乗で、全振幅の正弦波がその周波数の区画で約 1 になるように割る（区画の間の周波数では最大 1.4dB ほど小さくなる）。
     */
    fun compute(
        wave: ByteArray,
        out: FloatArray,
    ) {
        prepare(wave.size)
        for (i in 0 until n) {
            re[i] = ((wave[i].toInt() and 0xFF) - 128) / 128f * window[i]
            im[i] = 0f
        }
        fft()
        // 全振幅の正弦波では、その区画の |X| がハン窓の和（n / 2）の半分の n / 4 になるので、16 / n² を掛ける
        val scale = 16f / (n.toFloat() * n)
        for (k in 0 until n / 2) out[k] = (re[k] * re[k] + im[k] * im[k]) * scale
    }

    private fun prepare(size: Int) {
        if (size == n) return
        require(size >= 2 && size and (size - 1) == 0) { "波形の長さは 2 のべき乗にする（$size）" }
        n = size
        window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
        re = FloatArray(n)
        im = FloatArray(n)
        cosTable = FloatArray(n / 2) { cos(2 * PI * it / n).toFloat() }
        sinTable = FloatArray(n / 2) { sin(2 * PI * it / n).toFloat() }
    }

    /** 基数 2 の FFT。[re] と [im] をその場で書き換える。 */
    private fun fft() {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            for (start in 0 until n step len) {
                for (k in 0 until half) {
                    val c = cosTable[k * step]
                    val s = -sinTable[k * step]
                    val a = start + k
                    val b = a + half
                    val tr = re[b] * c - im[b] * s
                    val ti = re[b] * s + im[b] * c
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
            }
            len *= 2
        }
    }
}
