package dev.furunov.komalauncher.feature.widgets

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.SizeF
import androidx.compose.runtime.mutableStateOf
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.widgets.WidgetEdits
import dev.furunov.komalauncher.model.widgets.WidgetEntry
import dev.furunov.komalauncher.model.widgets.WidgetRef
import dev.furunov.komalauncher.model.widgets.WidgetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** 選択画面に並べるウィジェット 1 つ。 */
class WidgetChoice(
    val info: AppWidgetProviderInfo,
    val label: String,
    val appLabel: String,
    /** ウィジェットが求める最小の大きさ（dp）。自由に置くときの最初の大きさに使う。 */
    val widthDp: Float,
    val heightDp: Float,
) {
    val entry: WidgetEntry get() = WidgetEntry(info.provider.flattenToString(), label, appLabel, info.provider.packageName)
}

/** 選択画面で選んだウィジェットを置く先。 */
sealed interface PickTarget {
    /** コマから独立して置く。 */
    data object Free : PickTarget

    /** 自由に置いたウィジェット [widgetId] の中身を、位置と大きさを保ったまま入れ替える。 */
    data class Replace(
        val widgetId: String,
    ) : PickTarget
}

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

    // 長押しの間はウィジェットにタッチを渡さないビューを作る（KomaWidgetHostView）
    private val host: AppWidgetHost = KomaWidgetHost(context, HOST_ID)
    private val views = mutableMapOf<Int, AppWidgetHostView>()

    /** bind や configure の結果を待っているウィジェット。 */
    private var pending: Pending? = null

    /** ウィジェットの選択画面で選んだものを置く先（null なら選択画面を出していない）。 */
    val pickerFor = mutableStateOf<PickTarget?>(null)

    private class Pending(
        val widgetId: String,
        val appWidgetId: Int,
        val info: AppWidgetProviderInfo,
        /** 置き終えたときに呼ぶ（自由に置くウィジェットなら、ここでコマ割りに書く）。 */
        val onPlaced: () -> Unit = {},
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
                val d = context.resources.displayMetrics.density
                WidgetChoice(info, info.loadLabel(pm), appLabel, info.minWidth / d, info.minHeight / d)
            }.sortedWith(compareBy({ it.appLabel }, { it.label }))
    }

    /** provider（"パッケージ名/クラス名"）のウィジェットの名前。入っていなければ null。 */
    fun label(provider: String): String? = runCatching {
        manager.installedProviders.firstOrNull { it.provider.flattenToString() == provider }?.loadLabel(context.packageManager)
    }.getOrNull()

    /** 選択画面の一覧。名前を読むのに時間がかかるので、メインスレッドの外で読む。 */
    suspend fun loadChoices(): List<WidgetChoice> = withContext(Dispatchers.IO) { choices() }

    // 選択画面の見本とアイコン。スクロールで同じ項目が現れるたびに読み直さないよう覚えておく
    private val previews = ConcurrentHashMap<String, Optional<Drawable>>()
    private val icons = ConcurrentHashMap<String, Optional<Drawable>>()

    /** 見本のレイアウト（previewLayout）を展開するスレッド。他のアプリのレイアウトの展開は重いので、メインスレッドで行わない。 */
    val previewExecutor: Executor = Executors.newFixedThreadPool(2)

    /** アプリのアイコン（選択画面の見出しに使う）。 */
    suspend fun appIcon(packageName: String): Drawable? = withContext(Dispatchers.IO) {
        icons.getOrPut(packageName) { Optional.ofNullable(runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()) }.orElse(null)
    }

    /** 見本の画像。無ければウィジェットのアイコン。 */
    suspend fun previewImage(c: WidgetChoice): Drawable? = withContext(Dispatchers.IO) {
        previews.getOrPut(c.entry.key) {
            val dpi = context.resources.displayMetrics.densityDpi
            Optional.ofNullable(runCatching { c.info.loadPreviewImage(context, dpi) ?: c.info.loadIcon(context, dpi) }.getOrNull())
        }.orElse(null)
    }

    fun view(appWidgetId: Int): AppWidgetHostView? {
        views[appWidgetId]?.let { return it }
        val info = manager.getAppWidgetInfo(appWidgetId) ?: return null
        return host.createView(context.applicationContext, appWidgetId, info).also { views[appWidgetId] = it }
    }

    /** アプリから新しい中身が届いた回数（ビューを作る前なら null）。 */
    fun updates(appWidgetId: Int): StateFlow<Long>? = (views[appWidgetId] as? KomaWidgetHostView)?.updates

    /** 置く大きさ（dp）をウィジェットに伝える。伝えないと、ウィジェットは最小の大きさで描かれる。 */
    fun updateSize(
        appWidgetId: Int,
        widthDp: Float,
        heightDp: Float,
    ) {
        views[appWidgetId]?.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp, heightDp)))
    }

    /**
     * 選んだウィジェットを、自由に置くウィジェット [widgetId] に割り当てる。許可や設定の画面が要れば Activity から開く。
     * 許可や設定を取り消したときは [onPlaced] を呼ばない。
     */
    fun place(
        activity: Activity,
        widgetId: String,
        choice: WidgetChoice,
        onPlaced: () -> Unit = {},
    ) {
        pickerFor.value = null
        val id = host.allocateAppWidgetId()
        val p = Pending(widgetId, id, choice.info, onPlaced)
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

    /** ウィジェットを外し、確保していた id を返す。 */
    fun remove(widgetId: String) {
        val ref = store.assignments.value.byPanel[widgetId] ?: return
        host.deleteAppWidgetId(ref.appWidgetId)
        views.remove(ref.appWidgetId)
        store.update { it.copy(byPanel = it.byPanel - widgetId) }
    }

    /**
     * layout.json から消えたウィジェットを外す。外さないと、見えないウィジェットが更新を受け続け、
     * 外すための長押しのメニューも出せない。呼ぶのは layout.json を正しく読めたときだけ。
     */
    fun removeOrphans(layout: Layout) {
        WidgetEdits.orphans(store.assignments.value, layout).forEach(::remove)
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
        store.assignments.value.byPanel[p.widgetId]?.let {
            host.deleteAppWidgetId(it.appWidgetId)
            views.remove(it.appWidgetId)
        }
        store.update { it.copy(byPanel = it.byPanel + (p.widgetId to WidgetRef(p.appWidgetId, p.info.provider.flattenToString()))) }
        p.onPlaced()
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
