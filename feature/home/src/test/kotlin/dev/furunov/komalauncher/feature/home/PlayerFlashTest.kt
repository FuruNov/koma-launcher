package dev.furunov.komalauncher.feature.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerFlashTest {
    @Test
    fun tapWhilePlayingShowsPause() = assertEquals(PlayerFlash.PAUSE, PlayerFlash.forTap(playing = true))

    @Test
    fun tapWhilePausedShowsPlay() = assertEquals(PlayerFlash.PLAY, PlayerFlash.forTap(playing = false))

    @Test
    fun flashBeforeLayerAppearedIsNotShown() {
        // 前回の再生のときの操作の記号を、次に再生したときにもう一度表示しない
        assertFalse(PlayerFlashEvent(PlayerFlash.NEXT, at = 100L).isNewerThan(200L))
        assertTrue(PlayerFlashEvent(PlayerFlash.NEXT, at = 300L).isNewerThan(200L))
    }
}
