package com.droiddeck.launcher.core

import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbSlowPairingDiagnosticsTest {

    private fun logcatTime(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(java.util.Date(ms))

    private fun logcatLine(ms: Long, message: String): String =
        "${logcatTime(ms)}  1234  1234 I AdbDebuggingManager: $message"

    @Test fun pairingSucceededTimestampIsParsed() {
        val pairMs = System.currentTimeMillis()
        val nowMs = pairMs + 30_000
        val logcat = listOf(
            logcatLine(pairMs - 3_600_000, "Pairing succeeded key=OLDKEY"),
            logcatLine(pairMs - 25_000, "Pairing succeeded key=NEWKEY"),
        ).joinToString("\n")
        assertEquals(pairMs - 25_000, AdbSlowPairingDiagnostics.parsePairingSucceededMs(logcat, pairMs, nowMs))
    }

    @Test fun pairingSucceededMissingReturnsNull() {
        val pairMs = System.currentTimeMillis()
        val logcat = logcatLine(pairMs - 25_000, "Wifi disabled. Disabling adbwifi.")
        assertNull(AdbSlowPairingDiagnostics.parsePairingSucceededMs(logcat, pairMs, pairMs + 30_000))
    }

    @Test fun adbManagerEventsStripKeyMaterial() {
        val base = System.currentTimeMillis()
        val logcat = listOf(
            logcatLine(base, "Pairing succeeded key=AAAASECRETKEY"),
            logcatLine(base + 1_000, "Wifi disabled. Disabling adbwifi."),
            logcatLine(base + 2_000, "Error writing keys: java.io.IOException"),
        ).joinToString("\n")
        val events = AdbSlowPairingDiagnostics.parseAdbManagerEvents(logcat)
        assertEquals(3, events.size)
        assertTrue(events[0].endsWith("pairing-succeeded"))
        assertTrue(events[1].endsWith("wifi-disabled"))
        assertTrue(events[2].endsWith("error-writing-keys"))
        assertTrue(events.none { it.contains("AAAASECRETKEY") })
    }

    @Test fun dumpsysKeyPresence() {
        val (certPem, _) = AdbIdentityGenerator.generateFreshIdentity(
            System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3650),
        )
        val keyBase64 = AdbSlowPairingDiagnostics.publicKeyBase64(certPem)
        assertTrue(keyBase64!!.length > 200)
        val dumpsys = "ADB MANAGER STATE (dumpsys adb):\n" +
            "  connected_to_adb: true\n" +
            "  user_keys: $keyBase64 user@host\n"
        assertEquals(1, AdbSlowPairingDiagnostics.countAdbKeyLines(dumpsys))
        assertTrue(dumpsys.contains(keyBase64))
        val empty = "ADB MANAGER STATE (dumpsys adb):\n  connected_to_adb: true\n"
        assertEquals(0, AdbSlowPairingDiagnostics.countAdbKeyLines(empty))
        assertFalse(empty.contains(keyBase64))
    }

    @Test fun publicKeyBase64IsAValidRsaKey() {
        val (certPem, _) = AdbIdentityGenerator.generateFreshIdentity(
            System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3650),
        )
        val keyBase64 = AdbSlowPairingDiagnostics.publicKeyBase64(certPem)!!
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(keyBase64)))
        assertEquals("RSA", publicKey.algorithm)
    }
}
