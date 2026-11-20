package dev.furunov.komalauncher.model.layout

import kotlin.test.Test
import kotlin.test.assertEquals

class MaskMathTest {
    @Test
    fun stretchesAndInverts() {
        val keep = MaskMath.keepFromPerson(floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f))
        listOf(1f, 1f, 0.5f, 0f, 0f).forEachIndexed { i, want -> assertEquals(want, keep[i], 1e-5f) }
    }

    @Test
    fun coverGridAverages() {
        // 4×2 の画像の左半分が人物（残す濃さ 0）
        val keep = floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 1f, 1f)
        val grid = MaskMath.coverGrid(keep, 4, 2, columns = 2, rows = 1)
        assertEquals(1f, grid[0], 1e-5f)
        assertEquals(0f, grid[1], 1e-5f)
    }
}
