package dev.furunov.komalauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Waydroid にはロック画面が無いので、adb からロック解除のアニメーションを再生する。 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == "dev.furunov.komalauncher.DEBUG_PLAY_UNLOCK") {
            (context.applicationContext as KomaApplication).container.screenEvents.simulateUnlock()
        }
    }
}
