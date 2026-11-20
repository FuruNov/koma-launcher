package dev.furunov.komalauncher.model.media

import kotlinx.coroutines.flow.StateFlow

/** 再生の状態。Android の PlaybackState をこの 4 つにまとめる。 */
enum class PlaybackStatus { PLAYING, PAUSED, BUFFERING, STOPPED }

/**
 * 再生中の曲。画像は持たない（画面の型なので :core:platform が別に渡す）。
 * [trackKey] は曲が変わったことの合図（題とアーティストをつないだもの）。
 * [pausedAtMillis] は一時停止になった時刻（壁時計）。一時停止中だけ値を持つ。
 */
data class NowPlaying(
    val packageName: String,
    val status: PlaybackStatus,
    val trackKey: String,
    val pausedAtMillis: Long?,
)

/**
 * 他のアプリの再生の取得と操作。通知へのアクセスの許可が要る。
 * 監視はホームが前面にある間だけにする（[start] と [stop]、電池のため）。
 */
interface MediaSessions {
    val nowPlaying: StateFlow<NowPlaying?>

    /** 通知へのアクセスが許可されているか。 */
    val hasAccess: StateFlow<Boolean>

    fun start()

    fun stop()

    fun togglePlayPause()

    fun next()

    fun previous()

    /** 再生中のアプリを開く。 */
    fun openPlayer()

    /** Android の「通知へのアクセス」の設定画面を開く。 */
    fun openAccessSettings()

    /** このアプリのアプリ情報を開く（制限付き設定を許可するため）。 */
    fun openAppInfo()
}

object PlayerRules {
    /** コマをプレイヤーの表示にするか。一時停止は [pauseTimeoutMinutes] 分まで表示する。 */
    fun showsPlayer(
        np: NowPlaying?,
        nowMillis: Long,
        pauseTimeoutMinutes: Int,
    ): Boolean = when (np?.status) {
        PlaybackStatus.PLAYING, PlaybackStatus.BUFFERING -> true
        PlaybackStatus.PAUSED -> hideAtMillis(np, pauseTimeoutMinutes)?.let { nowMillis < it } ?: true
        PlaybackStatus.STOPPED, null -> false
    }

    /** 一時停止中なら、表示をやめる時刻。それ以外は null。 */
    fun hideAtMillis(
        np: NowPlaying?,
        pauseTimeoutMinutes: Int,
    ): Long? {
        if (np?.status != PlaybackStatus.PAUSED) return null
        return np.pausedAtMillis?.let { it + pauseTimeoutMinutes * 60_000L }
    }
}
