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
    fun silentFftGivesZeroBars() {
        val out = FloatArray(12) { 1f }
        SpectrumShaper(12).shape(ByteArray(1024), 48_000, 0.016f, out)
        assertTrue(out.all { it == 0f })
        assertTrue(Spectrum.isSilent(ByteArray(1024)))
    }

    @Test
    fun smallJitterGivesZeroBars() {
        // 無音でも残る ±1 の揺れは、棒にしない
        val fft = ByteArray(1024) { if (it % 3 == 0) 1 else -1 }
        val out = FloatArray(12) { 1f }
        SpectrumShaper(12).shape(fft, 48_000, 0.016f, out)
        assertTrue(out.all { it == 0f }, out.joinToString())
    }

    @Test
    fun loudLowFrequencyRaisesFirstBar() {
        // 1 番目の周波数（約 47Hz）だけが大きい
        val fft = ByteArray(1024).also { it[2] = 100 }
        val out = FloatArray(12)
        SpectrumShaper(12).shape(fft, 48_000, 0.016f, out)
        assertEquals(1f, out[0], 1e-3f)
        assertTrue(out.drop(1).all { it == 0f }, out.joinToString())
        assertFalse(Spectrum.isSilent(fft))
    }

    @Test
    fun barsFollowTheRecentLoudestLevel() {
        val shaper = SpectrumShaper(12)
        val out = FloatArray(12)
        val loud = ByteArray(1024).also { it[2] = 100 }
        // 10dB 小さい音（大きさ 100 の 1/√10）
        val quiet = ByteArray(1024).also { it[2] = 32 }
        shaper.shape(loud, 48_000, 0.016f, out)
        shaper.shape(quiet, 48_000, 0.016f, out)
        // 直前の大きい音から 10dB 下なので、範囲 20dB の中ほど（を 1.5 乗した高さ）
        assertEquals(0.5f.pow(1.5f), out[0], 0.03f)
        // 小さい音が 5 秒続くと、最近の最大が下がり、同じ音でも棒が高くなる。ただし最近の最大は 34dB より下げないので、満杯にはしない
        repeat(300) { shaper.shape(quiet, 48_000, 0.016f, out) }
        assertEquals(0.8f.pow(1.5f), out[0], 0.03f)
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
