package dev.furunov.komalauncher.model.icons

import dev.furunov.komalauncher.model.settings.IconShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IconOutlineTest {
    private fun points(ops: List<OutlineOp>): List<Pair<Float, Float>> = ops.flatMap { op ->
        when (op) {
            is OutlineOp.Move -> listOf(op.x to op.y)
            is OutlineOp.Line -> listOf(op.x to op.y)
            is OutlineOp.Cubic -> listOf(op.x1 to op.y1, op.x2 to op.y2, op.x to op.y)
            OutlineOp.Close -> emptyList()
        }
    }

    @Test
    fun allShapesFitInUnitSquare() {
        IconShape.entries.forEach { shape ->
            points(IconOutline.of(shape, needsClip = true)).forEach { (x, y) ->
                assertTrue(x in -0.001f..1.001f && y in -0.001f..1.001f, "$shape の点 ($x, $y) が正方形の外にある")
            }
        }
    }

    @Test
    fun bubbleTailPointsToBottomLeft() {
        val lowest = points(IconOutline.of(IconShape.BUBBLE, needsClip = true)).maxBy { it.second }
        assertTrue(lowest.first < 0.2f && lowest.second > 0.95f, "尻尾の先が左下に無い: $lowest")
    }

    @Test
    fun systemWithRimUsesRounded() = assertEquals(IconOutline.of(IconShape.ROUNDED, true), IconOutline.of(IconShape.SYSTEM, true))

    @Test
    fun outlinesAreClosed() {
        IconShape.entries.forEach { shape ->
            val ops = IconOutline.of(shape, needsClip = true)
            assertTrue(ops.first() is OutlineOp.Move && ops.last() == OutlineOp.Close, "$shape の輪郭が閉じていない")
        }
    }
}
