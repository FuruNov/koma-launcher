package dev.furunov.komalauncher.platform.media

import android.app.ActivityOptions
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.furunov.komalauncher.model.media.MediaSessions
import dev.furunov.komalauncher.model.media.NowPlaying
import dev.furunov.komalauncher.model.media.PauseTracker
import dev.furunov.komalauncher.model.media.PlaybackStatus
import dev.furunov.komalauncher.model.media.PlayerRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 他のアプリの再生を MediaSessionManager で取得する。「通知へのアクセス」の許可が要る。
 * 監視はホームが前面にある間だけ（[start] から [stop] まで）行う。
 */
class MediaSessionWatcher(
    context: Context,
) : MediaSessions {
    private val app = context.applicationContext
    private val manager = app.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(app, KomaNotificationListener::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val nowPlayingFlow = MutableStateFlow<NowPlaying?>(null)
    override val nowPlaying: StateFlow<NowPlaying?> = nowPlayingFlow
    private val access = MutableStateFlow(checkAccess())
    override val hasAccess: StateFlow<Boolean> = access
    private val artFlow = MutableStateFlow<ImageBitmap?>(null)

    /** 再生中の曲のジャケット（長辺 512 画素以下）。無ければ null。 */
    val art: StateFlow<ImageBitmap?> = artFlow

    private var started = false
    private var controllers: List<MediaController> = emptyList()

    /** コントローラーごとのコールバック。終わったセッションを、そのコントローラーだけ外せるように分けて持つ。 */
    private val callbacks = HashMap<MediaController, MediaController.Callback>()
    private var current: MediaController? = null

    /** セッションごとの、一時停止になった時刻（壁時計）。一時停止の期限に使う。 */
    private val paused = PauseTracker()
    private var artKey: String? = null
    private var artJob: Job? = null

    private fun callbackFor(c: MediaController) = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()

        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()

        // 一覧の変更の通知を出さないアプリもあるので、終わったセッションはここで外す
        override fun onSessionDestroyed() {
            unregister(c)
            controllers = controllers - c
            refresh()
        }
    }

    private fun unregister(c: MediaController) {
        callbacks.remove(c)?.let(c::unregisterCallback)
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> watch(list.orEmpty()) }

    /** 通知へのアクセスが許可されているか。設定画面からも呼ぶ（監視していなくても判定できる）。 */
    fun checkAccess(): Boolean = app.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(component) == true

    override fun start() {
        if (started) return
        started = true
        access.value = checkAccess()
        if (!access.value) {
            clear()
            return
        }
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            watch(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            // 許可が取り消された直後など
            access.value = false
            clear()
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        try {
            manager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (e: Exception) {
            // 登録できていなければ何もしない
        }
        controllers.forEach(::unregister)
        controllers = emptyList()
        // 離れている間の再生・一時停止は届かないので、戻ったときに PlaybackState の更新時刻から数え直す
        paused.reset()
    }

    private fun watch(list: List<MediaController>) {
        // stop の直前にキューに積まれた一覧の変更の通知が、止めた後に届くことがある。止めた後は監視を始め直さない
        if (!started) return
        controllers.forEach(::unregister)
        controllers = list
        // 先頭が止まっていても、2 番目が一時停止中ならそれを使うので、すべてを監視する
        list.forEach { c ->
            callbackFor(c).also {
                callbacks[c] = it
                c.registerCallback(it, main)
            }
        }
        refresh()
    }

    private fun refresh() {
        if (!started) return
        val now = System.currentTimeMillis()
        val live = HashSet<String>()
        var chosen: Triple<MediaController, PlaybackStatus, Long?>? = null
        for (c in controllers) {
            val key = keyOf(c)
            live += key
            val state = c.playbackState
            val status = statusOf(state?.state)
            val pausedAt = paused.update(key, status) { pausedSince(state, now) }
            if (chosen == null && status != PlaybackStatus.STOPPED) chosen = Triple(c, status, pausedAt)
        }
        paused.retain(live)
        val (c, status, pausedAt) = chosen ?: run {
            clear()
            return
        }
        current = c
        val meta = c.metadata
        val trackKey = listOfNotNull(meta?.getString(MediaMetadata.METADATA_KEY_TITLE), meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)).joinToString("\u0000")
        nowPlayingFlow.value = NowPlaying(c.packageName, status, trackKey, pausedAt)
        loadArt(c.packageName, trackKey, meta)
    }

    private fun keyOf(c: MediaController): String = c.packageName + "\u0000" + c.sessionToken.hashCode()

    private fun clear() {
        current = null
        nowPlayingFlow.value = null
        artJob?.cancel()
        artKey = null
        artFlow.value = null
    }

    /** 監視を始める前から一時停止していたら、状態が最後に更新された時刻を一時停止の時刻とみなす。 */
    private fun pausedSince(
        state: PlaybackState?,
        now: Long,
    ): Long {
        val updated = state?.lastPositionUpdateTime ?: return now
        if (updated <= 0L) return now
        return now - max(0L, SystemClock.elapsedRealtime() - updated)
    }

    private fun loadArt(
        packageName: String,
        trackKey: String,
        meta: MediaMetadata?,
    ) {
        val bitmap = meta?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        val uri = meta?.getString(MediaMetadata.METADATA_KEY_ART_URI) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
        val identity = bitmap?.let { "bmp:${it.width}x${it.height}:${it.generationId}" } ?: uri?.let { "uri:$it" }
        val key = PlayerRules.artKey(packageName, trackKey, identity)
        if (key == artKey) return
        artKey = key
        artJob?.cancel()
        artJob =
            scope.launch {
                val loaded =
                    withContext(Dispatchers.IO) {
                        when {
                            bitmap != null -> shrink(bitmap)
                            uri != null -> readUri(uri)
                            else -> null
                        }
                    }
                artFlow.value = loaded?.asImageBitmap()
            }
    }

    /** 他のアプリのビットマップは後で再利用されることがあるので、縮めた写しを持つ。 */
    private fun shrink(b: Bitmap): Bitmap? = try {
        val s = ART_SIDE.toFloat() / max(b.width, b.height)
        if (s < 1f) {
            Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
        } else {
            b.copy(Bitmap.Config.ARGB_8888, false)
        }
    } catch (e: Exception) {
        null
    }

    private fun readUri(uri: String): Bitmap? = try {
        app.contentResolver.loadThumbnail(Uri.parse(uri), Size(ART_SIDE, ART_SIDE), null)
    } catch (e: Exception) {
        null
    }

    override fun togglePlayPause() {
        val c = current ?: return
        if (statusOf(c.playbackState?.state) == PlaybackStatus.PAUSED) c.transportControls.play() else c.transportControls.pause()
    }

    override fun next() {
        current?.transportControls?.skipToNext()
    }

    override fun previous() {
        current?.transportControls?.skipToPrevious()
    }

    override fun openPlayer() {
        val c = current ?: return
        try {
            val pending = c.sessionActivity
            if (pending != null) {
                // Android 14 以降は、送る側（前面のランチャー）が起動を許すと明示しないと、裏にいる音楽アプリの画面を開けない
                pending.send(app, 0, null, null, null, null, backgroundStartOptions())
                return
            }
        } catch (e: Exception) {
            // 取り消された PendingIntent なら、アプリの起動に切り替える
        }
        app.packageManager.getLaunchIntentForPackage(c.packageName)?.let { app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun backgroundStartOptions(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val mode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
            } else {
                @Suppress("DEPRECATION")
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
        return ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode).toBundle()
    }

    override fun openAccessSettings() {
        // このアプリの切り替えの画面を直接開く。開けなければ一覧を開く
        val detail =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            app.startActivity(detail)
        } catch (e: Exception) {
            try {
                app.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                // 設定画面を開けない端末では何もしない
            }
        }
    }

    override fun openAppInfo() {
        app.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private companion object {
        const val ART_SIDE = 512

        fun statusOf(state: Int?): PlaybackStatus = when (state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> PlaybackStatus.PLAYING

            PlaybackState.STATE_PAUSED -> PlaybackStatus.PAUSED

            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
            -> PlaybackStatus.BUFFERING

            else -> PlaybackStatus.STOPPED
        }
    }
}
