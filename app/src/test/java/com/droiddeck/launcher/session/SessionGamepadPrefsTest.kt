package com.droiddeck.launcher.session

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SessionGamepadPrefsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearSettings() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun perGameSurfaceFallsBackToGlobal() {
        assertEquals(SessionPrefs.SURFACE_STICK, SessionPrefs.gamepadSurfaceForGame(context, "440", false))
        assertEquals(SessionPrefs.SURFACE_TRACKPAD, SessionPrefs.gamepadSurfaceForGame(context, "440", true))
        SessionPrefs.setGamepadSurfaceForGame(context, "440", false, SessionPrefs.SURFACE_TRACKPAD)
        assertEquals(SessionPrefs.SURFACE_TRACKPAD, SessionPrefs.gamepadSurfaceForGame(context, "440", false))
        assertEquals(SessionPrefs.SURFACE_TRACKPAD, SessionPrefs.gamepadSurfaceForGame(context, "440", true))
        // Other games and the global setting are untouched.
        assertEquals(SessionPrefs.SURFACE_STICK, SessionPrefs.gamepadSurfaceForGame(context, "570", false))
        assertEquals(SessionPrefs.SURFACE_STICK, SessionPrefs.gamepadSurface(context, false))
    }

    @Test fun emptyScopeWritesTheGlobalSurface() {
        SessionPrefs.setGamepadSurfaceForGame(context, "", true, SessionPrefs.SURFACE_STICK)
        assertEquals(SessionPrefs.SURFACE_STICK, SessionPrefs.gamepadSurface(context, true))
        assertEquals(SessionPrefs.SURFACE_STICK, SessionPrefs.gamepadSurfaceForGame(context, "440", true))
    }

    @Test fun controllerModeTogglePersists() {
        assertEquals(true, SessionPrefs.tvControllerMode(context))
        SessionPrefs.setTvControllerMode(context, false)
        assertEquals(false, SessionPrefs.tvControllerMode(context))
        SessionPrefs.setTvControllerMode(context, true)
        assertEquals(true, SessionPrefs.tvControllerMode(context))
    }

    @Test fun render4kDefaultsOffAndPersists() {
        assertEquals(false, SessionPrefs.tvRender4k(context))
        SessionPrefs.setTvRender4k(context, true)
        assertEquals(true, SessionPrefs.tvRender4k(context))
    }

    @Test fun parseDropsInvalidScopesAndSurfaces() {
        val parsed = SessionPrefs.parseGamepadSurfaces(
            """{"440":{"left":"stick","right":"junk"},"0":{"left":"stick"},"abc":{"left":"stick"},"570":{"right":"trackpad"}}""",
        )
        assertEquals(
            mapOf(
                "440" to SessionPrefs.GamepadSurfaces("stick", null),
                "570" to SessionPrefs.GamepadSurfaces(null, "trackpad"),
            ),
            parsed,
        )
        assertEquals("{}", SessionPrefs.serializeGamepadSurfaces(emptyMap()))
        assertEquals(parsed, SessionPrefs.parseGamepadSurfaces(SessionPrefs.serializeGamepadSurfaces(parsed)))
    }
}
