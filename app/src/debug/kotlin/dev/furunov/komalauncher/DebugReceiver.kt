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
        // センサーの無い Waydroid で視差を確かめる。例: am broadcast -a dev.furunov.komalauncher.DEBUG_TILT --ef x 1 --ef y -0.5
        if (intent.action == "dev.furunov.komalauncher.DEBUG_TILT") {
            (context.applicationContext as KomaApplication).container.tilt.simulate(Tilt(intent.getFloatExtra("x", 0f), intent.getFloatExtra("y", 0f)))
        }
        // 音楽アプリにログインできない Waydroid で、再生中のコマを確かめる。例: am broadcast -a dev.furunov.komalauncher.DEBUG_MEDIA --es state play
        if (intent.action == "dev.furunov.komalauncher.DEBUG_MEDIA") {
            DebugMediaSession.handle(context, intent.getStringExtra("state"))
        }
        // ブラウザの無い Waydroid で、ホーム画面への追加の依頼を確かめる。自分自身のショートカットの固定を依頼する。
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
