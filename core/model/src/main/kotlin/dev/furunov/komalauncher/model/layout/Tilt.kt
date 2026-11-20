package dev.furunov.komalauncher.model.layout

import kotlin.math.abs
import kotlin.math.sign

/** 端末の傾き。x・y とも −1〜1（±20 度で端）。x は左右（ロール）、y は前後（ピッチ）。 */
data class Tilt(
    val x: Float = 0f,
    val y: Float = 0f,
) {
    companion object {
        val ZERO = Tilt()

        /** これを超える角度は同じ量として扱う（モックアップと同じ ±20 度）。 */
        const val FULL_DEGREES = 20f

        /** これ未満の揺れは無視する（手の小さな震えで絵が揺れないように）。 */
        const val DEAD_DEGREES = 1f

        /** 1 フレームで目標へ寄せる割合。急に跳ばないよう、少しずつ寄せる。 */
        const val FOLLOW = 0.08f

        /** 持ち始めの角度 [base] からの差（度）を、傾きにする。 */
        fun fromDegrees(
            rollDeg: Float,
            pitchDeg: Float,
            baseRollDeg: Float,
            basePitchDeg: Float,
        ): Tilt = Tilt(scale(rollDeg - baseRollDeg), scale(pitchDeg - basePitchDeg))

        private fun scale(d: Float): Float {
            val v = if (abs(d) < DEAD_DEGREES) 0f else d - sign(d) * DEAD_DEGREES
            return (v / FULL_DEGREES).coerceIn(-1f, 1f)
        }

        /** [current] を [target] へ 1 フレーム分寄せる。十分近ければ目標そのものを返す（描き直しを止めるため）。 */
        fun follow(
            current: Tilt,
            target: Tilt,
        ): Tilt {
            val nx = current.x + (target.x - current.x) * FOLLOW
            val ny = current.y + (target.y - current.y) * FOLLOW
            return if (abs(target.x - nx) < 0.002f && abs(target.y - ny) < 0.002f) target else Tilt(nx, ny)
        }
    }
}
