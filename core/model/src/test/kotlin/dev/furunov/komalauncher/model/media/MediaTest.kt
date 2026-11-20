package dev.furunov.komalauncher.model.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaTest {
    private fun np(
        s: PlaybackStatus,
        pausedAt: Long? = null,
    ) = NowPlaying("com.google.android.apps.youtube.music", s, "t", pausedAt)

    @Test
    fun playingShows() = assertTrue(PlayerRules.showsPlayer(np(PlaybackStatus.PLAYING), 0, 30))

    @Test
    fun bufferingShows() = assertTrue(PlayerRules.showsPlayer(np(PlaybackStatus.BUFFERING), 0, 30))

    @Test
    fun stoppedHides() = assertFalse(PlayerRules.showsPlayer(np(PlaybackStatus.STOPPED), 0, 30))

    @Test
    fun noneHides() = assertFalse(PlayerRules.showsPlayer(null, 0, 30))

    @Test
    fun pausedShowsUntilTimeout() {
        val p = np(PlaybackStatus.PAUSED, pausedAt = 1_000L)
        assertTrue(PlayerRules.showsPlayer(p, 1_000L + 30 * 60_000L - 1, 30))
        assertFalse(PlayerRules.showsPlayer(p, 1_000L + 30 * 60_000L, 30))
        assertEquals(1_000L + 30 * 60_000L, PlayerRules.hideAtMillis(p, 30))
    }

    @Test
    fun playingHasNoHideTime() = assertNull(PlayerRules.hideAtMillis(np(PlaybackStatus.PLAYING), 30))

    @Test
    fun pauseTimeIsRecordedOnce() {
        val t = PauseTracker()
        assertEquals(100L, t.update("a", PlaybackStatus.PAUSED) { 100L })
        assertEquals(100L, t.update("a", PlaybackStatus.PAUSED) { 200L })
        assertNull(t.update("a", PlaybackStatus.PLAYING) { 300L })
        assertEquals(400L, t.update("a", PlaybackStatus.PAUSED) { 400L })
    }

    @Test
    fun pauseTimeIsForgottenAfterReset() {
        // ホームを離れている間に再生・一時停止しても、戻ったときに数え直す（古い時刻で期限切れにしない）
        val t = PauseTracker()
        t.update("a", PlaybackStatus.PAUSED) { 1_000L }
        t.reset()
        assertEquals(35_000L, t.update("a", PlaybackStatus.PAUSED) { 35_000L })
    }

    @Test
    fun pauseTimeOfEndedSessionIsDropped() {
        val t = PauseTracker()
        t.update("a", PlaybackStatus.PAUSED) { 1L }
        t.retain(emptySet())
        assertEquals(9L, t.update("a", PlaybackStatus.PAUSED) { 9L })
    }

    @Test
    fun artKeyChangesWhenArtArrivesLater() {
        // 先に画像なしのメタデータ、後から同じ題で画像つきのメタデータを出すアプリがある
        val before = PlayerRules.artKey("m", "t", null)
        val after = PlayerRules.artKey("m", "t", "bmp:512x512:7")
        assertTrue(before != after)
        assertEquals(after, PlayerRules.artKey("m", "t", "bmp:512x512:7"))
    }
}
