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

/**
 * セッションごとの、一時停止になった時刻。一時停止の期限に使う。
 * 監視をやめたら [reset] する。離れている間の再生・一時停止は届かないので、戻ったときに数え直すため。
 */
class PauseTracker {
    private val pausedAt = HashMap<String, Long>()

    /** 一時停止中なら、一時停止になった時刻（初めて見たときだけ [since] で決める）。それ以外は null。 */
    fun update(
        key: String,
        status: PlaybackStatus,
        since: () -> Long,
    ): Long? {
        if (status != PlaybackStatus.PAUSED) {
            pausedAt.remove(key)
            return null
        }
        return pausedAt.getOrPut(key, since)
    }

    /** 終わったセッションの記録を消す。 */
    fun retain(keys: Set<String>) {
        pausedAt.keys.retainAll(keys)
    }

    fun reset() = pausedAt.clear()
}

object PlayerRules {
    /**
     * ジャケットを読み直すかを決める合図。曲（[trackKey]）に加えて画像の識別（[art]、無ければ null）も含める。
     * 先に画像なしで、後から同じ題で画像つきのメタデータを出すアプリがあるため。
     */
    fun artKey(
        packageName: String,
        trackKey: String,
        art: String?,
    ): String = listOf(packageName, trackKey, art ?: "none").joinToString("\u0000")

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
