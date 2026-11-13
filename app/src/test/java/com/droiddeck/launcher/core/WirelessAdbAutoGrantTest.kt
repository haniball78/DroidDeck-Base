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

    @Test
    fun `grant commands carry no masking`() {
        val commands = WirelessAdbFix.grantCommands("com.example")
        assertEquals(3, commands.size)
        assertTrue(commands.none { it.contains("|| true") })
        assertEquals("pm grant com.example android.permission.WRITE_SECURE_SETTINGS", commands[0])
        assertEquals("pm grant com.example android.permission.DUMP", commands[1])
        assertEquals("pm grant com.example android.permission.PACKAGE_USAGE_STATS", commands[2])
    }
}
