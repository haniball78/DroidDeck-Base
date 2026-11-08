package com.droiddeck.launcher.core

import android.content.Context
import com.droiddeck.launcher.core.privileged.DiagnosticsLog
import com.droiddeck.launcher.core.privileged.PrivilegedBridge
import java.io.ByteArrayInputStream
import java.io.File
import java.security.cert.CertificateFactory
import java.util.Base64
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Diagnoses WHY a fresh pair takes minutes to become usable. AOSP's
 * AdbDebuggingManager posts the persist to a Handler on pairing and the
 * persist itself logs nothing on success (DEBUG is false on production),
 * so the app observes it two ways: logcat's "Pairing succeeded" line
 * (INFO, always on) pins the delay on the system's own clock, and
 * `dumpsys adb` prints /data/misc/adb/adb_keys itself — read by the
 * system service, so no root is needed. Only booleans, counts and
 * timestamps are recorded, never key material. The capture runs once,
 * on a background thread, after the first daemon start following a
 * parked slow pair (the daemon is up then, so the shell commands work),
 * and reads only: it changes no pairing behaviour.
 */
object AdbSlowPairingDiagnostics {

    private val pendingSlowPairMs = AtomicLong(0L)
    private val diagnosisAttempts = AtomicInteger(0)
    private const val MAX_DIAGNOSIS_ATTEMPTS = 3
    private const val PENDING_WINDOW_MS = 10 * 60 * 1_000L
    private const val COMMAND_TIMEOUT_MS = 10_000

    /** Called when a pair parks on AUTH: a slow pair is now pending. */
    fun noteSlowPairParked() {
        pendingSlowPairMs.set(System.currentTimeMillis())
        diagnosisAttempts.set(0)
    }

    /** After a daemon start succeeds: capture a pending slow-pair diagnosis on a background thread; never blocks and never throws. */
    fun maybeDiagnoseSlowPair(context: Context) {
        val pairMs = pendingSlowPairMs.get()
        if (pairMs == 0L) return
        if (System.currentTimeMillis() - pairMs > PENDING_WINDOW_MS) {
            pendingSlowPairMs.set(0L)
            return
        }
        if (diagnosisAttempts.get() >= MAX_DIAGNOSIS_ATTEMPTS) return
        Thread {
            runCatching { captureSlowPair(context.applicationContext, pairMs) }
        }.start()
    }

    private fun captureSlowPair(context: Context, pairMs: Long) {
        val nowMs = System.currentTimeMillis()
        val logcat = runCatching {
            PrivilegedBridge.executeChecked("logcat -d -s AdbDebuggingManager", COMMAND_TIMEOUT_MS)
        }.getOrNull()
        val dumpsys = runCatching {
            PrivilegedBridge.executeChecked("dumpsys adb", COMMAND_TIMEOUT_MS)
        }.getOrNull()
        val logcatOk = logcat?.exitCode == 0
        val dumpsysOk = dumpsys?.exitCode == 0
        val systemPairMs = if (logcatOk) parsePairingSucceededMs(logcat.output, pairMs, nowMs) else null
        val events = if (logcatOk) parseAdbManagerEvents(logcat.output) else emptyList()
        val ourKeyBase64 = runCatching {
            publicKeyBase64(File(context.noBackupFilesDir, WirelessAdbFix.CERT_FILE).readBytes())
        }.getOrNull()
        val keyLines = if (dumpsysOk) countAdbKeyLines(dumpsys.output) else null
        val keyPresent = if (dumpsysOk && ourKeyBase64 != null) dumpsys.output.contains(ourKeyBase64) else null
        val delayMs = systemPairMs?.let { nowMs - it } ?: (nowMs - pairMs)
        DiagnosticsLog.log(context, "adbpair", "slow-diagnosis", fields = buildMap {
            put("delay_ms", delayMs.toString())
            systemPairMs?.let { put("pair_system_ms", it.toString()) }
            put("logcat_ok", logcatOk.toString())
            put("dumpsys_ok", dumpsysOk.toString())
            keyLines?.let { put("key_lines", it.toString()) }
            keyPresent?.let { put("key_present", it.toString()) }
            if (events.isNotEmpty()) put("events", events.joinToString("; ").take(500))
            WirelessAdbFix.identityFingerprint(context)?.let { put("identity", it) }
        })
        if (logcatOk || dumpsysOk) {
            pendingSlowPairMs.set(0L)
        } else {
            diagnosisAttempts.incrementAndGet()
        }
    }

