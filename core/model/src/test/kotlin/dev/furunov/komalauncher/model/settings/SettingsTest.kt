package dev.furunov.komalauncher.model.settings

import dev.furunov.komalauncher.model.apps.AppKey
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun defaults() {
        val s = Settings()
        assertEquals(LauncherAction.OpenDrawer, s.actionFor(GestureKind.SwipeUp))
        assertEquals(LauncherAction.ExpandNotifications, s.actionFor(GestureKind.SwipeDown))
        assertEquals(LauncherAction.None, s.actionFor(GestureKind.PinchIn))
    }

    @Test
    fun roundTrip() {
        val s =
            Settings(
                gestures =
                mapOf(
                    GestureKind.DoubleTapGutter to LauncherAction.LockScreen,
                    GestureKind.PinchIn to LauncherAction.LaunchApp(AppKey("p", "c")),
                    GestureKind.SwipeUp to LauncherAction.OpenFolder("p1"),
                ),
            )
        assertEquals(s, json.decodeFromString(Settings.serializer(), json.encodeToString(Settings.serializer(), s)))
    }

    @Test
    fun oldFileWithoutNewFieldsUsesDefaults() {
        // 項目を増やした後でも、古い settings.json を読めること
        val s = json.decodeFromString(Settings.serializer(), """{"version":1}""")
        assertEquals(Settings(), s)
    }

    @Test
    fun oldFileWithoutMusicUsesDefaults() {
        val s = json.decodeFromString(Settings.serializer(), """{"version":1,"drawer":{"columns":4}}""")
        assertEquals(MusicSettings(pauseTimeoutMinutes = 30, reactToAudio = false), s.music)
    }

    @Test
    fun oldSettingsWithoutIconFieldsDecode() {
        val s = json.decodeFromString(Settings.serializer(), """{"appearance":{"iconSizeDp":52}}""")
        assertEquals(IconStyle(IconShape.SYSTEM, IconRim.NONE, false), s.appearance.iconStyle)
    }
}
