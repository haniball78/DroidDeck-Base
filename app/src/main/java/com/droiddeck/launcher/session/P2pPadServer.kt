package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.core.privileged.TvDiagnosticsLog
import com.droiddeck.launcher.input.EvdevKeys
import com.droiddeck.launcher.input.PadState
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Controller-side input listener for the second phone: accepts one gamepad
 * phone at a time on its own TCP port (advertised in the P2P TXT record as
 * "pad") and injects its pad state into the ring slot the phone chose
 * (0 = player 1, 1 = player 2). The controller phone's own touch pad stays slot 0.
 */
class P2pPadServer(private val bindPort: Int = 0) : SessionPart() {
    override fun start() {
        begin()
    }

    override fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        runCatching { currentClient?.close() }
        runCatching { serverSocket?.close() }
        acceptThread?.join(STOP_JOIN_MS)
        clearSlot()
    }

    /** Binds the listener; null when running, else the start-failed reason. */
    fun begin(): String? {
        synchronized(beginLock) {
            if (begun) return startReason
            begun = true
        }
        val ctx = app() ?: return "no-context".also { startReason = it }
        val server = runCatching { ServerSocket(bindPort, 1, null) }.getOrNull() ?: run {
            startReason = "bind-failed"
            return startReason
        }
        serverSocket = server
        port = server.localPort
        startReason = null
        TvDiagnosticsLog.log(
            ctx, "tv", "pad-server",
            fields = mapOf("step" to "server-started", "port" to port.toString()),
        )
        acceptThread = Thread({ acceptLoop() }, "pad-server-accept").apply { start() }
        return null
    }

    private fun acceptLoop() {
        val server = serverSocket ?: return
        while (!stopping.get()) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            runCatching { socket.tcpNoDelay = true }
            synchronized(clientLock) {
                runCatching { currentClient?.close() }
                currentClient = socket
            }
            clientsConnected.incrementAndGet()
            TvDiagnosticsLog.log(
                app(), "tv", "pad-server",
                fields = mapOf("step" to "client-connected", "ip" to (socket.inetAddress?.hostAddress.orEmpty())),
            )
            readLoop(socket)
            clearSlot()
            TvDiagnosticsLog.log(app(), "tv", "pad-server", fields = mapOf("step" to "client-disconnected"))
        }
    }

    private fun readLoop(socket: Socket) {
        val input = runCatching { DataInputStream(socket.getInputStream()) }.getOrNull() ?: return
        val lengthBuf = ByteArray(4)
        while (!stopping.get() && !socket.isClosed) {
            val length = runCatching {
                input.readFully(lengthBuf)
                ((lengthBuf[0].toInt() and 0xff) shl 24) or
                    ((lengthBuf[1].toInt() and 0xff) shl 16) or
                    ((lengthBuf[2].toInt() and 0xff) shl 8) or
                    (lengthBuf[3].toInt() and 0xff)
            }.getOrNull() ?: break
            if (length <= 0 || length > MAX_MESSAGE_BYTES) break
            val bytes = ByteArray(length)
            runCatching { input.readFully(bytes) }.getOrNull() ?: break
            when (val message = InputWire.parse(bytes)) {
                is InputMessage.Pad -> {
                    val (slot, state) = PadWire.decode(message.payload) ?: continue
                    lastSlot = slot
                    messagesReceived.incrementAndGet()
                    runCatching { onState?.invoke(slot, state) }
                    SessionState.padBridge?.applyExternalForSlot(slot, { it.copyFrom(state) }, true)
                }
                is InputMessage.Key -> {
                    val evdev = EvdevKeys.fromKeyEvent(message.keyCode, message.unicodeChar, message.scanCode)
                    if (evdev > 0) SessionState.guestInput?.sendKey(evdev, message.down)
                }
                is InputMessage.Mouse -> SessionState.guestInput?.sendMouse(
                    message.dx, message.dy, message.buttons, message.wheel,
                )
                null -> continue
            }
        }
        runCatching { socket.close() }
    }

    private fun clearSlot() {
        SessionState.padBridge?.applyExternalForSlot(lastSlot, { it.clear() }, true)
    }

    /** Test/diagnostics hook: called with the slot and every decoded pad state. */
    var onState: ((Int, PadState) -> Unit)? = null

    @Volatile
    var port = -1
        private set

    @Volatile
    private var lastSlot = 1

    private val beginLock = Any()

    @Volatile
    private var begun = false

    @Volatile
    private var startReason: String? = null

    private val stopping = AtomicBoolean(false)
    private val clientLock = Any()
    private val messagesReceived = AtomicLong(0)
    private val clientsConnected = AtomicLong(0)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var currentClient: Socket? = null

    private var acceptThread: Thread? = null

    private companion object {
        const val STOP_JOIN_MS = 2_000L
        // Pad message is 29 bytes (kind + 28); key/mouse are 14. Anything larger is garbage.
        const val MAX_MESSAGE_BYTES = 64
    }
}
