package com.droiddeck.launcher.session

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import android.view.Surface
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Receiver-side stream: discovers the controller's _droiddeck-tv service over
 * NSD (the receiver is already on the controller's network), reads length-prefixed
 * Annex B NAL units over raw TCP and decodes them to the given surface with a
 * hardware H.264 decoder. Reconnects until stopped. No PIN, no input return channel, no audio.
 */
class TvStreamReceiver(
    private val context: Context,
    private val surface: Surface,
    private val width: Int = 1920,
    private val height: Int = 1080,
    // The P2P group-owner address (authoritative); NSD is then used for the port only.
    private val hostOverride: String? = null,
    // Set for a transport with no discovery (USB cable): connect straight to this host:port.
    private val fixedEndpoint: Pair<String, Int>? = null,
    private val onStatus: ((String) -> Unit)? = null,
) {
    val framesDecoded = AtomicLong(0)
    val firstFrameMs = AtomicLong(-1)

    /** The host and stream port the last discovery resolved; the pad client uses the host. */
    @Volatile
    var resolvedEndpoint: Pair<String, Int>? = null
        private set

    fun start() {
        if (!started.compareAndSet(false, true)) return
        worker = Thread({ runLoop() }, "tv-stream-receiver").apply { start() }
    }

    fun stop() {
        destroyed.set(true)
        runCatching { currentSocket?.close() }
        runCatching { decoder?.release() }
        worker?.interrupt()
    }

    private fun runLoop() {
        while (!destroyed.get()) {
            val endpoint = fixedEndpoint ?: discover()
            if (endpoint == null) {
                sleepQuietly(RETRY_MS)
                continue
            }
            resolvedEndpoint = endpoint
            val connectStartNanos = System.nanoTime()
            val host = hostOverride ?: endpoint.first
            val socket = runCatching {
                Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(host, endpoint.second), CONNECT_TIMEOUT_MS)
                }
            }.getOrNull()
            if (socket == null) {
                onStatus?.invoke("connect to $host:${endpoint.second} failed, searching again")
                sleepQuietly(RETRY_MS)
                continue
            }
            currentSocket = socket
            onStatus?.invoke("connected to $host:${endpoint.second}")
            val dec = VideoDecoder(surface, width, height) {
                val n = framesDecoded.incrementAndGet()
                if (firstFrameMs.get() == -1L) {
                    firstFrameMs.set((System.nanoTime() - connectStartNanos) / 1_000_000)
                    onStatus?.invoke("playing, $n frames decoded")
                }
            }
            if (!dec.start()) {
                onStatus?.invoke("no H.264 decoder on this device")
                runCatching { socket.close() }
                currentSocket = null
                sleepQuietly(RETRY_MS)
                continue
            }
            decoder = dec
            val input = DataInputStream(socket.getInputStream())
            readUnits(input, { destroyed.get() || socket.isClosed }) { dec.feed(it) }
            runCatching { socket.close() }
            currentSocket = null
            runCatching { dec.release() }
            decoder = null
            if (!destroyed.get()) onStatus?.invoke("disconnected, searching again")
        }
    }

    /** Discovers one _droiddeck-tv._tcp service over NSD; null on timeout. */
    private fun discover(): Pair<String, Int>? = discoverNsdController(context, DISCOVERY_TIMEOUT_MS)

    private fun sleepQuietly(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }

    private val started = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private var worker: Thread? = null

    @Volatile
    private var currentSocket: Socket? = null

    @Volatile
    private var decoder: VideoDecoder? = null

    private companion object {
        const val DISCOVERY_TIMEOUT_MS = 10_000L
        const val CONNECT_TIMEOUT_MS = 5_000
        const val RETRY_MS = 2_000L
    }
}

/** The NSD service type the controller advertises; shared by the stream receiver and the gamepad finder. */
internal const val NSD_SERVICE_TYPE = "_droiddeck-tv._tcp"

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
            Log.w("TvStreamReceiver", "discovery failed: $errorCode")
            found.countDown()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }
    runCatching { manager.discoverServices("$NSD_SERVICE_TYPE.", NsdManager.PROTOCOL_DNS_SD, listener) }
        .onFailure {
            Log.w("TvStreamReceiver", "discoverServices failed", it)
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

/** Hardware H.264 decoder rendering to a surface; calls onFrame per released frame. */
internal class VideoDecoder(
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
    private val onFrame: () -> Unit,
) {
    private var decoder: MediaCodec? = null

    /** Creates and starts the decoder; false when this device has no AVC decoder. */
    fun start(): Boolean {
        val dec = runCatching {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(
                    MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height),
                    surface, null, 0,
                )
                start()
            }
        }.getOrNull() ?: return false
        decoder = dec
        return true
    }

    fun release() {
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        decoder = null
    }

    /** Queues one NAL unit and drains decoded frames; oversized units are skipped. */
    fun feed(unit: ByteArray) {
        val dec = decoder ?: return
        val ptsUs = System.nanoTime() / 1000
        val index = runCatching { dec.dequeueInputBuffer(INPUT_TIMEOUT_US) }.getOrDefault(-1)
        if (index >= 0) {
            val buf = dec.getInputBuffer(index)
            if (buf != null) {
                if (unit.size > buf.remaining()) {
                    Log.w(TAG, "skipping oversized unit: ${unit.size}")
                    runCatching { dec.queueInputBuffer(index, 0, 0, ptsUs, 0) }
                } else {
                    buf.clear()
                    buf.put(unit)
                    dec.queueInputBuffer(index, 0, unit.size, ptsUs, 0)
                }
            } else {
                runCatching { dec.queueInputBuffer(index, 0, 0, ptsUs, 0) }
            }
        }
        drain(dec)
    }

    private fun drain(dec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = runCatching { dec.dequeueOutputBuffer(info, 0) }.getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (index < 0) return
            runCatching { dec.releaseOutputBuffer(index, true) }
            onFrame()
        }
    }

    private companion object {
        const val TAG = "TvStreamReceiver"
        const val INPUT_TIMEOUT_US = 10_000L
    }
}

/** Reads length-prefixed NAL units until EOF, a bad length, or stop; returns units delivered. */
internal fun readUnits(input: DataInputStream, stopped: () -> Boolean, sink: (ByteArray) -> Unit): Int {
    var count = 0
    while (!stopped()) {
        val length = runCatching { input.readInt() }.getOrNull() ?: break
        if (length <= 0 || length > MAX_UNIT_BYTES) break
        val unit = ByteArray(length)
        try {
            input.readFully(unit)
        } catch (_: EOFException) {
            break
        }
        sink(unit)
        count++
    }
    return count
}

internal const val MAX_UNIT_BYTES = 16 * 1024 * 1024
