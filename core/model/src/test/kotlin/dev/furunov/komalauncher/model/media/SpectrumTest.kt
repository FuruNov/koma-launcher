package dev.furunov.komalauncher.model.media

import dev.furunov.komalauncher.model.geometry.Point
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
    fun barCountHasMinimum() = assertEquals(10, Spectrum.barCount(30f, 3f))

    @Test
    fun barCountFollowsEdgeLength() = assertEquals(20, Spectrum.barCount(540f, 3f))

    @Test
    fun silentFftGivesZeroBands() {
        val out = FloatArray(12) { 1f }
        Spectrum.bands(ByteArray(1024), 12, out)
        assertTrue(out.all { it == 0f })
        assertTrue(Spectrum.isSilent(ByteArray(1024)))
    }

    @Test
    fun loudLowFrequencyRaisesFirstBand() {
        val fft = ByteArray(1024).also { it[2] = 100 }
        val out = FloatArray(12)
        Spectrum.bands(fft, 12, out)
        assertTrue(out[0] > 0.5f && out.drop(1).all { it < 0.01f }, out.joinToString())
        assertFalse(Spectrum.isSilent(fft))
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
