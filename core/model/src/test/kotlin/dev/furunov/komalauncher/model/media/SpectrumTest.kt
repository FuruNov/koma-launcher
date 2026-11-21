package dev.furunov.komalauncher.model.media

import dev.furunov.komalauncher.model.geometry.Point
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpectrumTest {
    @Test
    fun bottomEdgeOfSlantedQuad() {
        val e = Spectrum.bottomEdge(listOf(Point(0f, 0f), Point(100f, 0f), Point(100f, 45f), Point(0f, 60f)))
        assertEquals(Point(0f, 60f) to Point(100f, 45f), e)
    }

    @Test
    fun bottomEdgeSkipsZeroLengthEdge() {
        // コマの間隔が 0 のとき、切る線の上の頂点が 2 回入ることがある。長さ 0 の辺を選ぶと棒の向きが決まらない
        val e = Spectrum.bottomEdge(listOf(Point(0f, 0f), Point(100f, 0f), Point(100f, 80f), Point(100f, 80f), Point(0f, 60f)))
        assertEquals(Point(0f, 60f) to Point(100f, 80f), e)
    }

    @Test
    fun barCountHasMinimum() = assertEquals(10, Spectrum.barCount(30f, 3f))

    @Test
    fun barCountFollowsEdgeLength() = assertEquals(20, Spectrum.barCount(540f, 3f))

    @Test
    fun silentPowerGivesZeroBars() {
        val out = FloatArray(12) { 1f }
        SpectrumShaper(12).shape(FloatArray(512), 48_000, 0.016f, out)
        assertTrue(out.all { it == 0f })
        assertTrue(Spectrum.isSilent(FloatArray(512)))
    }

    @Test
    fun smallJitterGivesZeroBars() {
        // 無音に近いときに残る、波形の ±1 の揺れは棒にしない
        var seed = 12345
        val wave =
            ByteArray(1024) {
                seed = seed * 1103515245 + 12345
                (128 + if (seed ushr 16 and 1 == 0) 1 else -1).toByte()
            }
        val power = FloatArray(512)
        PowerSpectrum().compute(wave, power)
        val out = FloatArray(12) { 1f }
        SpectrumShaper(12).shape(power, 48_000, 0.016f, out)
        assertTrue(out.all { it == 0f }, out.joinToString())
    }

    @Test
    fun loudLowFrequencyRaisesFirstBar() {
        // 1 番目の周波数（約 47Hz）だけが大きい（-10dB）
        val power = FloatArray(512).also { it[1] = 0.1f }
        val out = FloatArray(12)
        SpectrumShaper(12).shape(power, 48_000, 0.016f, out)
        assertEquals(1f, out[0], 1e-3f)
        assertTrue(out.drop(1).all { it == 0f }, out.joinToString())
        assertFalse(Spectrum.isSilent(power))
    }

    @Test
    fun barsFollowTheRecentLoudestLevel() {
        val shaper = SpectrumShaper(12)
        val out = FloatArray(12)
        // -25dB と、10dB 小さい -35dB
        val loud = FloatArray(512).also { it[1] = 10f.pow(-2.5f) }
        val quiet = FloatArray(512).also { it[1] = 10f.pow(-3.5f) }
        shaper.shape(loud, 48_000, 0.016f, out)
        // 時間方向に平均するので、1 回だけでは小さい音まで下がりきらない
        shaper.shape(quiet, 48_000, 0.016f, out)
        val first = out[0]
        // 0.5 秒続くと、直前の大きい音から 10dB 下（最近の最大は 0.25dB 下がる）なので、範囲 30dB の約 3 分の 2（を 3 乗した高さ）
        repeat(30) { shaper.shape(quiet, 48_000, 0.016f, out) }
        assertTrue(first > out[0])
        assertEquals((20.25f / 30f).pow(3f), out[0], 0.03f)
        // 小さい音が 5 秒続くと、最近の最大が下がり（2.65dB）、同じ音でも棒が高くなる
        repeat(300) { shaper.shape(quiet, 48_000, 0.016f, out) }
        assertEquals((22.65f / 30f).pow(3f), out[0], 0.03f)
    }

    @Test
    fun simulatedIsZeroAtLevelZero() {
        val out = FloatArray(16) { 1f }
        Spectrum.simulated(16, 3.2f, 0f, out)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun simulatedStaysInRange() {
        val out = FloatArray(16)
        for (t in 0 until 100) {
            Spectrum.simulated(16, t * 0.037f, 1f, out)
            assertTrue(out.all { it in 0f..1f })
        }
    }

    @Test
    fun smoothRisesFasterThanFalls() {
        val up = floatArrayOf(0f)
        Spectrum.smooth(up, floatArrayOf(1f), 0.016f)
        val down = floatArrayOf(1f)
        Spectrum.smooth(down, floatArrayOf(0f), 0.016f)
        assertTrue(up[0] > 1f - down[0])
    }
}
