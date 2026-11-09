package com.droiddeck.launcher.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // The decision is seeded straight into the pairing prefs: the
    // consent dialog that used to write it is gone, so the test writes
    // the same key the getter reads.
    private fun seedGrantDecision(allowed: Boolean) {
        context.getSharedPreferences("wireless-adb-pairing", Context.MODE_PRIVATE)
            .edit().putBoolean("secureGrantConsent", allowed).apply()
    }

    // The full reset (resetPairingCompletely) cannot run here: it mints an
    // identity through KadbCert, whose class file targets Java 21, while
    // these tests run on JDK 17. The minting path is the same one pair()
    // uses on the device, so the reset's file and consent effects are
    // tested here through the exact pieces the reset composes.
    @Test
    fun `reset pieces wipe the saved pairing and the grant decision`() {
        val dir = context.noBackupFilesDir
        for (name in pairingFiles) File(dir, name).writeText("seed")
        seedGrantDecision(true)
        assertEquals(true, WirelessAdbFix.secureGrantDecision(context))

        WirelessAdbFix.forgetIdentity(context)
        WirelessAdbFix.clearSecureGrantDecision(context)

        for (name in pairingFiles) assertFalse(File(dir, name).exists())
        assertNull(WirelessAdbFix.secureGrantDecision(context))
    }

    @Test
    fun `grant decision reads and clears`() {
        WirelessAdbFix.clearSecureGrantDecision(context)
        assertNull(WirelessAdbFix.secureGrantDecision(context))
        seedGrantDecision(true)
        assertEquals(true, WirelessAdbFix.secureGrantDecision(context))
        seedGrantDecision(false)
        assertEquals(false, WirelessAdbFix.secureGrantDecision(context))
        WirelessAdbFix.clearSecureGrantDecision(context)
        assertNull(WirelessAdbFix.secureGrantDecision(context))
    }
}
