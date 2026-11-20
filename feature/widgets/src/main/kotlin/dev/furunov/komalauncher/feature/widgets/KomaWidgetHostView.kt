package dev.furunov.komalauncher.feature.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * 長押しの間はウィジェットにタッチを渡さないホストのビュー。
 *
 * ホーム画面（Compose）はウィジェットの上の長押しも見分けてコマのメニューを開くが、ウィジェットのビューにも
 * 同じタッチが届いている。そのままだと指を離したときにウィジェットがタップとして扱い、時計のウィジェットなら
 * 時計のアプリを開いて、メニューを隠してしまう（2026-11-20 に Waydroid で再現）。
 * そこで Launcher3 と同じく、長押しの時間が過ぎたら以降のタッチを取り上げ、ウィジェットには ACTION_CANCEL を送る。
 * 長押しの判定と時間は ViewConfiguration の値を使い、Compose の判定（HomeGestures）とそろえる。
 */
class KomaWidgetHostView(
    context: Context,
) : AppWidgetHostView(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var stolen = false
    private val steal = Runnable { stolen = true }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stolen = false
                downX = ev.x
                downY = ev.y
                postDelayed(steal, ViewConfiguration.getLongPressTimeout().toLong())
            }

            MotionEvent.ACTION_MOVE ->
                // 動かしたら長押しではない（スクロールするウィジェットにタッチを渡し続ける）
                if (!stolen && hypot(ev.x - downX, ev.y - downY) > slop) removeCallbacks(steal)

            // 2 本目の指はホームの 2 本指のジェスチャーなので、長押しにしない
            MotionEvent.ACTION_POINTER_DOWN -> removeCallbacks(steal)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(steal)
        }
        return stolen
    }

    /** 取り上げた後のタッチはここで受け取り、ウィジェットには渡さない（親の Compose は同じタッチを別に受け取っている）。 */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(steal)
            val wasStolen = stolen
            stolen = false
            return wasStolen
        }
        return stolen || super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(steal)
        super.onDetachedFromWindow()
    }
}

/** [KomaWidgetHostView] を作るホスト。 */
internal class KomaWidgetHost(
    context: Context,
    hostId: Int,
) : AppWidgetHost(context, hostId) {
    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?,
    ): AppWidgetHostView = KomaWidgetHostView(context)
}
