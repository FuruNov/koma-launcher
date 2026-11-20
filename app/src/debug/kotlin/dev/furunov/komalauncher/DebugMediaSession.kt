package dev.furunov.komalauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock

/**
 * 音楽アプリにログインできない Waydroid で、再生中のコマを確かめるための仮の再生セッション。
 * 音は鳴らさない。ランチャーからの再生・一時停止・曲送りを受けて、状態とジャケットを切り替える。
 * 例: am broadcast -a dev.furunov.komalauncher.DEBUG_MEDIA --es state play（pause・stop も使える）
 */
object DebugMediaSession {
    private var session: MediaSession? = null
    private var track = 0
    private val colors = listOf(0xFF0D1B3D.toInt() to 0xFFFFE08A.toInt(), 0xFFFF7A59.toInt() to 0xFFFF3D7F.toInt(), 0xFF9FE6D8.toInt() to 0xFF2A8A99.toInt())

    fun handle(
        context: Context,
        state: String?,
    ) {
        val s = session ?: MediaSession(context.applicationContext, "koma-debug").also { created ->
            created.setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() = setState(PlaybackState.STATE_PLAYING)

                    override fun onPause() = setState(PlaybackState.STATE_PAUSED)

                    override fun onSkipToNext() = skip(1)

                    override fun onSkipToPrevious() = skip(-1)
                },
            )
            session = created
            publishTrack()
        }
        when (state) {
            "pause" -> setState(PlaybackState.STATE_PAUSED)

            "stop" -> {
                setState(PlaybackState.STATE_STOPPED)
                s.isActive = false
                s.release()
                session = null
            }

            else -> {
                s.isActive = true
                setState(PlaybackState.STATE_PLAYING)
            }
        }
    }

    private fun skip(delta: Int) {
        track = (track + delta + colors.size) % colors.size
        publishTrack()
        setState(PlaybackState.STATE_PLAYING)
    }

    private fun setState(state: Int) {
        val actions =
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        session?.setPlaybackState(PlaybackState.Builder().setActions(actions).setState(state, 0L, 1f, SystemClock.elapsedRealtime()).build())
    }

    private fun publishTrack() {
        val (a, b) = colors[track]
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 400f, 400f, a, b, Shader.TileMode.CLAMP) })
            drawCircle(280f, 120f, 70f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = b })
        }
        session?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "テストの曲 ${track + 1}")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Koma Launcher")
                .putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap)
                .build(),
        )
    }
}
