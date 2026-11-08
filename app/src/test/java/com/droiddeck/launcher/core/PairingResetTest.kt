package com.droiddeck.launcher.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PairingResetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `reset mints a new identity and clears the grant decision`() {
        val first = WirelessAdbFix.resetPairingCompletely(context)
        assertNotNull(first)
        WirelessAdbFix.setSecureGrantDecision(context, true)
        assertEquals(true, WirelessAdbFix.secureGrantDecision(context))
        val second = WirelessAdbFix.resetPairingCompletely(context)
        assertNotNull(second)
        assertNotEquals(first, second)
        assertNull(WirelessAdbFix.secureGrantDecision(context))
        val dir = context.noBackupFilesDir
        assertFalse(File(dir, "wireless-adb-host.txt").exists())
        assertFalse(File(dir, "wireless-adb-connection.txt").exists())
        assertTrue(File(dir, "wireless-adb-cert.pem").exists())
        assertTrue(File(dir, "wireless-adb-key.pem").exists())
    }
}
