package dev.furunov.komalauncher.model.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolygonTest {
    private val square = Polygon(listOf(Point(0f, 0f), Point(10f, 0f), Point(10f, 10f), Point(0f, 10f)))

    // 上辺は 0〜10、下辺は 0〜6 の、右側を斜めに切ったコマ
    private val slanted = Polygon(listOf(Point(0f, 0f), Point(10f, 0f), Point(6f, 10f), Point(0f, 10f)))

    // L 字の凹多角形
    private val concave =
        Polygon(
            listOf(Point(0f, 0f), Point(4f, 0f), Point(4f, 6f), Point(10f, 6f), Point(10f, 10f), Point(0f, 10f)),
        )

    @Test
    fun spanBetweenTakesTheNarrowestWidthInTheBand() {
        // 右の辺は y = 0 で x = 10、y = 10 で x = 6。帯 2〜5 で最も狭いのは y = 5 の 8
        val (l, r) = assertNotNull(slanted.spanBetween(2f, 5f))
        assertEquals(0f, l, 1e-4f)
        assertEquals(8f, r, 1e-4f)
        assertEquals(0f to 10f, square.spanBetween(0f, 10f))
        assertNull(square.spanBetween(11f, 12f))
    }

    @Test
    fun insetShrinksAndStopsAtTheCenter() {
        assertEquals(Rect(2f, 2f, 8f, 8f), Rect(0f, 0f, 10f, 10f).inset(2f))
        // 幅より大きく寄せると、中心の線になる
        assertEquals(Rect(5f, 8f, 5f, 12f), Rect(0f, 0f, 10f, 20f).inset(8f))
    }

    @Test
    fun insideConvex() = assertTrue(square.contains(Point(5f, 5f)))

    @Test
    fun outsideConvex() = assertFalse(square.contains(Point(11f, 5f)))

    @Test
    fun verticesAndEdgesCountAsInside() {
        // 境界線の上を叩いたときに、どのコマも反応しない隙間を作らないため
        assertTrue(square.contains(Point(0f, 0f)))
        assertTrue(square.contains(Point(10f, 5f)))
        assertTrue(slanted.contains(Point(8f, 5f)))
    }

    @Test
    fun slantedEdge() {
        assertTrue(slanted.contains(Point(7.9f, 5f)))
        assertFalse(slanted.contains(Point(8.1f, 5f)))
        assertTrue(slanted.contains(Point(9.5f, 0.5f)))
        assertFalse(slanted.contains(Point(9f, 9f)))
    }

    @Test
    fun concaveNotch() {
        assertTrue(concave.contains(Point(2f, 2f)))
        assertFalse(concave.contains(Point(7f, 3f)))
        assertTrue(concave.contains(Point(7f, 8f)))
    }

    @Test
    fun bounds() = assertEquals(Rect(0f, 0f, 10f, 10f), slanted.bounds)

    @Test
    fun inscribedRectIsInsidePolygon() {
        for (p in listOf(square, slanted, concave)) {
            val r = p.largestInscribedRect()
            assertTrue(r.width > 0f && r.height > 0f)
            for (corner in listOf(Point(r.left, r.top), Point(r.right, r.top), Point(r.right, r.bottom), Point(r.left, r.bottom))) {
                assertTrue(p.contains(corner), "$corner of $r is outside $p")
            }
        }
    }

    @Test
    fun inscribedRectOfSquareIsAlmostWhole() {
        val r = square.largestInscribedRect()
        assertTrue(r.width * r.height > 90f, "$r")
    }

    @Test
    fun inscribedRectOfSlantedUsesMostOfIt() {
        // 面積 80 の台形。内接長方形はその 6 割以上あってほしい
        val r = slanted.largestInscribedRect()
        assertTrue(r.width * r.height > 48f, "$r")
    }

    @Test
    fun mapPoints() {
        val moved = square.map { Point(it.x * 2, it.y + 1) }
        assertEquals(Rect(0f, 1f, 20f, 11f), moved.bounds)
    }
}
