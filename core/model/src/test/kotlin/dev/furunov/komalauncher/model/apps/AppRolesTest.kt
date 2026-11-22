package dev.furunov.komalauncher.model.apps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppRolesTest {
    private fun of(
        declared: AppCategory?,
        vararg roles: AppRole,
    ) = AppRoles.categoryOf(roles.toSet(), declared)

    @Test
    fun browserBeatsDeclaredSocial() = assertEquals(AppCategory.BROWSER, of(AppCategory.SOCIAL, AppRole.BROWSER))

    @Test
    fun dialerAndMailGoToSocial() {
        assertEquals(AppCategory.SOCIAL, of(AppCategory.PRODUCTIVITY, AppRole.DIAL))
        assertEquals(AppCategory.SOCIAL, of(null, AppRole.MESSAGE))
    }

    @Test
    fun mapsAndCameraBeatDeclared() {
        assertEquals(AppCategory.MAPS, of(null, AppRole.MAPS))
        assertEquals(AppCategory.IMAGE, of(AppCategory.PRODUCTIVITY, AppRole.CAMERA))
    }

    @Test
    fun mediaViewerOnlyFillsMissingCategory() {
        assertEquals(AppCategory.IMAGE, of(null, AppRole.IMAGE, AppRole.VIDEO))
        assertEquals(AppCategory.VIDEO, of(null, AppRole.VIDEO, AppRole.AUDIO))
        assertEquals(AppCategory.AUDIO, of(null, AppRole.AUDIO))
        assertEquals(AppCategory.SOCIAL, of(AppCategory.SOCIAL, AppRole.IMAGE))
    }

    @Test
    fun fileManagerIsNotMediaApp() = assertNull(of(null, AppRole.ANY_FILE, AppRole.IMAGE, AppRole.VIDEO, AppRole.AUDIO))

    @Test
    fun noRoleKeepsDeclared() = assertEquals(AppCategory.GAME, of(AppCategory.GAME))
}
