package com.droiddeck.launcher.session

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.core.privileged.TvDiagnosticsLog
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * P3 slice A3: serves the encoded game frames over raw TCP on the Wi-Fi network.
 *
 * The surface encoder feeds its chunks straight into a bounded in-memory queue
 * (see [feed]); the sender splits them into NAL units at the Annex B start
 * codes and sends each unit length-prefixed (4-byte big-endian length + NAL
 * bytes, start code included) to one connected TV receiver at a time. The
 * encoder still records to its file for diagnostics, but the server never
 * reads it, so a full recording can no longer starve the stream. It is a
 * proof slice only: no PIN, no input return channel, no audio.
 */
class TvStreamServer(private val bindPort: Int = 0) : SessionPart() {
    override fun start() {
        begin()
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        stopping = true
        runCatching { currentClient?.close() }
        runCatching { serverSocket?.close() }
        runCatching { nsdManager?.unregisterService(registrationListener) }
        runCatching { multicastLock?.release() }
        acceptThread?.join(STOP_JOIN_MS)
        senderThread?.join(STOP_JOIN_MS)
        logStopped()
    }

    /** Starts the server; null when running, else the start-failed reason. */
    fun begin(): String? {
        synchronized(beginLock) {
            if (begun) return startReason
            begun = true
        }
        val ctx = app() ?: return "no-context".also { startReason = it }
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val address = wifi?.let { wifiAddress(it) }
        // Wildcard bind: the receiver reaches this server over the P2P group
        // address (192.168.49.1), not the router Wi-Fi address.
        val server = runCatching { ServerSocket(bindPort, 1, null) }.getOrNull() ?: run {
            startReason = "bind-failed"
            return startReason
        }
        serverSocket = server
        port = server.localPort
        multicastLock = runCatching {
            // MulticastLock has no setReferenceCounted (unlike WifiLock/WakeLock); acquire/release are balanced.
            wifi?.createMulticastLock("droiddeck-tv")?.apply {
                acquire()
            }
        }.getOrNull()
        nsdManager = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        runCatching {
            nsdManager?.registerService(buildServiceInfo(server.localPort), NsdManager.PROTOCOL_DNS_SD, registrationListener)
        }.onFailure {
            Log.w(TAG, "tv stream advertise failed", it)
            TvDiagnosticsLog.log(app(), "tv", "stream", fields = mapOf("step" to "advertise-failed", "error" to "exception"))
        }
        running.set(true)
        startReason = null
        TvDiagnosticsLog.log(
            app(), "tv", "stream",
            fields = mapOf("step" to "server-started", "ip" to (address?.hostAddress ?: "0.0.0.0"), "port" to server.localPort.toString()),
        )
        acceptThread = Thread({ acceptLoop() }, "tv-stream-accept").apply { start() }
        return null
    }

