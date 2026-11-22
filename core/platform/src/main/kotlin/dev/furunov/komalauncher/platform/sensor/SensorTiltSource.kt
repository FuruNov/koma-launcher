package dev.furunov.komalauncher.platform.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dev.furunov.komalauncher.model.layout.Tilt
import dev.furunov.komalauncher.model.ports.TiltSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/**
 * 傾きを game rotation vector（地磁気を使わない回転）から読む。地磁気を使う回転は、近くの金属で向きが跳ぶことがあるため。
 * 読み始めた時点の角度を基準にし、そこからの差を傾きにする（モックアップと同じ）。
 * センサーが無い端末（Waydroid）では何もしない。確認には [simulate] を使う。
 */
class SensorTiltSource(
    context: Context,
) : TiltSource,
    SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val mutable = MutableStateFlow(Tilt.ZERO)
    override val target: StateFlow<Tilt> = mutable
    private val rotation = FloatArray(9)
    private val angles = FloatArray(3)
    private var base: FloatArray? = null

    /** true の間は、センサーの値で目標を変えない（[simulate] で送った傾きを残す）。次の [start] で戻す。 */
    private var simulated = false

    override fun start() {
        val s = sensor ?: return
        base = null
        simulated = false
        // UI の間隔（約 60 ms）で十分。画面は目標へ少しずつ寄せて描くので、細かく読んでも見た目は変わらない
        manager?.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
    }

    override fun stop() {
        manager?.unregisterListener(this)
        mutable.value = Tilt.ZERO
    }

    /**
     * センサーの無い環境で、傾けたときの見た目を確認する（debug の受け口から呼ぶ）。
     * 実機ではセンサーも値を出すので、送った後はセンサーの値を無視する。無視しないと、送った傾きが約 60 ms 後に
     * 机の上の端末の傾き（ほぼ 0）で上書きされ、傾けて動かし続けるときの重さを測れない（2026-11-21）。
     */
    fun simulate(tilt: Tilt) {
        simulated = true
        mutable.value = tilt
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (simulated) return
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        SensorManager.getOrientation(rotation, angles)
        val pitch = Math.toDegrees(angles[1].toDouble()).toFloat()
        val roll = Math.toDegrees(angles[2].toDouble()).toFloat()
        val b = base ?: floatArrayOf(roll, pitch).also { base = it }
        val next = Tilt.fromDegrees(roll, pitch, b[0], b[1])
        // 手の小さな震えでは目標を変えない。変えると画面が目標へ寄せる描き直しを続け、何もしていなくても毎フレーム描くことになる
        // （2026-11-20 に実機で、何もしない 3 秒に 758 フレーム描いていた）
        val cur = mutable.value
        if (abs(next.x - cur.x) >= Tilt.STEP || abs(next.y - cur.y) >= Tilt.STEP || (next == Tilt.ZERO && cur != Tilt.ZERO)) mutable.value = next
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) = Unit
}
