package dev.furunov.komalauncher.model.icons

import dev.furunov.komalauncher.model.settings.IconRim
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

class IconContentBoxTest {
    @Test
    fun contentBoxStaysInsideShapeAndRim() {
        IconShape.entries.forEach { shape ->
            IconRim.entries.forEach { rim ->
                val box = IconOutline.contentBox(shape, rim)
                val poly = IconOutline.polygon(IconOutline.of(shape, needsClip = true))
                listOf(box.l to box.t, box.r to box.t, box.r to box.b, box.l to box.b).forEach { (x, y) ->
                    assertTrue(IconOutline.inside(poly, x, y), "$shape/$rim の四角の角 ($x, $y) が形の外にある")
                    assertTrue(IconOutline.distanceToEdge(poly, x, y) >= IconOutline.rimWidth(rim) - 0.002f, "$shape/$rim の四角の角 ($x, $y) が縁に掛かる")
                }
            }
        }
    }

    @Test
    fun thickRimStillLeavesContentOutsideRim() {
        IconShape.entries.forEach { shape ->
            IconRim.entries.forEach { rim ->
                val box = IconOutline.contentBox(shape, rim, scale = 2f)
                val poly = IconOutline.polygon(IconOutline.of(shape, needsClip = true))
                listOf(box.l to box.t, box.r to box.t, box.r to box.b, box.l to box.b).forEach { (x, y) ->
                    assertTrue(IconOutline.distanceToEdge(poly, x, y) >= IconOutline.rimWidth(rim, 2f) - 0.002f, "$shape/$rim の太い縁に四角の角 ($x, $y) が掛かる")
                }
            }
        }
    }

    @Test
    fun rimWidthScales() = assertEquals(IconOutline.rimWidth(IconRim.INK) * 1.5f, IconOutline.rimWidth(IconRim.INK, 1.5f), 0.0001f)

    @Test
    fun roundedWithoutRimKeepsSixteenPercentMargin() {
        val box = IconOutline.contentBox(IconShape.ROUNDED, IconRim.NONE)
        assertEquals(0.16f, box.l, 0.001f)
        assertEquals(0.84f, box.r, 0.001f)
    }
}