    private fun acceptLoop() {
        val server = serverSocket ?: return
        while (!stopping) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            runCatching { socket.tcpNoDelay = true }
            synchronized(clientLock) {
                runCatching { currentClient?.close() }
                currentClient = socket
                chunkQueue.clear()
            }
            clientsConnected.incrementAndGet()
            TvDiagnosticsLog.log(
                app(), "tv", "stream",
                fields = mapOf("step" to "client-connected", "ip" to (socket.inetAddress?.hostAddress.orEmpty())),
            )
            runCatching { onClientConnected?.invoke() }
            val sender = Thread({ sendLoop(socket) }, "tv-stream-send").apply { start() }
            synchronized(clientLock) { senderThread = sender }
        }
    }

    private fun sendLoop(socket: Socket) {
        val out = runCatching { DataOutputStream(socket.getOutputStream()) }.getOrNull() ?: run {
            closeClient(socket)
            return
        }
        val scanner = AnnexBScanner()
        while (!stopping && !socket.isClosed) {
            val chunk = try {
                chunkQueue.poll(QUEUE_POLL_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                closeClient(socket)
                return
            } ?: continue
            val units = scanner.feed(chunk) + scanner.flush()
            for (unit in units) {
                if (!writeUnit(out, unit)) {
                    closeClient(socket)
                    return
                }
            }
        }
        closeClient(socket)
    }

    /**
     * Offers one encoded chunk to the connected receiver; never blocks the
     * encoder thread. Chunks offered with no client connected are dropped, so
     * a joining client gets fresh frames after the next keyframe.
     */
    fun feed(chunk: ByteArray) {
        if (currentClient == null) return
        offerChunk(chunk)
    }

    /** Offers a chunk, dropping the oldest queued chunk when the queue is full. */
    internal fun offerChunk(chunk: ByteArray) {
        if (!chunkQueue.offer(chunk)) {
            chunkQueue.poll()
            chunkQueue.offer(chunk)
        }
    }

    /** Removes and returns the oldest queued chunk, or null when the queue is empty. */
    internal fun pollChunk(): ByteArray? = chunkQueue.poll()

    internal val queueSize: Int get() = chunkQueue.size

    internal fun hasClient(): Boolean = currentClient != null

    /** Called when a receiver connects (the controller asks the encoder for a keyframe then). */
    var onClientConnected: (() -> Unit)? = null

    /** Sends one length-prefixed NAL; false when the client is gone. */
    private fun writeUnit(out: DataOutputStream, unit: ByteArray): Boolean {
        return runCatching {
            out.writeInt(unit.size)
            out.write(unit)
            out.flush()
            framesSent.incrementAndGet()
            bytesSent.addAndGet(unit.size.toLong())
            if (firstFrameMs.get() == -1L) {
                firstFrameMs.set(SystemClock.elapsedRealtime() - startNanos)
                TvDiagnosticsLog.log(app(), "tv", "stream", fields = mapOf("step" to "first-frame"))
            }
            true
        }.getOrDefault(false)
    }

    private fun closeClient(socket: Socket) {
        synchronized(clientLock) {
            if (currentClient === socket) {
                currentClient = null
                chunkQueue.clear()
            }
        }
        runCatching { socket.close() }
        TvDiagnosticsLog.log(app(), "tv", "stream", fields = mapOf("step" to "client-disconnected"))
    }

    private fun logStopped() {
        if (!stoppedLogged.compareAndSet(false, true)) return
        val stats = longArrayOf(
            framesSent.get(), bytesSent.get(), clientsConnected.get(), 0L,
            firstFrameMs.get(), SystemClock.elapsedRealtime() - startNanos,
        )
        TvDiagnosticsLog.log(app(), "tv", "stream", fields = mapOf("step" to "stopped", "summary" to streamSummary(stats)))
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            // The callback's port is not trustworthy on this device (it reports 0
            // while the socket is bound); log the bound port instead.
            TvDiagnosticsLog.log(
                app(), "tv", "stream",
                fields = mapOf(
                    "step" to "advertise-ok",
                    "name" to (serviceInfo.serviceName ?: ""),
                    "port" to port.toString(),
                ),
            )
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            TvDiagnosticsLog.log(app(), "tv", "stream", fields = mapOf("step" to "advertise-failed", "error" to errorCode.toString()))
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
    }

    private val beginLock = Any()

    @Volatile
    private var begun = false

    @Volatile
    private var startReason: String? = null

    /** The bound port, or -1 before the socket is bound. */
    @Volatile
    var port = -1
        private set

    @Volatile
    private var stopping = false

    private val running = AtomicBoolean(false)
    private val stoppedLogged = AtomicBoolean(false)
    private val startNanos = SystemClock.elapsedRealtime()
    private val framesSent = AtomicLong(0)
    private val bytesSent = AtomicLong(0)
    private val clientsConnected = AtomicLong(0)
    private val firstFrameMs = AtomicLong(-1)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var currentClient: Socket? = null

    private val clientLock = Any()
    private val chunkQueue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private var acceptThread: Thread? = null
    private var senderThread: Thread? = null
    private var nsdManager: NsdManager? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    internal companion object {
        const val TAG = "TvStreamServer"
        const val SERVICE_NAME = "DroidDeck TV"
        const val SERVICE_TYPE = "_droiddeck-tv._tcp."
        const val QUEUE_CAPACITY = 32
        const val QUEUE_POLL_MS = 50L
        const val STOP_JOIN_MS = 2_000L
    }
}

