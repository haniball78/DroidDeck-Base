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
 * owner so a receiver can discover and join it. No Play Services dependency.
 * begin() returns after kicking off group creation; formation and advertising
 * finish asynchronously (the session must not wait on P2P), failures are logged.
 */
class P2pGroup(private val padServer: P2pPadServer? = null) : SessionPart() {
    override fun start() {
        begin()
    }

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { manager?.removeGroup(channel, null) }
        thread?.quitSafely()
    }

    /** Kicks off group creation (5 GHz when available); null when started, else the start-failed reason. */
    fun begin(): String? {
        synchronized(beginLock) {
            if (begun) return startReason
            begun = true
        }
        val context = app() ?: run {
            startReason = "not-attached"
            return startReason
        }
        val mgr = runCatching { context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager }.getOrNull()
        if (mgr == null) {
            startReason = "p2p-unavailable"
            return startReason
        }
        manager = mgr
        val t = HandlerThread("p2p-group").apply { start() }
        thread = t
        val ch = runCatching { mgr.initialize(context, t.looper, null) }.getOrNull()
        if (ch == null) {
            startReason = "p2p-init-failed"
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
                    mgr.createGroup(ch, plainListener())
                } else {
                    fail("create-group-failed")
                }
            }
        }
        if (config != null) mgr.createGroup(ch, config, listener) else mgr.createGroup(ch, listener)
        return null
    }

    /** The group we ask for on API 29+: name + 5 GHz band + persistent. Null below API 29. */
    internal data class GroupRequest(val networkName: String, val band: Int, val persistent: Boolean)

    internal fun groupRequestFor(sdkInt: Int = Build.VERSION.SDK_INT): GroupRequest? =
        if (sdkInt < Build.VERSION_CODES.Q) null
        else GroupRequest(GROUP_NAME, WifiP2pConfig.GROUP_OWNER_BAND_5GHZ, true)

    // Not chained: under Robolectric the Builder setters return null instead of this.
    private fun buildConfig(request: GroupRequest): WifiP2pConfig? = runCatching {
        val builder = WifiP2pConfig.Builder()
        builder.setNetworkName(request.networkName)
        builder.setGroupOperatingBand(request.band)
        if (request.persistent) builder.enablePersistentMode(true)
        builder.build()
    }.getOrNull()

    private fun plainListener() = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = pollGroupInfo()
        override fun onFailure(reason: Int) {
            if (!stopped.get()) fail("create-group-failed")
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

    private fun advertiseService() {
        if (stopped.get()) return
        // The TXT record carries the video size so the receiver can configure
        // its decoder before the first frame arrives, and the pad-server port
        // so a second phone can join as player 2.
        val size = SessionState.outputSize
        val record = mutableMapOf(
            "role" to "controller", "w" to size.first.toString(), "h" to size.second.toString(),
        )
        val padPort = padServer?.port ?: -1
        if (padPort > 0) record["pad"] = padPort.toString()
        val info = WifiP2pDnsSdServiceInfo.newInstance(
            SERVICE_INSTANCE, SERVICE_TYPE, record,
        )
        manager?.addLocalService(channel, info, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (stopped.get()) return
                startReason = null
                TvDiagnosticsLog.log(
                    app(), "tv", "p2p-group",
                    fields = mapOf("step" to "begin", "result" to "up", "group" to (groupName ?: "-")),
                )
            }

            override fun onFailure(reason: Int) {
                if (!stopped.get()) fail("advertise-failed")
            }
        }) ?: fail("advertise-failed")
    }

    private fun fail(reason: String) {
        startReason = reason
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
        const val GROUP_NAME = "DroidDeck-TV"
        const val SERVICE_INSTANCE = "DroidDeck-TV"
        const val SERVICE_TYPE = "_droiddeck-tv._tcp"
        const val GROUP_TIMEOUT_MS = 10_000L
        const val POLL_MS = 500L
    }
}
