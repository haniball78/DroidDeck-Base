package com.droiddeck.launcher.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionPrefs
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.ArrayDeque

object WirelessAdbFix {
    internal const val CERT_FILE = "wireless-adb-cert.pem"
    private const val KEY_FILE = "wireless-adb-key.pem"
    private const val HOST_FILE = "wireless-adb-host.txt"
    private const val CONNECTION_FILE = "wireless-adb-connection.txt"
    const val LOOPBACK = "127.0.0.1"

    /**
     * Automatic wireless restarts per pairing episode, before the user
     * must act: one slow toggle only. A second toggle cannot help once
     * the key is on disk — if the fresh listener also rejects it, the
     * cause is not the propagation window — and each extra toggle costs
     * the user another wait for the new listener.
     */
    const val MAX_AUTO_RESTARTS = 1

    /**
     * How long the single automatic wireless restart waits for a new
     * connect port: the mDNS fallback in findConnectPort takes up to
     * 12 s, so this must stay above it — shortening it to 10 s would
     * report "no port change" on a listener the fallback would find.
     */
    const val AUTO_RESTART_PORT_WAIT_MS = 15_000L

    private data class SavedConnection(val host: String, val port: Int?)

    @Synchronized
    private fun loadOrCreateIdentity(context: Context) {
        val directory = context.noBackupFilesDir
        val cert = File(directory, CERT_FILE)
        val key = File(directory, KEY_FILE)
        if (cert.isFile && key.isFile) {
            KadbCert.set(cert.readBytes(), key.readBytes())
            return
        }

        // Mint fresh here, never KadbCert.get(): it only generates when its
        // in-memory key is empty, so after a reset it rewrites the SAME key
        // and the reset never changes the fingerprint (the reset bug).
        val identity = AdbIdentityGenerator.generateFreshIdentity(
            notAfterMs = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3650),
        )
        KadbCert.set(identity.first, identity.second)
        writeAtomically(context, cert, identity.first)
        writeAtomically(context, key, identity.second)
    }

    private fun writeAtomically(context: Context, destination: File, contents: ByteArray) {
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.writeBytes(contents)
        check(temporary.renameTo(destination)) { context.getString(R.string.adbfix_save_failed) }
    }

    suspend fun pair(context: Context, host: String, port: Int, pairingCode: String) {
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.attach(context)
        loadOrCreateIdentity(context.applicationContext)
        val fingerprint = identityFingerprint(context)
        val identityFields = fingerprint?.let { mapOf("identity" to it) } ?: emptyMap()
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(context, "adb.pair", "started", fields = mapOf("port" to port.toString()) + identityFields)
        Kadb.pair(host, port, pairingCode, "DroidDeck")
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(context, "adb.pair", "succeeded", fields = mapOf("port" to port.toString()) + identityFields)
        writeAtomically(context, File(context.noBackupFilesDir, HOST_FILE), host.toByteArray())
        writeAtomically(context, File(context.noBackupFilesDir, CONNECTION_FILE), ByteArray(0))
    }

    private fun savedConnection(context: Context): SavedConnection? {
        val directory = context.applicationContext.noBackupFilesDir
        if (!File(directory, CERT_FILE).isFile || !File(directory, KEY_FILE).isFile) return null
        val host = File(directory, HOST_FILE).takeIf(File::isFile)?.readText()?.trim()
            ?.takeIf(String::isNotEmpty) ?: return null
        val lines = File(directory, CONNECTION_FILE).takeIf(File::isFile)?.readLines()
        val port = lines?.takeIf { it.size == 2 && it[0] == host }
            ?.get(1)?.toIntOrNull()?.takeIf { it in 1..65535 }
        return SavedConnection(host, port)
    }

    fun savedConnectionAddress(context: Context): String? = savedConnection(context)?.let { saved ->
        val host = if (':' in saved.host) "[${saved.host}]" else saved.host
        "$host:${saved.port ?: ""}"
    }

    private fun saveConnection(context: Context, host: String, port: Int) {
        writeAtomically(
            context,
            File(context.applicationContext.noBackupFilesDir, CONNECTION_FILE),
            "$host\n$port".toByteArray(),
        )
    }

    fun isLocalAddress(address: InetAddress): Boolean = runCatching {
        if (address.isLoopbackAddress) return true
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { network ->
            network.inetAddresses.toList().any { it.address.contentEquals(address.address) }
        }
    }.getOrDefault(address is Inet4Address)

    fun localLanHosts(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && !it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())

    fun localConnectPort(context: Context): Int? =
        tlsPortProperty()
            ?: findConnectPort(context, LOOPBACK)

    private fun pairingPrefs(context: Context) =
        context.getSharedPreferences("wireless-adb-pairing", Context.MODE_PRIVATE)

    /** The user's self-grant decision: true = allow, false = decline, null = not asked yet. */
    fun secureGrantDecision(context: Context): Boolean? {
        val prefs = pairingPrefs(context.applicationContext)
        return if (prefs.contains("secureGrantConsent")) prefs.getBoolean("secureGrantConsent", false) else null
    }

    fun setSecureGrantDecision(context: Context, allowed: Boolean) {
        pairingPrefs(context.applicationContext).edit().putBoolean("secureGrantConsent", allowed).apply()
    }

    fun clearSecureGrantDecision(context: Context) {
        pairingPrefs(context.applicationContext).edit().remove("secureGrantConsent").apply()
    }

    /** True when the app process itself holds WRITE_SECURE_SETTINGS. */
    fun hasSecureSettingsGrant(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * True only when an automatic daemon start may run: paired, with a
     * known current port that is not the one whose start last failed auth.
     */
    fun shouldAutoRetryDaemonStart(paired: Boolean, currentPort: Int?, lastAuthFailedPort: Int?): Boolean =
        paired && currentPort != null && currentPort != lastAuthFailedPort

    /**
     * True only when the card may restart wireless debugging itself:
     * paired, with the current port still the one whose start last
     * failed auth, and that port not already restarted for. The cap
     * bounds it, so a repeated AUTH failure on fresh ports never
     * loops the toggle.
     */
    fun shouldAutoRestartWireless(
        paired: Boolean,
        currentPort: Int?,
        lastAuthFailedPort: Int?,
        restartedPorts: Set<Int>,
        maxRestarts: Int = MAX_AUTO_RESTARTS,
    ): Boolean =
        paired && currentPort != null && currentPort == lastAuthFailedPort &&
            currentPort !in restartedPorts && restartedPorts.size < maxRestarts

    /** First observed port that differs from [oldPort], or null if none did. */
    fun firstChangedPort(oldPort: Int?, observed: List<Int?>): Int? =
        observed.firstOrNull { it != null && it != oldPort }

    /**
     * Waits up to [timeoutMs] for the connect port to change from
     * [oldPort]: a fresh pair publishes a new listener, and the pre-pair
     * listener keeps rejecting the key. Returns the new port, or null
     * when the port never changed.
     */
    suspend fun waitForConnectPortChange(
        context: Context,
        oldPort: Int?,
        timeoutMs: Long = 30_000,
        pollIntervalMs: Long = 1_000,
    ): Int? {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val port = runCatching { localConnectPort(context) }.getOrNull()
            if (port != null && port != oldPort) return port
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            if (remaining <= 0) return null
            kotlinx.coroutines.delay(minOf(pollIntervalMs, remaining))
        }
    }

    /**
     * Delays after a fresh pair at which the pre-pair port is probed again:
     * the key may only become visible to the listener after a short
     * propagation delay, in which case no restart is needed at all. The
     * last probe fires at the 30 s timeout, so the whole window is
     * covered without waiting longer than a fresh pair already did.
     */
    val SAME_PORT_PROBE_DELAYS_MS = listOf(2_000L, 5_000L, 10_000L, 20_000L, 30_000L)

    /** Probe delay for [attemptIndex], or null when the schedule is exhausted. */
    fun samePortProbeDelay(attemptIndex: Int): Long? = SAME_PORT_PROBE_DELAYS_MS.getOrNull(attemptIndex)

    /**
     * The manual port field stays hidden until pairing has actually
     * failed — a rejected code or a failed stage — so the normal flow
     * never shows it.
     */
    fun shouldShowManualPort(
        stage: WirelessAdbPairingService.Stage,
        alreadyShown: Boolean,
    ): Boolean =
        alreadyShown || stage is WirelessAdbPairingService.Stage.Failed ||
            (stage is WirelessAdbPairingService.Stage.CodeNeeded && stage.error != null)

    /** Which half of a split screen the app occupies, from window bounds. */
    fun splitHalfFromBounds(windowTop: Int, windowHeight: Int, displayHeight: Int): String {
        if (windowHeight <= 0 || displayHeight <= 0) return "unknown"
        if (windowHeight >= displayHeight * 3 / 4) return "fullscreen"
        return if (windowTop + windowHeight / 2 < displayHeight / 2) "top" else "bottom"
    }

    /** Which half of a split screen the app occupies, or "unknown". */
    fun splitHalf(activity: Activity): String = runCatching {
        val decor = activity.window.decorView
        val location = IntArray(2)
        decor.getLocationOnScreen(location)
        splitHalfFromBounds(location[1], decor.height, activity.resources.displayMetrics.heightPixels)
    }.getOrDefault("unknown")

    /**
     * Waits up to [timeoutMs] for a usable connect port after a fresh pair:
     * either the port changes (fresh listener) or the pre-pair port starts
     * accepting the key (propagation delay). Probes the old port at the
     * scheduled delays and logs every outcome, so real devices answer
     * whether a restart is genuinely required or a timed retry suffices.
     * Returns the usable port, or null when neither happened in time.
     */
    suspend fun waitForUsableConnectPort(
        context: Context,
        oldPort: Int?,
        timeoutMs: Long = 30_000,
        pollIntervalMs: Long = 1_000,
    ): Int? {
        val start = android.os.SystemClock.elapsedRealtime()
        var probeIndex = 0
        while (true) {
            val port = runCatching { localConnectPort(context) }.getOrNull()
            if (port != null && port != oldPort) return port
            val elapsed = android.os.SystemClock.elapsedRealtime() - start
            val nextDelay = samePortProbeDelay(probeIndex)
            if (oldPort != null && nextDelay != null && elapsed >= nextDelay) {
                probeIndex++
                val probe = runCatching { verifyConnection(context, LOOPBACK, oldPort) }
                if (probe.isSuccess) {
                    com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
                        context, "adb.daemon", "same-port-retry-succeeded",
                        fields = mapOf("port" to oldPort.toString(), "delayMs" to nextDelay.toString()),
                    )
                    return oldPort
                }
                com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
                    context, "adb.daemon", "same-port-retry-failed",
                    fields = mapOf(
                        "port" to oldPort.toString(),
                        "delayMs" to nextDelay.toString(),
                        "reason" to (probe.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"),
                    ),
                )
            }
            val remaining = start + timeoutMs - android.os.SystemClock.elapsedRealtime()
            if (remaining <= 0) return null
            kotlinx.coroutines.delay(minOf(pollIntervalMs, remaining))
        }
    }

    /**
     * Restarts wireless debugging from the app process by writing the
     * adb_wifi_enabled setting off and on: the same toggle the user
     * performs in Developer options, which restarts the listener on a
     * new port. The app holds WRITE_SECURE_SETTINGS from the daemon
     * start grant; without it the write fails and the caller falls
     * back to asking the user. Refuses when wireless is already off,
     * so it never turns on a toggle the user switched off. The
     * setting ends on, as it started.
     */
    suspend fun restartWirelessDebugging(
        context: Context,
        offMs: Long = 1_500,
    ): Boolean {
        if (!hasSecureSettingsGrant(context)) return false
        return runCatching {
            val resolver = context.applicationContext.contentResolver
            if (android.provider.Settings.Global.getInt(resolver, "adb_wifi_enabled", 0) == 0) return@runCatching false
            if (!android.provider.Settings.Global.putString(resolver, "adb_wifi_enabled", "0")) return@runCatching false
            kotlinx.coroutines.delay(offMs)
            android.provider.Settings.Global.putString(resolver, "adb_wifi_enabled", "1")
        }.getOrDefault(false)
    }

    /**
     * First 16 hex chars of the SHA-256 of the stored identity certificate:
     * enough to prove whether pair and connect used the same key, and never
     * key material. Null when no identity is stored.
     */
    fun identityFingerprint(context: Context): String? {
        val cert = File(context.applicationContext.noBackupFilesDir, CERT_FILE)
        if (!cert.isFile) return null
        return runCatching { Hashes.sha256(cert).take(16) }.getOrNull()?.takeIf { it.length == 16 }
    }

    /**
     * Records a daemon start parked on [port] under the failure class every
     * observed connect to that port throws after pairing. The card shows
     * the AUTH guidance, and the auto-attempt stays off the port until it
     * changes or a start succeeds.
     */
    fun parkAuthFailure(context: Context, port: Int?) {
        AdbSlowPairingDiagnostics.noteSlowPairParked()
        com.droiddeck.launcher.core.privileged.PrivilegedBridge.noteDaemonFailure(
            "AdbPairAuthException", ConnectionFailure.AUTH,
        )
        port?.let { com.droiddeck.launcher.core.privileged.PrivilegedBridge.noteAuthFailedPort(it) }
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
            context, "adb.daemon", "parked-auth",
            fields = buildMap {
                port?.let { put("port", it.toString()) }
                put("reason", "AdbPairAuthException")
                put("category", "auth")
                identityFingerprint(context)?.let { put("identity", it) }
            },
        )
    }

    // Thrown when a fresh pair publishes no new connect port: the daemon
    // start is parked, not attempted, so the stale port is never retried.
    private class AuthParkedException(message: String) : Exception(message)

    private fun tlsPortProperty(): Int? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "service.adb.tls.port") as? String
    }.getOrNull()?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }

    fun findConnectPort(context: Context, pairedHost: String, timeoutSeconds: Long = 12): Int? {
        val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val paired = runCatching { InetAddress.getByName(pairedHost) }.getOrNull() ?: return null
        val matches: (InetAddress) -> Boolean =
            if (paired.isLoopbackAddress) ::isLocalAddress else { address -> address.address.contentEquals(paired.address) }
        val resolvedPort = AtomicInteger(-1)
        val finished = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val queue = ArrayDeque<NsdServiceInfo>()
        val seen = mutableSetOf<String>()
        var resolving = false
        val lock = Any()

        lateinit var resolveNext: () -> Unit
        resolveNext = {
            val next = synchronized(lock) {
                if (finished.get() || resolving || queue.isEmpty()) null
                else queue.removeFirst().also { resolving = true }
            }
            if (next != null) {
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        synchronized(lock) { resolving = false }
                        resolveNext()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val address = serviceInfo.host
                        if (address != null && matches(address) && finished.compareAndSet(false, true)) {
                            resolvedPort.set(serviceInfo.port)
                            latch.countDown()
                        } else {
                            synchronized(lock) { resolving = false }
                            resolveNext()
                        }
                    }
                }
                runCatching { manager.resolveService(next, listener) }.onFailure {
                    synchronized(lock) { resolving = false }
                    resolveNext()
                }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType?.contains("_adb-tls-connect._tcp") == true) {
                    synchronized(lock) {
                        if (seen.add(serviceInfo.serviceName)) queue.addLast(serviceInfo)
                    }
                    resolveNext()
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) { latch.countDown() }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { latch.countDown() }
        }

        try {
            manager.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            latch.await(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { manager.stopServiceDiscovery(discoveryListener) }
        }
        return resolvedPort.get().takeIf { it in 1..65535 }
    }

    fun setChildProcessLimit(context: Context, host: String, port: Int, enabled: Boolean) {
        loadOrCreateIdentity(context.applicationContext)
        Kadb.create(host, port).use { adb ->
            PhantomProcessLimit.shellCommands(enabled).forEach { command ->
                val change = adb.shell(command)
                check(change.exitCode == 0) { change.allOutput.ifBlank { context.getString(R.string.adbfix_command_failed, change.exitCode) } }
            }
            val result = adb.shell(PhantomProcessLimit.verifyCommand())
            check(result.exitCode == 0 && PhantomProcessLimit.verified(result.output, enabled)) {
                context.getString(R.string.adbfix_not_confirmed, result.allOutput.trim())
            }
        }
        if (PhantomProcessLimit.usesDeviceConfig()) PhantomProcessLimit.rememberAndroid12(context, !enabled)
        saveConnection(context, host, port)
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
            context, "phantom.apply", "succeeded",
            fields = mapOf("action" to if (enabled) "re-enable" else "disable"),
        )
    }

    fun setUsingSavedPairing(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        val saved = savedConnection(appContext)
            ?: error(appContext.getString(R.string.adbfix_no_pairing))
        val cachedPort = saved.port
        if (cachedPort != null) {
            val cachedResult = runCatching {
                setChildProcessLimit(appContext, saved.host, cachedPort, enabled)
            }
            if (cachedResult.isSuccess) return
            val discoveredPort = findConnectPort(appContext, saved.host)
            if (discoveredPort != null && discoveredPort != cachedPort) {
                setChildProcessLimit(appContext, saved.host, discoveredPort, enabled)
            } else {
                cachedResult.getOrThrow()
            }
        } else {
            val discoveredPort = findConnectPort(appContext, saved.host)
                ?: error(appContext.getString(R.string.adbfix_enter_address))
            setChildProcessLimit(appContext, saved.host, discoveredPort, enabled)
        }
    }

    fun startPrivilegedDaemonViaAdb(context: Context, host: String, port: Int) {
        loadOrCreateIdentity(context.applicationContext)
        val fingerprint = identityFingerprint(context)
        val starterCmd = com.droiddeck.launcher.core.privileged.PrivilegedBridge.getStarterAdbCommand(context)
        val steps = StringBuilder()
        Kadb.create(host, port).use { adb ->
            if (SessionPrefs.phantomDisableEnabled(context)) {
                steps.append("phantom:").append(adb.shell("device_config set_sync_disabled_for_tests persistent && device_config put activity_manager max_phantom_processes 2147483647 && settings put global settings_enable_monitor_phantom_procs false").exitCode).append(",")
            } else {
                steps.append("phantom:skipped,")
            }
            // The self-grant runs only with the user's consent: a decline
            // leaves the app without WRITE_SECURE_SETTINGS, so the
            // automatic wireless restart is unavailable and the manual
            // guided reset is the fallback. Undecided keeps the old
            // behaviour — the consent screen gates every new pairing, so
            // undecided here means a pairing from an older build.
            val grantSecure = secureGrantDecision(context) != false
            val grantSecureResult = if (grantSecure) {
                adb.shell("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true").exitCode.toString()
            } else {
                "skipped"
            }
            steps.append("grant-secure:").append(grantSecureResult).append(",")
            steps.append("grant-dump:").append(adb.shell("pm grant ${context.packageName} android.permission.DUMP 2>/dev/null || true").exitCode).append(",")
            steps.append("grant-usage:").append(adb.shell("pm grant ${context.packageName} android.permission.PACKAGE_USAGE_STATS 2>/dev/null || true").exitCode).append(",")
            steps.append("whitelist:").append(adb.shell("dumpsys deviceidle whitelist +${context.packageName} 2>/dev/null || true").exitCode).append(",")
            steps.append("appops:").append(adb.shell("cmd appops set ${context.packageName} RUN_IN_BACKGROUND allow 2>/dev/null || true").exitCode).append(",")
            steps.append("starter:").append(adb.shell(starterCmd).exitCode)
        }
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
            context, "device.change", "daemon-start-steps",
            fields = mapOf("steps" to steps.toString()),
        )
        saveConnection(context, host, port)
        com.droiddeck.launcher.core.privileged.PrivilegedBridge.clearDaemonFailure()
        com.droiddeck.launcher.core.privileged.PrivilegedBridge.clearAuthRejected()
        com.droiddeck.launcher.core.privileged.PrivilegedBridge.clearAuthFailedPort()
        // A verified daemon start ends plain mode: unfreeze status probes
        // so later refresh() calls report the live daemon instead of a
        // stale NONE frozen by the plain-mode latch.
        com.droiddeck.launcher.core.privileged.PrivilegedBridge.resetPlainModeLatch()
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
            context, "adb.daemon", "started", fields = buildMap {
                put("port", port.toString())
                fingerprint?.let { put("identity", it) }
            },
        )
        AdbSlowPairingDiagnostics.maybeDiagnoseSlowPair(context)
    }

    fun verifyConnection(context: Context, host: String, port: Int) {
        loadOrCreateIdentity(context.applicationContext)
        Kadb.create(host, port).use { adb ->
            val result = adb.shell("echo ok")
            check(result.exitCode == 0) { "Verification command failed with exit ${result.exitCode}" }
        }
    }

    fun isPaired(context: Context): Boolean {
        val directory = context.applicationContext.noBackupFilesDir
        val cert = File(directory, CERT_FILE)
        val key = File(directory, KEY_FILE)
        val host = File(directory, HOST_FILE).takeIf(File::isFile)?.readText()?.trim()
        return cert.isFile && key.isFile && !host.isNullOrEmpty()
    }

    suspend fun pairAndStartPrivilegedDaemon(context: Context, port: Int, pairingCode: String): Result<Unit> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val result = runCatching {
                val prePairPort = localConnectPort(context)
                pair(context, LOOPBACK, port, pairingCode)
                val baselinePort = localConnectPort(context)
                if (baselinePort != null) {
                    // Soft gate only: try the baseline capture for at
                    // most a few seconds, then proceed to daemon start
                    // and tweaks regardless. A baseline failure or
                    // timeout must never block or break improvements;
                    // the capture is retried on a later pairing, and
                    // it reads state only, it never restores anything.
                    val captured = kotlinx.coroutines.withTimeoutOrNull(5000) {
                        runCatching { com.droiddeck.launcher.core.privileged.DeviceBaselineCapture.ensureAfterPairing(context, LOOPBACK, baselinePort) }.getOrNull()
                    }
                    if (captured == null) {
                        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
                            context, "baseline.capture", "skipped-soft",
                            fields = mapOf("reason" to "timeout-or-failed"),
                        )
                    }
                }
                // A usable port after pairing is either a changed port
                // (fresh listener) or the pre-pair port once it starts
                // accepting the key (propagation delay): connecting to a
                // stale listener fails auth every time (observed
                // 2026-11-06), so wait for a usable one instead of
                // retrying blindly. No usable port means park, not a
                // start attempt: the stale port is recorded as AUTH and
                // the card retries on its own when the port changes.
                val connectPort = waitForUsableConnectPort(context, prePairPort)
                    ?: run {
                        parkAuthFailure(context, prePairPort ?: localConnectPort(context))
                        throw AuthParkedException(context.getString(R.string.connection_card_toast_pair_failed))
                    }
                startPrivilegedDaemonViaAdb(context, LOOPBACK, connectPort)
                verifyConnection(context, LOOPBACK, connectPort)
                check(com.droiddeck.launcher.core.privileged.PrivilegedBridge.refresh(caller = "start-verify").isActive) { "Daemon did not respond after start" }
            }
            // The park has its own parked-auth event; logging it here as a
            // pair failure would mislabel a parked start as a failed pair.
            if (result.isFailure && result.exceptionOrNull() !is AuthParkedException) {
                val reason = result.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"
                com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
                    context, "adb.pair", "failed",
                    fields = mapOf(
                        "reason" to reason,
                        "category" to classifyConnectionFailure(reason).name.lowercase(),
                    ),
                )
            }
            result
        }

    fun forgetIdentity(context: Context) {
        val directory = context.applicationContext.noBackupFilesDir
        File(directory, CERT_FILE).delete()
        File(directory, KEY_FILE).delete()
        File(directory, HOST_FILE).delete()
        File(directory, CONNECTION_FILE).delete()
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
            context, "adb.pair", "identity-cleared",
        )
    }

    fun clearPairing(context: Context) {
        val directory = context.applicationContext.noBackupFilesDir
        File(directory, HOST_FILE).delete()
        File(directory, CONNECTION_FILE).delete()
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
            context, "adb.pair", "cleared",
        )
    }

    /**
     * Full reset: wipes every saved port and pairing and mints a fresh
     * identity, so the next connection starts from zero. A start-from-zero
     * tool, not an AUTH cure: a fresh key alone never fixed a rejected
     * key — only a fresh listener (port change) does — so the fresh pair
     * still goes through the port-change and auto-restart logic.
     */
    fun resetPairingCompletely(context: Context): String? {
        val appContext = context.applicationContext
        forgetIdentity(appContext)
        clearSecureGrantDecision(appContext)
        com.droiddeck.launcher.core.privileged.PrivilegedBridge.clearAuthFailedPort()
        com.droiddeck.launcher.core.privileged.PrivilegedBridge.clearDaemonFailure()
        com.droiddeck.launcher.core.privileged.PrivilegedBridge.clearAuthRejected()
        loadOrCreateIdentity(appContext)
        val fingerprint = identityFingerprint(appContext)
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
            appContext, "adb.pair", "reset-all",
            fields = fingerprint?.let { mapOf("identity" to it) } ?: emptyMap(),
        )
        return fingerprint
    }

    fun startPrivilegedDaemonWithSavedPairing(context: Context): Boolean {
        val appContext = context.applicationContext
        runCatching { com.droiddeck.launcher.core.privileged.DeviceBaselineCapture.ensureViaSavedPairing(appContext) }
        val saved = savedConnection(appContext)
        val discovered = localConnectPort(appContext)
        val candidates = orderConnectCandidates(saved?.host, saved?.port, discovered, localLanHosts())
        if (candidates.isEmpty()) return false
        var firstError: Throwable? = null
        for (candidate in candidates) {
            val ok = runCatching {
                startPrivilegedDaemonViaAdb(appContext, candidate.host, candidate.port)
                true
            }.onFailure { if (firstError == null) firstError = it }.getOrDefault(false)
            if (ok) return true
        }
        val reason = firstError?.javaClass?.simpleName ?: "unknown"
        val category = classifyConnectionFailure(reason)
        val tried = candidates.joinToString(",") { "${it.host}:${it.port}" }
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
            appContext, "adb.daemon", "failed",
            fields = buildMap {
                put("port", candidates.first().port.toString())
                put("tried", tried)
                put("reason", reason)
                put("category", category.name.lowercase())
                identityFingerprint(appContext)?.let { put("identity", it) }
            },
        )
        com.droiddeck.launcher.core.privileged.PrivilegedBridge.noteDaemonFailure(reason, category, tried)
        if (category == ConnectionFailure.AUTH) {
            com.droiddeck.launcher.core.privileged.PrivilegedBridge.noteAuthFailedPort(
                discovered ?: candidates.first().port,
            )
        }
        return false
    }

    /**
     * The one intent every wireless-debugging entry point uses. CLEAR_TASK
     * forces a fresh Settings task so the highlight key is honored: the key
     * is only applied when the fragment is first created, and a warm
     * Settings task would otherwise reopen on its old page and ignore it —
     * the "some buttons land, some don't" inconsistency.
     */
    fun wirelessDebuggingIntent(): Intent =
        Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        }

    /**
     * Opens Developer options in the adjacent window (split-screen),
     * best-effort: the system may open it fullscreen instead, and the
     * split ratio is the user's to drag — no API sets it on a phone.
     * No CLEAR_TASK: a fresh task would break the adjacency.
     */
    fun wirelessDebuggingAdjacentIntent(): Intent =
        Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        }
}
