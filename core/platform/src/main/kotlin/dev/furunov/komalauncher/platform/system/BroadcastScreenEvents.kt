package dev.furunov.komalauncher.platform.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import dev.furunov.komalauncher.model.ports.ScreenEvent
import dev.furunov.komalauncher.model.ports.ScreenEvents
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 画面の消灯とロック解除を受ける。Application の寿命で 1 度だけ登録する。
 * Activity で登録すると、画面が消えた時点で onStop になり、解除の通知を受け取れないため。
 */
class BroadcastScreenEvents(
    context: Context,
) : ScreenEvents {
    private val flow = MutableSharedFlow<ScreenEvent>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<ScreenEvent> = flow
    private val pending = MutableStateFlow(false)
    override val unlockPending: StateFlow<Boolean> = pending

    init {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    c: Context,
                    intent: Intent,
                ) {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            // 消灯の時点で印を立てる。解除の直後に別のアプリが前に出ても、次にホームへ戻ったときに再生できる
                            pending.value = true
                            flow.tryEmit(ScreenEvent.ScreenOff)
                        }

                        Intent.ACTION_USER_PRESENT -> flow.tryEmit(ScreenEvent.UserPresent)
                    }
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
        // システムの broadcast だけを受けるので、他のアプリからは送れないようにする
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun consumeUnlock() {
        pending.value = false
    }

    /** debug ビルドの再生の口から呼ぶ。 */
    fun simulateUnlock() {
        pending.value = true
        flow.tryEmit(ScreenEvent.UserPresent)
    }
}
