package dev.furunov.komalauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import dev.furunov.komalauncher.model.layout.Tilt

/** Waydroid にはロック画面が無いので、adb からロック解除のアニメーションを再生する。 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == "dev.furunov.komalauncher.DEBUG_PLAY_UNLOCK") {
            (context.applicationContext as KomaApplication).container.screenEvents.simulateUnlock()
        }
        // センサーの無い Waydroid で視差を確認する。例: am broadcast -a dev.furunov.komalauncher.DEBUG_TILT --ef x 1 --ef y -0.5
        // --ef period <秒> を付けると、左右 x・前後 y を半径とする円を period 秒で一周する動きを、dur 秒（デフォルト 12）続ける。
        // 値を毎フレーム変えるので、adb から少しずつ送るよりも滑らかに動く（動画を撮るとき）
        if (intent.action == "dev.furunov.komalauncher.DEBUG_TILT") {
            val tilt = (context.applicationContext as KomaApplication).container.tilt
            val x = intent.getFloatExtra("x", 0f)
            val y = intent.getFloatExtra("y", 0f)
            val period = intent.getFloatExtra("period", 0f)
            TiltAnimation.stop()
            if (period > 0f) TiltAnimation.start(x, y, period, intent.getFloatExtra("dur", 12f)) { tilt.simulate(it) } else tilt.simulate(Tilt(x, y))
        }
        // 音楽アプリにログインできない Waydroid で、再生中のコマを確認する。例: am broadcast -a dev.furunov.komalauncher.DEBUG_MEDIA --es state play
        if (intent.action == "dev.furunov.komalauncher.DEBUG_MEDIA") {
            DebugMediaSession.handle(context, intent.getStringExtra("state"))
        }
        // ブラウザの無い Waydroid で、ホーム画面への追加の依頼を確認する。自分自身のショートカットの固定を依頼する。
        // 例: am broadcast -p dev.furunov.komalauncher -a dev.furunov.komalauncher.DEBUG_PIN --ei n 1
        // --ez update true を付けると、固定済みのショートカットの名前とアイコンを変える（ブラウザの側でアイコンが変わった場合を再現する）。色は --ei color <ARGB の整数> で変える
        if (intent.action == "dev.furunov.komalauncher.DEBUG_PIN") {
            val n = intent.getIntExtra("n", 1)
            val update = intent.getBooleanExtra("update", false)
            val shortcut =
                ShortcutInfo
                    .Builder(context, "debug-pin-$n")
                    .setShortLabel(if (update) "テスト $n 更新" else "テスト $n")
                    .setIcon(if (update) Icon.createWithBitmap(Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(intent.getIntExtra("color", 0xFF2BB5D8.toInt())) }) else Icon.createWithResource(context, R.mipmap.ic_launcher))
                    .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
                    .build()
            val manager = context.getSystemService(ShortcutManager::class.java)
            if (update) manager.updateShortcuts(listOf(shortcut)) else manager.requestPinShortcut(shortcut, null)
        }
        // 傾けている間の描画の重さを効果ごとに測る。例: am broadcast -p dev.furunov.komalauncher -a dev.furunov.komalauncher.DEBUG_FX --es off shadow,rim
        if (intent.action == "dev.furunov.komalauncher.DEBUG_FX") {
            dev.furunov.komalauncher.feature.home.HomeDebug.off = intent.getStringExtra("off").orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
        // 動画を撮るときに、システムバーを隠す。例: am broadcast -p dev.furunov.komalauncher -a dev.furunov.komalauncher.DEBUG_BARS --ez hide true
        if (intent.action == "dev.furunov.komalauncher.DEBUG_BARS") {
            dev.furunov.komalauncher.feature.home.HomeDebug.hideBars = intent.getBooleanExtra("hide", true)
        }
        if (intent.action == "dev.furunov.komalauncher.DEBUG_VIS") {
            DebugVisualizer.handle(
                context,
                intent.getIntExtra("session", 0),
                intent.getBooleanExtra("listen", false),
                intent.getIntExtra("use", -1),
                intent.getIntExtra("scaling", -1),
                intent.getLongExtra("logms", 3000L),
                intent.getLongExtra("interval", 100L),
                intent.getIntExtra("record", 0),
            )
        }
    }
}

/** debug ビルドで、傾きを円に沿って毎フレーム動かす。始めと終わりの 1 秒で、半径を 0 から広げ、0 へ縮める。 */
private object TiltAnimation {
    private var callback: android.view.Choreographer.FrameCallback? = null

    fun stop() {
        callback?.let { android.view.Choreographer.getInstance().removeFrameCallback(it) }
        callback = null
    }

    fun start(
        x: Float,
        y: Float,
        period: Float,
        duration: Float,
        set: (Tilt) -> Unit,
    ) {
        var start = -1L
        val cb =
            object : android.view.Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (start < 0) start = frameTimeNanos
                    val t = (frameTimeNanos - start) / 1e9f
                    if (t >= duration) {
                        set(Tilt(0f, 0f))
                        callback = null
                        return
                    }
                    val k = minOf(1f, t, duration - t)
                    val a = (2 * Math.PI * t / period).toFloat()
                    set(Tilt(x * k * kotlin.math.sin(a), y * k * kotlin.math.cos(a)))
                    android.view.Choreographer.getInstance().postFrameCallback(this)
                }
            }
        callback = cb
        android.view.Choreographer.getInstance().postFrameCallback(cb)
    }
}
