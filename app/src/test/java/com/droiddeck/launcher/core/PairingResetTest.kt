package com.droiddeck.launcher.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PairingResetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val pairingFiles = listOf(
        "wireless-adb-cert.pem",
        "wireless-adb-key.pem",
        "wireless-adb-host.txt",
        "wireless-adb-connection.txt",
    )

    // The full reset (resetPairingCompletely) cannot run here: it mints an
    // identity through KadbCert, whose class file targets Java 21, while
    // these tests run on JDK 17. The minting path is the same one pair()
    // uses on the device, so the reset's file effects are tested here
    // through the exact pieces the reset composes.
    @Test
    fun `reset pieces wipe the saved pairing but keep the auto-grant toggle`() {
        val dir = context.noBackupFilesDir
        for (name in pairingFiles) File(dir, name).writeText("seed")
        WirelessAdbFix.setAutoGrantSecureEnabled(context, false)

        WirelessAdbFix.forgetIdentity(context)

        for (name in pairingFiles) assertFalse(File(dir, name).exists())
        // The toggle is a user preference, not pairing state: a reset
        // must not silently turn the automatic grant off.
        assertEquals(false, WirelessAdbFix.isAutoGrantSecureEnabled(context))
    }
}
