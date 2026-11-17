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
class ReceiverInputPrefsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearSettings() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun receiverSendInputIsOffByDefault() {
        assertFalse(SessionPrefs.receiverSendInput(context))
    }

    @Test
    fun receiverSendInputRoundTrips() {
        SessionPrefs.setReceiverSendInput(context, true)
        assertTrue(SessionPrefs.receiverSendInput(context))
        SessionPrefs.setReceiverSendInput(context, false)
        assertFalse(SessionPrefs.receiverSendInput(context))
    }
}
