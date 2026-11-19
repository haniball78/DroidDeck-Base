package com.droiddeck.launcher.session

import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.core.privileged.TvDiagnosticsLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Controller-side Wi-Fi Direct group for Receiver mode: the phone becomes the group
 * owner so a receiver can discover and join it, or uses the group already up
 * (e.g. made from Settings) instead of asking the framework to create one.
 * No Play Services dependency. begin() returns after kicking off group
 * detection/creation; formation and advertising finish asynchronously (the
 * session must not wait on P2P), failures are logged.
 */
class P2pGroup(
    private val padServer: P2pPadServer? = null,
    private val streamPort: Int = -1,
) : SessionPart() {
    enum class Status { IDLE, FORMING, READY, FAILED }

    @Volatile
    var status: Status = Status.IDLE
        private set

    @Volatile
    var failureReason: String? = null
        private set

    /** Called on the P2P thread when the group is up or has failed. */
    var onState: (() -> Unit)? = null
    override fun start() {
        begin()
    }

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        // A group this call did not create is not ours to tear down.
        if (!usedExistingGroup) runCatching { manager?.removeGroup(channel, null) }
        thread?.quitSafely()
    }

    /** True when the group in use is one already up, not one this call created. */
    @Volatile
    var usedExistingGroup: Boolean = false
        private set

    /** Kicks off group detection (an existing group is used as-is) or creation; null when started, else the start-failed reason. */
    fun begin(): String? {
        synchronized(beginLock) {
            if (begun) return startReason
            begun = true
        }
        status = Status.FORMING
        val context = app() ?: run {
            startReason = "not-attached"
            status = Status.FAILED
            failureReason = startReason
            return startReason
        }
        val mgr = runCatching { context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager }.getOrNull()
        if (mgr == null) {
            startReason = "p2p-unavailable"
            status = Status.FAILED
            failureReason = startReason
            return startReason
        }
        manager = mgr
        val t = HandlerThread("p2p-group").apply { start() }
        thread = t
        // HandlerThread.getLooper() blocks on a real device but can return
        // null under Robolectric before the thread prepares its looper; wait
        // briefly so the channel init and the detection timeout below never
        // see a null looper. On a device this exits on the first check.
        val looper = runCatching {
            var ready = t.looper
            val deadline = System.currentTimeMillis() + 1_000
            while (ready == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(1)
                ready = t.looper
            }
            ready
        }.getOrNull()
        if (looper == null) {
            startReason = "p2p-looper-not-ready"
            status = Status.FAILED
            failureReason = startReason
            t.quitSafely()
            return startReason
        }
        val ch = runCatching { mgr.initialize(context, looper, null) }.getOrNull()
        if (ch == null) {
            startReason = "p2p-init-failed"
            status = Status.FAILED
            failureReason = startReason
            t.quitSafely()
            return startReason
        }
        channel = ch
        val request = groupRequestFor()
        val config = request?.let { buildConfig(it) }
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() = pollGroupInfo()
            override fun onFailure(reason: Int) {
                if (stopped.get()) return
                if (config != null) {
                    // 5 GHz config rejected: retry with no band request.
                    runCatching { mgr.createGroup(ch, plainListener()) }
                        .onFailure { if (!stopped.get()) fail("create-group-failed") }
                } else {
                    fail("create-group-failed: ${p2pFailureReason(reason)}")
                }
            }
        }
        fun createGroup() {
            // createGroup throws synchronously when the P2P permission is missing;
            // that must fail the group honestly, never the session.
            runCatching { if (config != null) mgr.createGroup(ch, config, listener) else mgr.createGroup(ch, listener) }
                .onFailure { if (!stopped.get()) fail("create-group-failed") }
        }
        // A group already up (e.g. made from Settings) is used as-is: the
        // framework's createGroup fails on this phone, so never ask for one
        // when a group exists. requestConnectionInfo is the authority here —
        // requestGroupInfo returns null on a live group.
        val detectionDone = AtomicBoolean(false)
        runCatching {
            mgr.requestConnectionInfo(ch) { info ->
                if (stopped.get() || !detectionDone.compareAndSet(false, true)) return@requestConnectionInfo
                if (info?.groupFormed == true && info.isGroupOwner) useExistingGroup() else createGroup()
            }
        }.onFailure {
            if (!stopped.get() && detectionDone.compareAndSet(false, true)) createGroup()
        }
        Handler(t.looper).postDelayed({
            if (stopped.get() || !detectionDone.compareAndSet(false, true)) return@postDelayed
            createGroup()
        }, DETECT_TIMEOUT_MS)
        return null
    }

    /** The group is already up: skip creation and advertise on it. */
    private fun useExistingGroup() {
        if (stopped.get()) return
        usedExistingGroup = true
        advertiseService(bestEffort = true)
    }

    /** The group we ask for on API 29+: a valid DIRECT- name + passphrase, auto band, never persistent. Null below API 29. */
    internal data class GroupRequest(val networkName: String, val band: Int, val persistent: Boolean, val passphrase: String)

    internal fun groupRequestFor(sdkInt: Int = Build.VERSION.SDK_INT): GroupRequest? =
        if (sdkInt < Build.VERSION_CODES.Q) null
        else GroupRequest(GROUP_NAME, WifiP2pConfig.GROUP_OWNER_BAND_AUTO, false, GROUP_PASSPHRASE)

    // Not chained: under Robolectric the Builder setters return null instead of this.
    private fun buildConfig(request: GroupRequest): WifiP2pConfig? = runCatching {
        val builder = WifiP2pConfig.Builder()
        builder.setNetworkName(request.networkName)
        builder.setPassphrase(request.passphrase)
        builder.setGroupOperatingBand(request.band)
        if (request.persistent) builder.enablePersistentMode(true)
        builder.build()
    }.getOrNull()

    private fun plainListener() = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = pollGroupInfo()
        override fun onFailure(reason: Int) {
            if (!stopped.get()) fail("create-group-failed: ${p2pFailureReason(reason)}")
        }
    }

    private fun pollGroupInfo() {
        if (stopped.get()) return
        val handler = Handler(thread!!.looper)
        val deadline = SystemClock.elapsedRealtime() + GROUP_TIMEOUT_MS
        fun check() {
            if (stopped.get()) return
            manager?.requestGroupInfo(channel) { group ->
                if (stopped.get()) return@requestGroupInfo
                if (group != null && group.isGroupOwner) {
                    groupName = group.networkName
                    advertiseService()
                    return@requestGroupInfo
                }
                if (SystemClock.elapsedRealtime() < deadline) {
                    handler.postDelayed({ check() }, POLL_MS)
                } else {
                    fail("group-timeout")
                }
            } ?: fail("group-info-failed")
        }
        check()
    }

    private fun advertiseService(bestEffort: Boolean = false) {
        if (stopped.get()) return
        val info = WifiP2pDnsSdServiceInfo.newInstance(
            SERVICE_INSTANCE, SERVICE_TYPE,
            txtRecord(padServer?.port ?: -1, streamPort, SessionState.outputSize),
        )
        manager?.addLocalService(channel, info, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = markReady()

            override fun onFailure(reason: Int) {
                if (stopped.get()) return
                if (bestEffort) {
                    // The group is up and NSD carries discovery, so a dead P2P
                    // advertisement must not fail the preparation.
                    TvDiagnosticsLog.log(
                        app(), "tv", "p2p-group",
                        fields = mapOf("step" to "advertise-failed", "result" to p2pFailureReason(reason), "group" to (groupName ?: "-")),
                    )
                    markReady()
                } else {
                    fail("advertise-failed: ${p2pFailureReason(reason)}")
                }
            }
        }) ?: fail("advertise-failed")
    }

    private fun markReady() {
        if (stopped.get()) return
        startReason = null
        status = Status.READY
        onState?.invoke()
        TvDiagnosticsLog.log(
            app(), "tv", "p2p-group",
            fields = mapOf("step" to "begin", "result" to "up", "group" to (groupName ?: "-")),
        )
    }

    private fun fail(reason: String) {
        startReason = reason
        status = Status.FAILED
        failureReason = reason
        onState?.invoke()
        TvDiagnosticsLog.log(
            app(), "tv", "p2p-group",
            fields = mapOf("step" to "begin", "result" to reason, "group" to (groupName ?: "-")),
        )
        thread?.quitSafely()
    }

    private val beginLock = Any()

    @Volatile
    private var begun = false

    @Volatile
    private var startReason: String? = null

    private val stopped = AtomicBoolean(false)
    private var thread: HandlerThread? = null
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    @Volatile
    var groupName: String? = null
        private set

    private companion object {
        const val GROUP_NAME = "DIRECT-TV"
        const val GROUP_PASSPHRASE = "DroidDeck1"
        const val SERVICE_INSTANCE = "DroidDeck-TV"
        const val SERVICE_TYPE = "_droiddeck-tv._tcp"
        const val GROUP_TIMEOUT_MS = 10_000L
        const val DETECT_TIMEOUT_MS = 3_000L
        const val POLL_MS = 500L
    }
}

