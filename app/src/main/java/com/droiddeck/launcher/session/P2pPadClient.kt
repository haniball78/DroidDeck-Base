package com.droiddeck.launcher.session

import com.droiddeck.launcher.input.PadState
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gamepad-phone side of the second-controller link: connects to the
 * controller phone's pad server (group-owner address + the "pad" port from
 * the P2P TXT record) and streams the local touch-pad state. send() only
 * stores the latest state — a sender thread writes it, and only when it
 * changed, so touch events on the UI thread never block on the network.
 */
class P2pPadClient {
    /** Opens the connection; false when the controller cannot be reached. */
    fun connect(host: String, port: Int): Boolean {
        if (connected.get()) return true
        val socket = Socket()
        runCatching {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        }.getOrNull() ?: run {
            runCatching { socket.close() }
            return false
        }
        this.socket = socket
        connected.set(true)
        senderThread = Thread({ sendLoop() }, "pad-client-send").apply { start() }
        return true
    }

    fun send(state: PadState) {
        latest = PadWire.encode(state)
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
            val bytes = latest
            if (bytes != null && !bytes.contentEquals(lastSent)) {
                val ok = runCatching {
                    out.writeInt(bytes.size)
                    out.write(bytes)
                    out.flush()
                    true
                }.getOrDefault(false)
                if (!ok) break
                lastSent = bytes
            }
            runCatching { Thread.sleep(SEND_POLL_MS) }
        }
        connected.set(false)
        runCatching { socket?.close() }
    }

    val connected = AtomicBoolean(false)

    @Volatile
    private var latest: ByteArray? = null

    @Volatile
    private var lastSent: ByteArray? = null

    @Volatile
    private var socket: Socket? = null

    private val stopping = AtomicBoolean(false)
    private var senderThread: Thread? = null

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val SEND_POLL_MS = 10L
        const val STOP_JOIN_MS = 2_000L
    }
}
