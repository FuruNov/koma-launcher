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

    override fun start() {
        val s = sensor ?: return
        base = null
        // UI の間隔（約 60 ms）で十分。画面は目標へ少しずつ寄せて描くので、細かく読んでも見た目は変わらない
        manager?.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
    }

    override fun stop() {
        manager?.unregisterListener(this)
        mutable.value = Tilt.ZERO
    }

    /** センサーの無い環境で、傾けたときの見た目を確かめる（debug の受け口から呼ぶ）。 */
    fun simulate(tilt: Tilt) {
        mutable.value = tilt
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        SensorManager.getOrientation(rotation, angles)
        val pitch = Math.toDegrees(angles[1].toDouble()).toFloat()
        val roll = Math.toDegrees(angles[2].toDouble()).toFloat()
        val b = base ?: floatArrayOf(roll, pitch).also { base = it }
        mutable.value = Tilt.fromDegrees(roll, pitch, b[0], b[1])
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) = Unit
}