/** A P2P group name must start with DIRECT- plus two characters and fit in 9-32 bytes. */
internal fun isValidGroupName(name: String): Boolean =
    name.length in 9..32 && name.matches(Regex("^DIRECT-[a-zA-Z0-9]{2}.*"))

/** A P2P passphrase is 8-63 ASCII characters. */
internal fun isValidPassphrase(passphrase: String): Boolean =
    passphrase.length in 8..63 && passphrase.all { it.code in 0x20..0x7e }

/**
 * TXT record for the controller's DNS-SD service: the video size so the receiver can configure
 * its decoder before the first frame arrives, the pad-server port so a second phone can join
 * as player 2, and the stream-server port so the receiver connects to the bound port.
 */
internal fun txtRecord(padPort: Int, streamPort: Int, size: Pair<Int, Int>): Map<String, String> {
    val record = mutableMapOf(
        "role" to "controller", "w" to size.first.toString(), "h" to size.second.toString(),
    )
    if (padPort > 0) record["pad"] = padPort.toString()
    if (streamPort > 0) record["video"] = streamPort.toString()
    return record
}

/** Short name for a WifiP2pManager failure code, for diagnostics and status text. */
internal fun p2pFailureReason(code: Int): String = when (code) {
    WifiP2pManager.BUSY -> "busy"
    WifiP2pManager.P2P_UNSUPPORTED -> "p2p-unsupported"
    WifiP2pManager.ERROR -> "error"
    WifiP2pManager.NO_SERVICE_REQUESTS -> "no-service-requests"
    else -> "reason-$code"
}