    /**
     * The system-clock time of the "Pairing succeeded" logcat line for the
     * pair around [pairMs] (the app's clock, same device), or null. Takes
     * the latest matching line inside a one-minute window around the pair;
     * a line more than a minute in the future is a year-boundary artefact
     * and is shifted back a year.
     */
    fun parsePairingSucceededMs(logcat: String, pairMs: Long, nowMs: Long): Long? {
        val times = logcat.lineSequence()
            .filter { it.contains("AdbDebuggingManager") && it.contains("Pairing succeeded") }
            .mapNotNull { parseLogcatTime(it, nowMs) }
            .toList()
        if (times.isEmpty()) return null
        return times.lastOrNull { it in pairMs - 60_000..pairMs + 60_000 }
    }

    /**
     * The AdbDebuggingManager lines of a logcat capture, reduced to
     * "HH:MM:SS event" entries with any key material stripped: the
     * "Pairing succeeded key=..." line becomes just "pairing-succeeded",
     * and any other "key=" value is dropped. At most ten entries.
     */
    fun parseAdbManagerEvents(logcat: String): List<String> {
        return logcat.lineSequence()
            .filter { it.contains("AdbDebuggingManager:") }
            .mapNotNull { line ->
                val time = Regex("""^\d{2}-\d{2} (\d{2}:\d{2}:\d{2})\.\d{3}""").find(line)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                val message = line.substringAfter("AdbDebuggingManager:", "").trim()
                val event = when {
                    message.startsWith("Pairing succeeded") -> "pairing-succeeded"
                    message.startsWith("Pairing failed") -> "pairing-failed"
                    message.contains("Disabling adbwifi") -> "wifi-disabled"
                    message.contains("Error writing keys") -> "error-writing-keys"
                    message.contains("Unable to obtain the key file") -> "error-key-file"
                    else -> message.substringBefore("key=").trim().take(80).ifEmpty { return@mapNotNull null }
                }
                "$time $event"
            }
            .take(10)
            .toList()
    }

    /** Lines of a `dumpsys adb` output that look like an adb key entry (best effort: the dump's exact layout varies). */
    fun countAdbKeyLines(dumpsys: String): Int {
        return dumpsys.lineSequence().count { line ->
            Regex("""[A-Za-z0-9+/=]{200,}\s+\S+""").containsMatchIn(line)
        }
    }

    /**
     * The adb-keys form of a certificate's public key: the standard
     * Base64 of its X.509 SubjectPublicKeyInfo encoding, which is what
     * the pairing handshake puts into /data/misc/adb/adb_keys.
     */
    fun publicKeyBase64(certPem: ByteArray): String? {
        return runCatching {
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certPem)) as java.security.cert.X509Certificate
            Base64.getEncoder().encodeToString(certificate.publicKey.encoded)
        }.getOrNull()
    }

    private fun parseLogcatTime(line: String, nowMs: Long): Long? {
        val match = Regex("""^(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.(\d{3})""").find(line) ?: return null
        val (month, day, hour, minute, second, millis) = match.destructured
        val zone = TimeZone.getDefault()
        val year = Calendar.getInstance(zone, Locale.US).apply { timeInMillis = nowMs }.get(Calendar.YEAR)
        val calendar = Calendar.getInstance(zone, Locale.US).apply {
            set(year, month.toInt() - 1, day.toInt(), hour.toInt(), minute.toInt(), second.toInt())
            set(Calendar.MILLISECOND, millis.toInt())
        }
        var timeMs = calendar.timeInMillis
        if (timeMs > nowMs + 60_000) {
            calendar.add(Calendar.YEAR, -1)
            timeMs = calendar.timeInMillis
        }
        return timeMs
    }
}
