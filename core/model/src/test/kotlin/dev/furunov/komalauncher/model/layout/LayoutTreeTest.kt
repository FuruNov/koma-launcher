package dev.furunov.komalauncher.model.layout

import dev.furunov.komalauncher.model.geometry.Point
import dev.furunov.komalauncher.model.geometry.Rect
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TreeGeometryTest {
    private val rect = listOf(Point(0f, 0f), Point(1000f, 0f), Point(1000f, 2000f), Point(0f, 2000f))

    @Test
    fun pointAtWalksClockwiseFromFirstVertex() {
        // 周 6000。上辺 0〜1000、右辺 1000〜3000、下辺 3000〜4000、左辺 4000〜6000
        assertNear(Point(500f, 0f), TreeGeometry.pointAt(rect, 500f / 6000f))
        assertNear(Point(1000f, 1000f), TreeGeometry.pointAt(rect, 2000f / 6000f))
        assertNear(Point(0f, 1000f), TreeGeometry.pointAt(rect, 5000f / 6000f))
        // 範囲の外は周回させる
        assertNear(Point(500f, 0f), TreeGeometry.pointAt(rect, 1f + 500f / 6000f))
        assertNear(Point(0f, 1000f), TreeGeometry.pointAt(rect, -1000f / 6000f))
    }

    @Test
    fun paramOfIsInverseOfPointAt() {
        listOf(0.01f, 0.1f, 0.3f, 0.55f, 0.7f, 0.95f).forEach { t ->
            assertEquals(t, TreeGeometry.paramOf(rect, TreeGeometry.pointAt(rect, t)), 1e-4f)
        }
        // 縁から離れた点は、最も近い縁の点の位置になる
        assertEquals(2000f / 6000f, TreeGeometry.paramOf(rect, Point(1200f, 1000f)), 1e-4f)
    }

    @Test
    fun clipSplitsIntoTwoSidesSeparatedByGutter() {
        // 右辺の中点 → 左辺の中点。左側（a）が上のコマ
        val a = Point(1000f, 1000f)
        val b = Point(0f, 1000f)
        val top = TreeGeometry.clip(rect, a, b, 1, 10f)
        val bottom = TreeGeometry.clip(rect, a, b, -1, 10f)
        assertEquals(990f, top.maxOf { it.y }, 1e-3f)
        assertEquals(1010f, bottom.minOf { it.y }, 1e-3f)
        // 2 つの面積と境目の帯（幅 20 × 長さ 1000）の和が親の面積
        assertEquals(area(rect), area(top) + area(bottom) + 20f * 1000f, 1f)
    }

    @Test
    fun clipOfSlantedLineStaysInsideParent() {
        val a = TreeGeometry.pointAt(rect, 0.2f)
        val b = TreeGeometry.pointAt(rect, 0.85f)
        listOf(1, -1).forEach { s ->
            TreeGeometry.clip(rect, a, b, s, 13f).forEach { p ->
                assertTrue(p.x in -1e-3f..1000.001f && p.y in -1e-3f..2000.001f, "親の外の点 $p")
            }
        }
        assertEquals(area(rect), area(TreeGeometry.clip(rect, a, b, 1, 0f)) + area(TreeGeometry.clip(rect, a, b, -1, 0f)), 1f)
    }

    @Test
    fun clipReturnsEmptyWhenNothingRemains() {
        // 上辺に重なる右向きの線。画面の座標は y が下向きなので、右側（-1）は上辺の外で、何も残らない
        assertEquals(emptyList(), TreeGeometry.clip(rect, Point(0f, 0f), Point(1000f, 0f), -1, 5f))
    }
}

class LayoutResolverTest {
    private fun leaf(
        id: String,
        art: PanelArt? = null,
    ) = LayoutNode.Leaf(Panel(PanelId(id), PanelContent.Folder(emptyList()), art = art))

    // 右辺の中点 → 左辺の中点（1000×2000 の周で 1/3 と 5/6）
    private val half = LayoutNode.Split(1f / 3f, 5f / 6f, leaf("top"), leaf("bottom"))

    private fun tree(
        root: LayoutNode,
        frame: Frame = Frame(refWidth = 1000f, margin = 0f, gutter = 20f, bleed = true),
    ) = Layout(LayoutShape.Tree(root, frame))

    @Test
    fun splitsRootWithGutter() {
        val r = LayoutResolver.resolve(tree(half), 1000f, 2000f)
        assertEquals(listOf("top", "bottom"), r.panels.map { it.panel.id.value })
        assertEquals(990f, r.panels[0].polygon.bounds.bottom, 1e-3f)
        assertEquals(1010f, r.panels[1].polygon.bounds.top, 1e-3f)
        assertEquals(1, r.cuts.size)
        assertEquals("", r.cuts[0].path)
    }

