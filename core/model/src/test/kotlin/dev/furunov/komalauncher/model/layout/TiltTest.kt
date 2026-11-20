package dev.furunov.komalauncher.model.layout

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
    }
}
