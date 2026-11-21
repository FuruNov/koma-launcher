package dev.furunov.komalauncher.model.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpectrumRecordingTest {
    @Test
    fun readsWhatWasWritten() {
        val bytes = ByteArrayOutputStream()
        SpectrumRecording.Writer(bytes).use {
            it.write(1_000L, 48_000, -55.5f, -47f, ByteArray(1024) { i -> i.toByte() })
            it.write(17_000_000L, 48_000, Float.NaN, 0f, ByteArray(512))
        }
        val frames = SpectrumRecording.read(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(2, frames.size)
        assertEquals(1_000L, frames[0].timeNanos)
        assertEquals(-55.5f, frames[0].rmsDb)
        assertEquals(-47f, frames[0].volumeDb)
        assertContentEquals(ByteArray(1024) { i -> i.toByte() }, frames[0].wave)
        assertTrue(frames[1].rmsDb.isNaN())
        assertEquals(512, frames[1].wave.size)
    }

    @Test
    fun quietAddsBackTheVolume() {
        val t = SpectrumTuning()
        // 音量 -47dB で RMS -57dB なら、音量をかける前は -10dB（曲が鳴っている）
        assertFalse(t.isQuiet(-57f, -47f))
        assertTrue(t.isQuiet(-100f, -47f))
        // 計測できなければ判断しない
        assertFalse(t.isQuiet(Float.NaN, -47f))
    }
}