    @Test
    fun marginShrinksRootUnlessBleed() {
        val framed = LayoutResolver.resolve(tree(half, Frame(refWidth = 1000f, margin = 24f, gutter = 20f)), 1000f, 2000f)
        assertEquals(24f, framed.panels[0].polygon.bounds.left, 1e-3f)
        assertEquals(24f, framed.panels[0].polygon.bounds.top, 1e-3f)
        val bled = LayoutResolver.resolve(tree(half, Frame(refWidth = 1000f, margin = 24f, gutter = 20f, bleed = true)), 1000f, 2000f)
        assertEquals(0f, bled.panels[0].polygon.bounds.top, 1e-3f)
    }

    @Test
    fun lengthsScaleWithScreenWidth() {
        // 幅が半分の画面では、余白・コマの間を含めて形がちょうど半分になる
        val frame = Frame(refWidth = 1000f, margin = 24f, gutter = 20f)
        val big = LayoutResolver.resolve(tree(half, frame), 1000f, 2000f)
        val small = LayoutResolver.resolve(tree(half, frame), 500f, 1000f)
        big.panels.zip(small.panels).forEach { (b, s) ->
            b.polygon.points.zip(s.polygon.points).forEach { (p, q) -> assertNear(Point(p.x / 2f, p.y / 2f), q) }
        }
    }

    @Test
    fun sameTreeFollowsScreenAspect() {
        // 縦横比の違う画面でも、線の端は縁の同じ割合の位置（ここでは左右の辺の中点）に来る
        val r = LayoutResolver.resolve(tree(half, Frame(refWidth = 1000f, margin = 0f, gutter = 0f, bleed = true)), 1000f, 2000f)
        assertNear(Point(1000f, 1000f), r.cuts[0].a)
        val wide = LayoutResolver.resolve(tree(LayoutNode.Split(0.375f, 0.875f, leaf("t"), leaf("b")), Frame(refWidth = 1000f, gutter = 0f, bleed = true)), 1000f, 1000f)
        assertNear(Point(1000f, 500f), wide.cuts[0].a)
        assertNear(Point(0f, 500f), wide.cuts[0].b)
    }

    @Test
    fun nestedSplitsKeepOrderAndPaths() {
        val root = LayoutNode.Split(1f / 3f, 5f / 6f, leaf("a"), LayoutNode.Split(0.6f, 0.1f, leaf("ba"), leaf("bb")))
        val r = LayoutResolver.resolve(tree(root), 1000f, 2000f)
        assertEquals(listOf("a", "ba", "bb"), r.panels.map { it.panel.id.value })
        assertEquals(listOf(0, 1, 2), r.panels.map { it.index })
        assertEquals(listOf("", "b"), r.cuts.map { it.path })
    }

    @Test
    fun emptyPanelIsSkippedButKeepsIndexOfOthers() {
        // 上辺の上を通る線で割ると、上側は空になる
        val root = LayoutNode.Split(0.1f, 0.05f, leaf("gone"), leaf("rest"))
        val r = LayoutResolver.resolve(tree(root), 1000f, 2000f)
        assertEquals(listOf("rest"), r.panels.map { it.panel.id.value })
        assertEquals(1, r.panels[0].index)
    }

    @Test
    fun artWithoutAspectIsNotPlaced() {
        val r = LayoutResolver.resolve(tree(leaf("x", PanelArt("img"))), 1000f, 2000f)
        assertEquals(null, r.panels[0].art)
        val placed = LayoutResolver.resolve(tree(leaf("x", PanelArt("img", aspect = 16f / 9f))), 1000f, 2000f)
        assertCovers(placed.panels[0].polygon.bounds, placed.panels[0].art!!, 0f)
    }

    @Test
    fun slitPanelIsSkipped() {
        // コマの間の幅がちょうどコマの幅に等しいと、頂点は残るが幅が 0 になる
        val root = LayoutNode.Split(0.5f / 6f, 3.5f / 6f, leaf("left", PanelArt("i", 1f)), leaf("right", PanelArt("i", 1f)))
        val r = LayoutResolver.resolve(tree(root, Frame(refWidth = 1000f, gutter = 1000f, bleed = true)), 1000f, 2000f)
        assertTrue(r.panels.all { it.polygon.bounds.width >= 0.5f && it.polygon.bounds.height >= 0.5f })
        assertTrue(r.panels.all { p -> p.art?.let { listOf(it.left, it.right).all(Float::isFinite) } ?: true })
    }

    @Test
    fun layoutOfStacksPanelsTopToBottom() {
        val layout = Layout.of(listOf("1", "2", "3").map { Panel(PanelId(it), PanelContent.Drawer) })
        val r = LayoutResolver.resolve(layout, 1080f, 1920f)
        assertEquals(listOf("1", "2", "3"), r.panels.map { it.panel.id.value })
        assertTrue(r.panels[0].polygon.centroid.y < r.panels[1].polygon.centroid.y)
        assertTrue(r.panels[1].polygon.centroid.y < r.panels[2].polygon.centroid.y)
    }
}

class ArtLimitsTest {
    private val wide = 1400f / 788f

