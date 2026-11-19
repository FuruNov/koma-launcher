package dev.furunov.komalauncher.platform.system

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import dev.furunov.komalauncher.model.ports.SystemShade

/**
 * 通知センターを StatusBarManager の非公開のメソッドで開く。公開 API には無いが、
 * EXPAND_STATUS_BAR（通常の権限）で呼べ、Nova などの他社製ランチャーも同じ方法を使っている。
 * 呼べない端末では false を返すので、呼び出し側は AccessibilityService の実装に切り替える。
 */
class ReflectionSystemShade(
    private val context: Context,
) : SystemShade {
    override fun expandNotifications(): Boolean = call("expandNotificationsPanel")

    override fun expandQuickSettings(): Boolean = call("expandSettingsPanel")

    @SuppressLint("WrongConstant")
    private fun call(method: String): Boolean = try {
        val sbm = context.getSystemService("statusbar") ?: return false
        sbm.javaClass.getMethod(method).invoke(sbm)
        true
    } catch (e: Exception) {
        Log.w("SystemShade", "$method を呼べない", e)
        false
    }
}
