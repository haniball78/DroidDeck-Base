package com.droiddeck.launcher.session

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.droiddeck.launcher.input.PadState
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gamepad-phone side of the second-controller link: connects to the
 * controller phone's pad server (group-owner address + the "pad" port from
 * the P2P TXT record) and streams the local touch-pad state. send() only
 * stores the latest state — a sender thread writes it, and only when it
 * changed, so touch events on the UI thread never block on the network.
 */
class P2pPadClient(private val slot: Int = 1) {
    init {
        require(slot in 0..3) { "slot must be 0..3" }
    }

    /** Opens the connection; false when the controller cannot be reached. */
    fun connect(host: String, port: Int): Boolean {
        if (connected.get()) return true
        repeat(CONNECT_ATTEMPTS) { attempt ->
            val socket = Socket()
            val ok = runCatching {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }.isSuccess
            if (ok) {
                this.socket = socket
                connected.set(true)
                senderThread = Thread({ sendLoop() }, "pad-client-send").apply { start() }
                return true
            }
            runCatching { socket.close() }
            if (attempt < CONNECT_ATTEMPTS - 1) runCatching { Thread.sleep(RETRY_DELAY_MS) }
        }
        return false
    }

    fun send(state: PadState) {
        latest = InputWire.wrap(InputWire.KIND_PAD, PadWire.encode(state, slot))
    }

    /** Keyboard/mouse input is queued in order and never coalesced. */
    fun sendKey(keyCode: Int, unicodeChar: Int, scanCode: Int, down: Boolean) {
        enqueue(InputWire.encodeKey(keyCode, unicodeChar, scanCode, down))
    }

    fun sendMouse(dx: Float, dy: Float, buttons: Int, wheel: Float) {
        enqueue(InputWire.encodeMouse(dx, dy, buttons, wheel))
    }

    private fun enqueue(bytes: ByteArray) {
        synchronized(queueLock) { messageQueue.addLast(bytes) }
    }

    fun close() {
        if (!stopping.compareAndSet(false, true)) return
        connected.set(false)
        runCatching { socket?.close() }
        senderThread?.join(STOP_JOIN_MS)
    }

    private fun sendLoop() {
        val out = runCatching { DataOutputStream(socket?.getOutputStream()) }.getOrNull() ?: run {
            connected.set(false)
            return
        }
        while (!stopping.get() && connected.get()) {
            // Queued key/mouse messages first, in order — a quick down+up tap
            // must never be lost to the pad-state dedupe below.
            var failed = false
            while (true) {
                val queued = synchronized(queueLock) { messageQueue.removeFirstOrNull() } ?: break
                if (!write(out, queued)) {
                    failed = true
                    break
                }
            }
            if (failed) break
            val bytes = latest
            if (bytes != null && !bytes.contentEquals(lastSent)) {
                if (!write(out, bytes)) break
                lastSent = bytes
            }
            runCatching { Thread.sleep(SEND_POLL_MS) }
        }
        connected.set(false)
        runCatching { socket?.close() }
    }

    private fun write(out: DataOutputStream, bytes: ByteArray): Boolean = runCatching {
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
        true
    }.getOrDefault(false)

    val connected = AtomicBoolean(false)

    @Volatile
    private var latest: ByteArray? = null

    @Volatile
    private var lastSent: ByteArray? = null

    private val queueLock = Any()
    private val messageQueue = ArrayDeque<ByteArray>()

    @Volatile
    private var socket: Socket? = null

    private val stopping = AtomicBoolean(false)
    private var senderThread: Thread? = null

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val CONNECT_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 500L
        const val SEND_POLL_MS = 10L
        const val STOP_JOIN_MS = 2_000L
    }
}

/** The NSD service type the controller advertises; the gamepad finder resolves it to find the pad server. */
internal const val NSD_SERVICE_TYPE = "_droiddeck-tv._tcp"

/** Short NSD discovery window for USB mode: the cable link is either there or it is not. */
internal const val USB_DISCOVERY_TIMEOUT_MS = 3_000L

/**
 * Discovers one controller service over plain NSD and resolves it; the host and
 * port come from resolution, never from the registration callback (which
 * reports port 0 on this device). Null on timeout or failure. Stops discovery
 * before returning on every path.
 */
internal fun discoverNsdController(context: Context, timeoutMs: Long): Pair<String, Int>? {
    val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
    var endpoint: Pair<String, Int>? = null
    val found = CountDownLatch(1)
    val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (serviceInfo.serviceType?.contains(NSD_SERVICE_TYPE) != true) return
            runCatching { manager.resolveService(serviceInfo, nsdResolveListener(found) { endpoint = it }) }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w("P2pPadClient", "discovery failed: $errorCode")
            found.countDown()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }
    runCatching { manager.discoverServices("$NSD_SERVICE_TYPE.", NsdManager.PROTOCOL_DNS_SD, listener) }
        .onFailure {
            Log.w("P2pPadClient", "discoverServices failed", it)
            return null
        }
    runCatching { found.await(timeoutMs, TimeUnit.MILLISECONDS) }
    runCatching { manager.stopServiceDiscovery(listener) }
    return endpoint
}

internal fun nsdResolveListener(found: CountDownLatch, onResolved: (Pair<String, Int>) -> Unit) =
    object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            nsdEndpoint(serviceInfo.host?.hostAddress, serviceInfo.port)?.let {
                onResolved(it)
                found.countDown()
            }
        }
    }

/** A resolved NSD endpoint; null when the host is missing or the port is unusable (0 is the registration-callback quirk, never a real port). */
internal fun nsdEndpoint(host: String?, port: Int): Pair<String, Int>? {
    if (host.isNullOrBlank()) return null
    if (port < 1 || port > 65535) return null
    return host to port
}
