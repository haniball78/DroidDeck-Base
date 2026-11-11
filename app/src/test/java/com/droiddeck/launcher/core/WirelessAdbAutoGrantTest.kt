package com.droiddeck.launcher.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WirelessAdbAutoGrantTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `auto-grant is on by default`() {
        context.getSharedPreferences("wireless-adb-pairing", Context.MODE_PRIVATE)
            .edit().remove("autoGrantSecure").apply()
        assertTrue(WirelessAdbFix.isAutoGrantSecureEnabled(context))
    }

    @Test
    fun `auto-grant toggle round-trips`() {
        WirelessAdbFix.setAutoGrantSecureEnabled(context, false)
        assertEquals(false, WirelessAdbFix.isAutoGrantSecureEnabled(context))
        WirelessAdbFix.setAutoGrantSecureEnabled(context, true)
        assertEquals(true, WirelessAdbFix.isAutoGrantSecureEnabled(context))
    }
}
