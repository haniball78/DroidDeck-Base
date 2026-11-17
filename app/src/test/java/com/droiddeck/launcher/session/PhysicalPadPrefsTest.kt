package com.droiddeck.launcher.session

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PhysicalPadPrefsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearSettings() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun physicalPadPlayer2IsOffByDefault() {
        assertFalse(SessionPrefs.physicalPadPlayer2(context))
    }

    @Test
    fun physicalPadPlayer2RoundTrips() {
        SessionPrefs.setPhysicalPadPlayer2(context, true)
        assertTrue(SessionPrefs.physicalPadPlayer2(context))
        SessionPrefs.setPhysicalPadPlayer2(context, false)
        assertFalse(SessionPrefs.physicalPadPlayer2(context))
    }
}
