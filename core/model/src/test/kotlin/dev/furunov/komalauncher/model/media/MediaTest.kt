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
}
