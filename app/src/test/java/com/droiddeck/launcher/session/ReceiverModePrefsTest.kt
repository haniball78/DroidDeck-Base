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
class ReceiverModePrefsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearSettings() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun receiverModeIsOffByDefault() {
        assertEquals(SessionPrefs.RECEIVER_MODE_OFF, SessionPrefs.receiverMode(context))
    }

    @Test
    fun receiverModeRoundTrips() {
        for (mode in listOf(SessionPrefs.RECEIVER_MODE_CONTROLLER, SessionPrefs.RECEIVER_MODE_RECEIVER, SessionPrefs.RECEIVER_MODE_GAMEPAD)) {
            SessionPrefs.setReceiverMode(context, mode)
            assertEquals(mode, SessionPrefs.receiverMode(context))
        }
        SessionPrefs.setReceiverMode(context, SessionPrefs.RECEIVER_MODE_OFF)
        assertEquals(SessionPrefs.RECEIVER_MODE_OFF, SessionPrefs.receiverMode(context))
    }

    @Test
    fun receiverModeFallsBackToOffForUnknownValues() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit()
            .putString("receiverMode", "nearby").commit()
        assertEquals(SessionPrefs.RECEIVER_MODE_OFF, SessionPrefs.receiverMode(context))
    }
}
