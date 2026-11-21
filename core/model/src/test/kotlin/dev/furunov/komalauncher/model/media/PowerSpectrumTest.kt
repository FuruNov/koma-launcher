package dev.furunov.komalauncher.model.media

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PowerSpectrumTest {
    /** [bin] 番目の区画の周波数の正弦波（振幅 [amplitude]、全振幅が 1）を、getWaveForm の形式で作る。 */
    private fun sine(
        bin: Int,
        amplitude: Float,
    ) = ByteArray(1024) { (128 + (127 * amplitude * sin(2 * PI * bin * it / 1024)).roundToInt()).toByte() }

    private fun power(wave: ByteArray) = FloatArray(512).also { PowerSpectrum().compute(wave, it) }

    @Test
    fun silenceGivesZero() = assertTrue(power(ByteArray(1024) { 0x80.toByte() }).all { it == 0f })

    @Test
    fun fullScaleSineIsAboutOne() {
        val p = power(sine(32, 1f))
        assertEquals((127f / 128f).let { it * it }, p[32], 0.02f)
        // ハン窓なので、2 区画以上離れた区画にはほとんど漏れない
        assertTrue(p[29] < 1e-4f && p[35] < 1e-4f, "${p[29]} ${p[35]}")
    }

    @Test
    fun louderSineIsNeverSmaller() {
        // getFft は 127 を超えた値を半分にするので、大きい音ほど小さく読めることがある。自分で計算すれば、振幅が 2 倍なら 6dB 大きい
        val db = { a: Float -> 10 * log10(power(sine(5, a))[5]) }
        assertEquals(6.02f, db(1f) - db(0.5f), 0.1f)
    }
}
