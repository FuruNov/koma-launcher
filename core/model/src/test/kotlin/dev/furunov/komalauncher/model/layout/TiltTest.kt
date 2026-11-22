package dev.furunov.komalauncher.model.layout

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

class TiltTest {
    @Test
    fun smallShakesAreIgnored() {
        assertEquals(Tilt.ZERO, Tilt.fromDegrees(10.9f, 9.2f, 10f, 10f))
    }

    @Test
    fun scalesAndClamps() {
        // 1 度を引いてから 20 度で割る
        assertEquals(0.5f, Tilt.fromDegrees(11f, 0f, 0f, 0f).x, 1e-5f)
        assertEquals(-0.5f, Tilt.fromDegrees(0f, -11f, 0f, 0f).y, 1e-5f)
        assertEquals(1f, Tilt.fromDegrees(80f, 0f, 0f, 0f).x)
    }

    @Test
    fun followApproachesAndStops() {
        var t = Tilt.ZERO
        val target = Tilt(1f, -1f)
        t = Tilt.follow(t, target)
        assertEquals(0.08f, t.x, 1e-5f)
        repeat(200) { t = Tilt.follow(t, target) }
        // 十分近づいたら目標そのものになり、それ以上は変わらない
        assertEquals(target, t)
    }

    @Test
    fun shiftsFollowMockupAmounts() {
        assertEquals(0f, FxSpec().artShift())
        val fx = FxSpec(parallax = true, strength = 0.5f)
        assertEquals(13f, fx.artShift())
        assertEquals(9f, fx.clockShift())
        assertEquals(3.5f, fx.textShift())
        // 大見出しとアイコンは、名前より大きく動かす
        assertEquals(10f, fx.mastheadShift())
        assertEquals(8f, fx.iconShift())
    }

    @Test
    fun quantizeRoundsToSteps() {
        // 端で 10 画素動くなら、0.1 ずつの刻み（1 画素ずつ）にする
        assertEquals(Tilt(0.3f, -0.1f), Tilt.quantize(Tilt(0.27f, -0.12f), 0.1f).let { Tilt((it.x * 10).roundToInt() / 10f, (it.y * 10).roundToInt() / 10f) })
        // 刻みの半分より小さい変化では、同じ値のまま（描き直さない）
        assertEquals(Tilt.quantize(Tilt(0.31f, 0f), 0.1f), Tilt.quantize(Tilt(0.34f, 0f), 0.1f))
    }
}

class TiltStartTest {
    @Test
    fun smallJitterDoesNotStartMoving() {
        kotlin.test.assertFalse(Tilt.startsMoving(Tilt(0.2f, 0f), Tilt(0.25f, 0.04f), step = 0.1f))
        kotlin.test.assertTrue(Tilt.startsMoving(Tilt(0.2f, 0f), Tilt(0.31f, 0f), step = 0.1f))
        kotlin.test.assertTrue(Tilt.startsMoving(Tilt.ZERO, Tilt(0.01f, 0f), step = 0f))
    }
}
