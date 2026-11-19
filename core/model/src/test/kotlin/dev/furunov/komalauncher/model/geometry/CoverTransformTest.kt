package dev.furunov.komalauncher.model.geometry

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverTransformTest {
    private fun near(
        a: Point,
        b: Point,
    ) = abs(a.x - b.x) < 1e-3f && abs(a.y - b.y) < 1e-3f

    @Test
    fun sameAspectIsPlainScale() {
        val t = CoverTransform(viewWidth = 1220f, viewHeight = 2712f, imageAspect = 1220f / 2712f)
        assertTrue(near(Point(610f, 1356f), t.toView(Point(0.5f, 0.5f))))
        assertTrue(near(Point(1220f, 2712f), t.toView(Point(1f, 1f))))
    }

    @Test
    fun widerViewCropsTopAndBottom() {
        // 1080×1920（9:16）の画面に 1220:2712（約 9:20）の画像を cover で置くと、上下がはみ出す
        val t = CoverTransform(viewWidth = 1080f, viewHeight = 1920f, imageAspect = 1220f / 2712f)
        val drawnHeight = 1080f / (1220f / 2712f)
        assertEquals(drawnHeight, t.imageRect.height, 0.5f)
        assertEquals(1080f, t.imageRect.width, 0.01f)
        assertTrue(t.imageRect.top < 0f)
        // 画像の中心は画面の中心に来る
        assertTrue(near(Point(540f, 960f), t.toView(Point(0.5f, 0.5f))))
    }

    @Test
    fun roundTrip() {
        for ((w, h) in listOf(1080f to 1920f, 1220f to 2712f, 2712f to 1220f)) {
            val t = CoverTransform(w, h, imageAspect = 1220f / 2712f)
            val p = Point(0.3f, 0.71f)
            assertTrue(near(p, t.toImage(t.toView(p))), "$w x $h")
        }
    }

    @Test
    fun samePanelOnBothScreensHitsSameImagePoint() {
        // Waydroid と実機で同じ JSON が同じ絵の位置を指すこと
        val waydroid = CoverTransform(1080f, 1920f, 1220f / 2712f)
        val poco = CoverTransform(1220f, 2712f, 1220f / 2712f)
        val p = Point(0.42f, 0.5f)
        assertTrue(near(waydroid.toImage(waydroid.toView(p)), poco.toImage(poco.toView(p))))
    }
}
