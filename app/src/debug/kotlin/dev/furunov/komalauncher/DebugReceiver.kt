package dev.furunov.komalauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        // センサーの無い Waydroid で視差を確かめる。例: am broadcast -a dev.furunov.komalauncher.DEBUG_TILT --ef x 1 --ef y -0.5
        if (intent.action == "dev.furunov.komalauncher.DEBUG_TILT") {
            (context.applicationContext as KomaApplication).container.tilt.simulate(Tilt(intent.getFloatExtra("x", 0f), intent.getFloatExtra("y", 0f)))
        }
    }
}
