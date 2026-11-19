package dev.furunov.komalauncher.feature.widgets

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.SizeF
import androidx.compose.runtime.mutableStateOf
import dev.furunov.komalauncher.model.widgets.WidgetRef
import dev.furunov.komalauncher.model.widgets.WidgetStore

/** 選択画面に並べるウィジェット 1 つ。 */
class WidgetChoice(
    val info: AppWidgetProviderInfo,
    val label: String,
    val appLabel: String,
)

/**
 * ウィジェットの確保・bind・設定・表示をまとめる。
 * bind の許可と configure の画面は Activity の結果で返ってくるので、
 * MainActivity が [onActivityResult] をここへ渡す。
 */
class WidgetHostController(
    private val context: Context,
    private val store: WidgetStore,
) {
    private val manager = AppWidgetManager.getInstance(context)
    private val host = AppWidgetHost(context, HOST_ID)
    private val views = mutableMapOf<Int, AppWidgetHostView>()

    /** bind や configure の結果を待っているコマ。 */
    private var pending: Pending? = null

    /** ウィジェットの選択画面を出しているコマ（null なら出していない）。 */
    val pickerFor = mutableStateOf<String?>(null)

    private class Pending(
        val panelId: String,
        val appWidgetId: Int,
        val info: AppWidgetProviderInfo,
    )

    /** Activity の onStart / onStop から呼ぶ。止めている間はウィジェットの更新を受けない。 */
    fun startListening() = host.startListening()

    fun stopListening() = host.stopListening()

    fun choices(): List<WidgetChoice> {
        val pm = context.packageManager
        return manager.installedProviders
            .map { info ->
                val appLabel =
                    runCatching { pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString() }
                        .getOrDefault(info.provider.packageName)
                WidgetChoice(info, info.loadLabel(pm), appLabel)
            }.sortedWith(compareBy({ it.appLabel }, { it.label }))
    }

    fun view(appWidgetId: Int): AppWidgetHostView? {
        views[appWidgetId]?.let { return it }
        val info = manager.getAppWidgetInfo(appWidgetId) ?: return null
        return host.createView(context.applicationContext, appWidgetId, info).also { views[appWidgetId] = it }
    }

    /** コマの大きさ（dp）をウィジェットに伝える。伝えないと、ウィジェットは最小の大きさで描かれる。 */
    fun updateSize(
        appWidgetId: Int,
        widthDp: Float,
        heightDp: Float,
    ) {
        views[appWidgetId]?.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp, heightDp)))
    }

    /** 選んだウィジェットを [panelId] に置く。許可や設定の画面が要れば Activity から開く。 */
    fun place(
        activity: Activity,
        panelId: String,
        choice: WidgetChoice,
    ) {
        pickerFor.value = null
        val id = host.allocateAppWidgetId()
        val p = Pending(panelId, id, choice.info)
        pending = p
        if (manager.bindAppWidgetIdIfAllowed(id, choice.info.provider)) {
            configureOrFinish(activity, p)
        } else {
            val intent =
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, choice.info.provider)
            activity.startActivityForResult(intent, REQUEST_BIND)
        }
    }

    fun onActivityResult(
        activity: Activity,
        requestCode: Int,
        resultCode: Int,
    ): Boolean {
        val p = pending ?: return false
        when (requestCode) {
            REQUEST_BIND ->
                if (resultCode == Activity.RESULT_OK) configureOrFinish(activity, p) else abandon(p)

            REQUEST_CONFIGURE ->
                if (resultCode == Activity.RESULT_OK) finish(p) else abandon(p)

            else -> return false
        }
        return true
    }

    /** コマのウィジェットを外し、確保していた id を返す。 */
    fun remove(panelId: String) {
        val ref = store.assignments.value.byPanel[panelId] ?: return
        host.deleteAppWidgetId(ref.appWidgetId)
        views.remove(ref.appWidgetId)
        store.update { it.copy(byPanel = it.byPanel - panelId) }
    }

    private fun configureOrFinish(
        activity: Activity,
        p: Pending,
    ) {
        if (p.info.configure != null) {
            // 他のアプリの設定画面を、そのアプリの権限で開く。直接 startActivity すると SecurityException になることがある
            host.startAppWidgetConfigureActivityForResult(activity, p.appWidgetId, 0, REQUEST_CONFIGURE, null)
        } else {
            finish(p)
        }
    }

    private fun finish(p: Pending) {
        pending = null
        // 前に置いていたウィジェットは外す（id を残すと、見えないウィジェットが更新を受け続ける）
        store.assignments.value.byPanel[p.panelId]?.let {
            host.deleteAppWidgetId(it.appWidgetId)
            views.remove(it.appWidgetId)
        }
        store.update { it.copy(byPanel = it.byPanel + (p.panelId to WidgetRef(p.appWidgetId, p.info.provider.flattenToString()))) }
    }

    private fun abandon(p: Pending) {
        pending = null
        host.deleteAppWidgetId(p.appWidgetId)
    }

    companion object {
        /** このアプリの AppWidgetHost の番号。変えると、置いたウィジェットがすべて見えなくなる。 */
        const val HOST_ID = 0x4B4D
        const val REQUEST_BIND = 0x4B01
        const val REQUEST_CONFIGURE = 0x4B02
    }
}