/**
 * Splits an Annex B byte stream into NAL units at the 00 00 01 / 00 00 00 01
 * start codes. Each returned unit includes its start code; a unit is only
 * emitted once the next start code is seen, so a partial unit at the end of
 * a feed stays buffered. Units are NALs, not access units: the encoder's
 * output-buffer boundaries are lost in the byte stream, and a decoder accepts
 * NAL-by-NAL feeding.
 *
 * [flush] emits the buffered trailing unit. It is only valid at access-unit
 * (chunk) boundaries, where the trailing unit is known complete: a chunk is
 * one whole MediaCodec output buffer, never a partial NAL.
 */
internal class AnnexBScanner {
    private val pending = ByteArrayOutputStream()

    fun feed(chunk: ByteArray): List<ByteArray> {
        if (chunk.isNotEmpty()) pending.write(chunk)
        val bytes = pending.toByteArray()
        val starts = findStartCodes(bytes)
        if (starts.size < 2) return emptyList()
        val out = ArrayList<ByteArray>()
        for (i in 0 until starts.size - 1) {
            val nal = bytes.copyOfRange(starts[i], starts[i + 1])
            if (nal.size > startCodeLength(bytes, starts[i])) out.add(nal)
        }
        pending.reset()
        pending.write(bytes, starts.last(), bytes.size - starts.last())
        return out
    }

    /** Emits the buffered trailing unit, if it is a complete NAL; a bare start code stays buffered. */
    fun flush(): List<ByteArray> {
        val bytes = pending.toByteArray()
        val starts = findStartCodes(bytes)
        if (starts.isEmpty()) return emptyList()
        val start = starts.first()
        val unit = bytes.copyOfRange(start, bytes.size)
        if (unit.size <= startCodeLength(bytes, start)) return emptyList()
        pending.reset()
        return listOf(unit)
    }

    private fun findStartCodes(bytes: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i + 2 < bytes.size) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) {
                if (bytes[i + 2] == 1.toByte()) {
                    out.add(i)
                    i += 3
                    continue
                }
                if (i + 3 < bytes.size && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()) {
                    out.add(i)
                    i += 4
                    continue
                }
            }
            i++
        }
        return out
    }

    private fun startCodeLength(bytes: ByteArray, pos: Int): Int {
        return if (pos + 3 < bytes.size && bytes[pos + 2] == 0.toByte()) 4 else 3
    }
}

/** The phone's Wi-Fi address, or null when Wi-Fi is not connected. */
internal fun wifiAddress(wifi: WifiManager): InetAddress? {
    val ip = runCatching { wifi.connectionInfo?.ipAddress ?: 0 }.getOrDefault(0)
    if (ip == 0) return null
    val bytes = byteArrayOf(
        (ip and 0xff).toByte(),
        (ip shr 8 and 0xff).toByte(),
        (ip shr 16 and 0xff).toByte(),
        (ip shr 24 and 0xff).toByte(),
    )
    return runCatching { InetAddress.getByAddress(bytes) }.getOrNull()
}

/** The NSD service info for the stream server: the name and type the receiver discovers, on the bound port. */
internal fun buildServiceInfo(port: Int): NsdServiceInfo = NsdServiceInfo().apply {
    serviceName = TvStreamServer.SERVICE_NAME
    serviceType = TvStreamServer.SERVICE_TYPE
    this.port = port
}

/** [frames, bytes, clients, dropped, firstFrameMs, elapsedMs]; short arrays read as zero, firstFrameMs stays -1 when no frame was sent. */
fun streamSummary(stats: LongArray): String {
    val frames = stats.getOrElse(0) { 0L }
    val bytes = stats.getOrElse(1) { 0L }
    val clients = stats.getOrElse(2) { 0L }
    val dropped = stats.getOrElse(3) { 0L }
    val firstFrameMs = stats.getOrElse(4) { -1L }
    val elapsedMs = stats.getOrElse(5) { 0L }
    return "frames=$frames bytes=$bytes clients=$clients dropped=$dropped firstFrameMs=$firstFrameMs elapsedMs=$elapsedMs"
}
