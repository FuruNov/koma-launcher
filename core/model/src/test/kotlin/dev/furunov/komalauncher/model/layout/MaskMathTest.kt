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

    @Test
    fun personIsTheInverseOfKeep() {
        val person = MaskMath.personFromKeep(byteArrayOf(0, 255.toByte(), 100))
        assertEquals(listOf(255, 0, 155), person.map { it.toInt() and 0xFF })
    }

    @Test
    fun boxBlurKeepsTheTotalAndSpreads() {
        val w = 21
        val src = ByteArray(w) { if (it == 10) 210.toByte() else 0 }
        val out = MaskMath.boxBlur(src, w, 1, radius = 2, passes = 1).map { it.toInt() and 0xFF }
        // 半径 2 の箱は 5 画素に均す
        assertEquals(listOf(0, 42, 42, 42, 42, 42, 0), out.subList(7, 14))
        assertEquals(src.toList(), MaskMath.boxBlur(src, w, 1, radius = 0).toList())
    }

    @Test
    fun dilateGrowsByTheRadius() {
        val w = 21
        val src = ByteArray(w * w) { if (it == 10 * w + 10) 255.toByte() else 0 }
        val out = MaskMath.dilate(src, w, w, 4f)
        fun at(x: Int, y: Int) = out[y * w + x].toInt() and 0xFF
        assertEquals(255, at(14, 10))
        assertEquals(255, at(12, 10))
        assertEquals(255, at(10, 6))
        assertEquals(0, at(16, 10))
    }
}