    @Test
    fun zoomOneCoversBoundsExactlyOnOneAxis() {
        val b = Rect(0f, 0f, 1000f, 400f)
        val p = ArtLimits.placement(b, PanelArt("i", wide))
        // 横長のコマは幅で合わせる
        assertEquals(1000f, p.width, 1e-2f)
        assertEquals(1000f / wide, p.height, 1e-2f)
        val tall = Rect(0f, 0f, 400f, 1000f)
        val q = ArtLimits.placement(tall, PanelArt("i", wide))
        assertEquals(1000f, q.height, 1e-2f)
    }

    @Test
    fun placementAlwaysCoversBounds() {
        // 拡大率・ずらし・コマの形・視差の余白を変えても、絵の長方形がコマの外接長方形を覆う
        val rnd = Random(20261120)
        repeat(500) {
            val w = 50f + rnd.nextFloat() * 1200f
            val h = 50f + rnd.nextFloat() * 2600f
            val b = Rect(10f, 20f, 10f + w, 20f + h)
            val art =
                PanelArt(
                    image = "i",
                    aspect = listOf(wide, 0.5f, 1f, 3f).random(rnd),
                    zoom = listOf(0.5f, 1f, 1.7f, 3f, 5f).random(rnd),
                    dx = (rnd.nextFloat() - 0.5f) * 4f,
                    dy = (rnd.nextFloat() - 0.5f) * 4f,
                )
            val m = listOf(0f, 7f, 26f).random(rnd)
            assertCovers(b, ArtLimits.placement(b, art, m), m)
            // 収め直した値でも同じ位置に置かれる（収め直しで絵が跳ばない）
            val clamped = ArtLimits.clamp(b, art, m)
            if (art.zoom in ArtLimits.MIN_ZOOM..ArtLimits.MAX_ZOOM) {
                assertNear(ArtLimits.placement(b, art, m), ArtLimits.placement(b, clamped, m))
            }
            assertCovers(b, ArtLimits.placement(b, clamped, m), m)
        }
    }

    @Test
    fun clampKeepsValuesInsideRange() {
        val b = Rect(0f, 0f, 1000f, 400f)
        val c = ArtLimits.clamp(b, PanelArt("i", wide, zoom = 9f, dx = 5f, dy = -5f))
        assertEquals(ArtLimits.MAX_ZOOM, c.zoom)
        val p = ArtLimits.placement(b, c)
        // 右へ寄せきると、絵の左端がコマの左端にそろう
        assertEquals(0f, p.left, 1e-2f)
        assertEquals(400f, p.bottom, 1e-2f)
        assertEquals(1f, ArtLimits.clamp(b, PanelArt("i", wide, zoom = 0.2f)).zoom)
    }

    @Test
    fun zeroSizedBoundsDoNotProduceNaN() {
        // 幅か高さが 0 のコマ、範囲外の拡大率・縦横比でも、有限の長方形を返す
        listOf(Rect(5f, 5f, 5f, 100f), Rect(0f, 0f, 0f, 0f)).forEach { b ->
            listOf(PanelArt("i", wide), PanelArt("i", Float.NaN, zoom = Float.POSITIVE_INFINITY), PanelArt("i", 0f, zoom = 1e30f)).forEach { a ->
                val p = ArtLimits.placement(b, a)
                assertTrue(listOf(p.left, p.top, p.right, p.bottom).all { it.isFinite() }, "$b $a → $p")
            }
        }
    }

    @Test
    fun centeredArtStaysCentered() {
        val b = Rect(100f, 100f, 500f, 900f)
        val p = ArtLimits.placement(b, PanelArt("i", wide, zoom = 1.4f))
        assertNear(b.center, p.center)
    }
}

private fun area(poly: List<Point>): Float {
    var s = 0f
    for (i in poly.indices) {
        val p = poly[i]
        val q = poly[(i + 1) % poly.size]
        s += p.x * q.y - q.x * p.y
    }
    return abs(s) / 2f
}

private fun assertNear(
    expected: Point,
    actual: Point,
    eps: Float = 1e-2f,
) {
    assertTrue(abs(expected.x - actual.x) < eps && abs(expected.y - actual.y) < eps, "$expected と $actual が違う")
}

private fun assertNear(
    expected: Rect,
    actual: Rect,
) {
    assertNear(Point(expected.left, expected.top), Point(actual.left, actual.top))
    assertNear(Point(expected.right, expected.bottom), Point(actual.right, actual.bottom))
}

/** [art] が [b] を、上下左右に [m] ずつ広げた範囲まで覆う。 */
private fun assertCovers(
    b: Rect,
    art: Rect,
    m: Float,
) {
    val eps = 1e-2f
    assertTrue(
        art.left <= b.left - m + eps && art.top <= b.top - m + eps && art.right >= b.right + m - eps && art.bottom >= b.bottom + m - eps,
        "$art が $b（余白 $m）を覆っていない",
    )
}
