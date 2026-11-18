package com.droiddeck.launcher.session

import android.content.Context
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.droiddeck.launcher.core.privileged.TvDiagnosticsLog
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receiver-side Wi-Fi Direct: discovers a controller's _droiddeck-tv service,
 * connects to the group owner, and reports the owner's address plus the video
 * size from the TXT record. No Play Services dependency.
 */
class P2pReceiver(private val context: Context) {
    fun begin(): String? {
        synchronized(beginLock) {
            if (begun) return startReason
            begun = true
        }
        val mgr = runCatching { context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager }.getOrNull()
        if (mgr == null) {
            startReason = "p2p-unavailable"
            return startReason
        }
        manager = mgr
        val t = HandlerThread("p2p-receiver").apply { start() }
        thread = t
        val ch = runCatching { mgr.initialize(context, t.looper, null) }.getOrNull()
        if (ch == null) {
            startReason = "p2p-init-failed"
            t.quitSafely()
            return startReason
        }
        channel = ch
        val found = CountDownLatch(1)
        val txtReady = CountDownLatch(1)
        mgr.setDnsSdResponseListeners(
            ch,
            { _, registrationType, device ->
                if (registrationType == SERVICE_TYPE) {
                    controllerDevice = device
                    controllerName = device.deviceName
                    txtByDevice[device.deviceAddress]?.let {
                        videoSize = parseVideoSize(it)
                        padPort = parsePadPort(it)
                        videoPort = parseVideoPort(it)
                    }
                    if (videoSize != null) txtReady.countDown()
                    found.countDown()
                }
            },
            { _, record, device ->
                txtByDevice[device.deviceAddress] = record
                if (device.deviceAddress == controllerDevice?.deviceAddress) {
                    videoSize = parseVideoSize(record)
                    padPort = parsePadPort(record)
                    videoPort = parseVideoPort(record)
                    txtReady.countDown()
                }
            },
        )
        val request = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE)
        serviceRequest = request
        val reqAdded = CountDownLatch(1)
        var reqError: String? = null
        mgr.addServiceRequest(ch, request, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = reqAdded.countDown()
            override fun onFailure(reason: Int) {
                reqError = "service-request-failed"
                reqAdded.countDown()
            }
        })
        reqAdded.await(ADD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (reqError != null) {
            startReason = reqError
            t.quitSafely()
            return startReason
        }
        val discStarted = CountDownLatch(1)
        var discError: String? = null
        mgr.discoverServices(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = discStarted.countDown()
            override fun onFailure(reason: Int) {
                discError = "discover-failed"
                discStarted.countDown()
            }
        })
        discStarted.await(ADD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (discError != null) {
            startReason = discError
            t.quitSafely()
            return startReason
        }
        if (!found.await(DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            startReason = "controller-not-found"
            t.quitSafely()
            return startReason
        }
        // The TXT record may arrive before or after the service callback; wait
        // briefly for it, then fall back to the default size at the use site.
        txtReady.await(TXT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val device = controllerDevice ?: run {
            startReason = "controller-not-found"
            t.quitSafely()
            return startReason
        }
        val connected = CountDownLatch(1)
        var connectError: String? = null
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps = WpsInfo().apply { setup = WpsInfo.PBC }
        }
        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = connected.countDown()
            override fun onFailure(reason: Int) {
                connectError = "connect-failed"
                connected.countDown()
            }
        })
        connected.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (connectError != null) {
            startReason = connectError
            t.quitSafely()
            return startReason
        }
        val formed = CountDownLatch(1)
        var infoError: String? = null
        val deadline = SystemClock.elapsedRealtime() + GROUP_TIMEOUT_MS
        fun pollInfo() {
            mgr.requestConnectionInfo(ch) { info ->
                if (info != null && info.groupFormed && info.groupOwnerAddress != null) {
                    controllerAddress = info.groupOwnerAddress
                    formed.countDown()
                    return@requestConnectionInfo
                }
                if (SystemClock.elapsedRealtime() < deadline) {
                    Handler(t.looper).postDelayed({ pollInfo() }, POLL_MS)
                } else {
                    infoError = "group-info-timeout"
                    formed.countDown()
                }
            }
        }
        pollInfo()
        formed.await(GROUP_TIMEOUT_MS + AWAIT_SLACK_MS, TimeUnit.MILLISECONDS)
        if (infoError != null) {
            startReason = infoError
            t.quitSafely()
            return startReason
        }
        startReason = null
        TvDiagnosticsLog.log(
            context, "tv", "p2p-receiver",
            fields = mapOf("step" to "begin", "result" to "up", "controller" to (controllerName ?: "-")),
        )
        return startReason
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { serviceRequest?.let { manager?.removeServiceRequest(channel, it, null) } }
        runCatching { manager?.removeGroup(channel, null) }
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
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null
    private val txtByDevice = mutableMapOf<String, Map<String, String>>()

    @Volatile
    private var controllerDevice: WifiP2pDevice? = null

    @Volatile
    var controllerAddress: InetAddress? = null
        private set

    @Volatile
    var videoSize: Pair<Int, Int>? = null
        private set

    /** The controller's pad-server port from the TXT record ("pad"); null when the controller has no gamepad input. */
    @Volatile
    var padPort: Int? = null
        private set

    /** The controller's stream-server port from the TXT record ("video"); null when the controller streams nothing. */
    @Volatile
    var videoPort: Int? = null
        private set

    @Volatile
    var controllerName: String? = null
        private set

    private companion object {
        const val SERVICE_TYPE = "_droiddeck-tv._tcp"
        const val ADD_TIMEOUT_MS = 5_000L
        const val DISCOVERY_TIMEOUT_MS = 15_000L
        const val TXT_TIMEOUT_MS = 2_000L
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val GROUP_TIMEOUT_MS = 10_000L
        const val AWAIT_SLACK_MS = 2_000L
        const val POLL_MS = 500L
    }
}

/** Video size from the controller's TXT record ("w"/"h"); null when absent or invalid. */
internal fun parseVideoSize(record: Map<String, String>): Pair<Int, Int>? {
    val w = record["w"]?.toIntOrNull() ?: return null
    val h = record["h"]?.toIntOrNull() ?: return null
    if (w <= 0 || h <= 0) return null
    return w to h
}

/** Pad-server port from the controller's TXT record ("pad"); null when absent or invalid. */
internal fun parsePadPort(record: Map<String, String>): Int? {
    val port = record["pad"]?.toIntOrNull() ?: return null
    if (port < 1 || port > 65535) return null
    return port
}

/** Stream-server port from the controller's TXT record ("video"); null when absent or invalid. */
internal fun parseVideoPort(record: Map<String, String>): Int? {
    val port = record["video"]?.toIntOrNull() ?: return null
    if (port < 1 || port > 65535) return null
    return port
}
