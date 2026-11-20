package dev.furunov.komalauncher.model.layout

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ToneMatrixTest {
    /** モックアップの filterImage（"soft"）を、そのまま Kotlin に書いたもの。 */
    private fun mockup(
        r0: Float,
        g0: Float,
        b0: Float,
        strength: Float,
    ): FloatArray {
        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        val l = (0.299f * r0 + 0.587f * g0 + 0.114f * b0) / 255f
        val gray = l * 255f
        val r = lerp(gray, r0, 0.55f) * 0.9f + 246f * 0.1f
        val g = lerp(gray, g0, 0.55f) * 0.9f + 242f * 0.1f
        val b = lerp(gray, b0, 0.55f) * 0.9f + 233f * 0.1f - 6f
        return floatArrayOf(lerp(r0, r, strength), lerp(g0, g, strength), lerp(b0, b, strength))
    }

    @Test
    fun matchesMockupFormula() {
        val rnd = Random(1120)
        listOf(0.3f, 0.7f, 1f).forEach { s ->
            val m = assertNotNull(ToneMatrix.of(Look(Tone.SOFT, strength = s)))
            repeat(200) {
                val (r, g, b) = List(3) { rnd.nextFloat() * 255f }
                val got = ToneMatrix.apply(m, r, g, b)
                val want = mockup(r, g, b, s)
                for (i in 0..2) assertEquals(want[i], got[i], 0.01f)
            }
        }
    }

    @Test
    fun noneOrZeroStrengthIsNull() {
        assertNull(ToneMatrix.of(Look()))
        assertNull(ToneMatrix.of(Look(Tone.SOFT, strength = 0f)))
    }
}
