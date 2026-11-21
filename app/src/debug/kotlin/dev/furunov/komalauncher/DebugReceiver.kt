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
        // 音楽アプリにログインできない Waydroid で、再生中のコマを確かめる。例: am broadcast -a dev.furunov.komalauncher.DEBUG_MEDIA --es state play
        if (intent.action == "dev.furunov.komalauncher.DEBUG_MEDIA") {
            DebugMediaSession.handle(context, intent.getStringExtra("state"))
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
