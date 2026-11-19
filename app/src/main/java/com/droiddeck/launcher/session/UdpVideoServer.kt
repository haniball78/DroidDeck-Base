package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.core.privileged.TvDiagnosticsLog
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Phone-side UDP video sender: binds one DatagramSocket and sends
 * packetized frames to the tablet that subscribed with a subscribe
 * datagram. No client, no traffic. The client re-subscribes every
 * second as a heartbeat; a subscription older than five seconds is
 * treated as gone.
 */
class UdpVideoServer(private val bindPort: Int = 0) : SessionPart() {
    override fun start() {
        begin()
    }

    override fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        runCatching { socket?.close() }
        listenerThread?.join(STOP_JOIN_MS)
    }

    /** Binds the socket and starts the subscribe listener; null when running, else the reason. */
    fun begin(): String? {
        synchronized(beginLock) {
            if (begun) return startReason
            begun = true
        }
        val ctx = app() ?: return "no-context".also { startReason = it }
        val sock = runCatching { DatagramSocket(bindPort) }.getOrNull() ?: run {
            startReason = "bind-failed"
            return startReason
        }
        socket = sock
        port = sock.localPort
        startReason = null
        sender = { bytes, address ->
            runCatching {
                sock.send(DatagramPacket(bytes, bytes.size, address))
                true
            }.getOrDefault(false)
        }
        TvDiagnosticsLog.log(
            ctx, "tv", "udp-video-server",
            fields = mapOf("step" to "server-started", "port" to port.toString()),
        )
        listenerThread = Thread({ listenLoop() }, "udp-video-listen").apply { start() }
        return null
    }

    /** Called when a tablet subscribes (the controller asks the encoder for a keyframe then). */
    var onSubscribe: (() -> Unit)? = null

    /** Sends one datagram; replaced by tests with a recorder (the VM sandbox blocks UDP sends). */
    internal var sender: ((ByteArray, InetSocketAddress) -> Boolean)? = null

    /** Sends one frame to the subscribed client; drops it when nobody is subscribed. */
    fun feed(frame: StreamFrame) {
        val client = clientAddress ?: return
        if (System.currentTimeMillis() - lastSubscribeMs > SUBSCRIBE_FRESH_MS) return
        val send = sender ?: return
        var sent = 0
        for (packet in packetizeFrame(frame)) {
            val bytes = packet.encode()
            if (!send(bytes, client)) break
            sent++
        }
        if (sent > 0) {
            framesSent.incrementAndGet()
            packetsSent.addAndGet(sent.toLong())
        }
    }

    fun hasClient(): Boolean {
        if (clientAddress == null) return false
        return System.currentTimeMillis() - lastSubscribeMs <= SUBSCRIBE_FRESH_MS
    }

    @Volatile
    var clientAddress: InetSocketAddress? = null
        private set

    @Volatile
    var port: Int = 0
        private set

    val framesSent = AtomicLong(0)
    val packetsSent = AtomicLong(0)

    private fun listenLoop() {
        val sock = socket ?: return
        val buf = ByteArray(UdpVideo.HEADER_BYTES + UdpVideo.PAYLOAD_BYTES)
        while (!stopping.get()) {
            val packet = DatagramPacket(buf, buf.size)
            val received = runCatching { sock.receive(packet) }.isSuccess
            if (!received) break
            if (!UdpVideo.isSubscribeDatagram(packet.data, packet.length)) continue
            onSubscribeDatagram(InetSocketAddress(packet.address, packet.port))
        }
    }

    /** Records a subscribe datagram's sender as the client; a stale subscription asks for a fresh keyframe. */
    internal fun onSubscribeDatagram(address: InetSocketAddress) {
        val now = System.currentTimeMillis()
        val wasFresh = clientAddress != null && now - lastSubscribeMs <= SUBSCRIBE_FRESH_MS
        clientAddress = address
        lastSubscribeMs = now
        if (!wasFresh) runCatching { onSubscribe?.invoke() }
    }

    private val beginLock = Any()

    @Volatile
    private var begun = false

    @Volatile
    private var startReason: String? = null

    private val stopping = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var listenerThread: Thread? = null

    @Volatile
    private var lastSubscribeMs: Long = 0

    companion object {
        const val SUBSCRIBE_FRESH_MS = 5_000L
        const val STOP_JOIN_MS = 2_000L
    }
}

/**
 * Phone-side control listener: one TCP client at a time, 8-byte
 * RFI/IDR messages. The RFI's frame range is passed through; the
 * caller answers with a keyframe, since the public MediaCodec API
 * has no reference-invalidation call.
 */
class UdpControlServer(private val bindPort: Int = 0, private val onMessage: (ControlMessage) -> Unit) : SessionPart() {
    override fun start() {
        begin()
    }

    override fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        runCatching { currentClient?.close() }
        runCatching { serverSocket?.close() }
        acceptThread?.join(STOP_JOIN_MS)
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
            ctx, "tv", "udp-control-server",
            fields = mapOf("step" to "server-started", "port" to port.toString()),
        )
        acceptThread = Thread({ acceptLoop() }, "udp-control-accept").apply { start() }
        return null
    }

    @Volatile
    var port: Int = 0
        private set

    private fun acceptLoop() {
        val server = serverSocket ?: return
        while (!stopping.get()) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            runCatching { socket.tcpNoDelay = true }
            currentClient = socket
            readLoop(socket)
            runCatching { socket.close() }
            if (currentClient === socket) currentClient = null
        }
    }

    private fun readLoop(socket: Socket) {
        val input = runCatching { DataInputStream(socket.getInputStream()) }.getOrNull() ?: return
        val buf = ByteArray(ControlMessage.SIZE)
        while (!stopping.get()) {
            try {
                input.readFully(buf)
            } catch (_: EOFException) {
                break
            } catch (_: Exception) {
                break
            }
            val message = ControlMessage.decode(buf, buf.size) ?: continue
            runCatching { onMessage(message) }
        }
    }

    private val beginLock = Any()

    @Volatile
    private var begun = false

    @Volatile
    private var startReason: String? = null

    private val stopping = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var currentClient: Socket? = null

    companion object {
        const val STOP_JOIN_MS = 2_000L
    }
}

/** Tablet-side control client: one TCP connection, synchronous 8-byte RFI/IDR writes. */
class UdpControlClient {
    fun connect(host: String, port: Int): Boolean {
        if (connected.get()) return true
        val socket = Socket()
        val ok = runCatching {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = CONNECT_TIMEOUT_MS
        }.isSuccess
        if (!ok) {
            runCatching { socket.close() }
            return false
        }
        this.socket = socket
        connected.set(true)
        return true
    }

    fun sendRfi(firstFrameIndex: Int, lastFrameIndex: Int): Boolean =
        send(ControlMessage.rfi(firstFrameIndex, lastFrameIndex))

    fun sendIdr(): Boolean = send(ControlMessage.idr())

    fun close() {
        if (!connected.compareAndSet(true, false)) return
        runCatching { socket?.close() }
    }

    private fun send(message: ControlMessage): Boolean {
        val sock = socket ?: return false
        return runCatching {
            val out = DataOutputStream(sock.getOutputStream())
            out.write(message.encode())
            out.flush()
            true
        }.getOrDefault(false)
    }

    private val connected = AtomicBoolean(false)
    private var socket: Socket? = null

    companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
    }
}
