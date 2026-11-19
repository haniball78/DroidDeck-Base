package com.droiddeck.launcher.session

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.droiddeck.launcher.R
import java.io.DataInputStream
import java.io.EOFException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
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
        runCatching { udpSocket?.close() }
        runCatching { decoder?.release() }
        worker?.interrupt()
    }

    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        while (!destroyed.get()) {
            val resolved = resolveEndpoint()
            if (resolved == null) {
                sleepQuietly(RETRY_MS)
                continue
            }
            val (endpoint, viaNsd) = resolved
            resolvedEndpoint = endpoint
            val host = hostOverride ?: endpoint.first
            val connectStartNanos = System.nanoTime()
            val dec = VideoDecoder(surface, width, height) {
                val n = framesDecoded.incrementAndGet()
                if (firstFrameMs.get() == -1L) {
                    firstFrameMs.set((System.nanoTime() - connectStartNanos) / 1_000_000)
                    onStatus?.invoke("playing, $n frames decoded")
                }
            }
            if (!dec.start()) {
                onStatus?.invoke("no H.264 decoder on this device")
                sleepQuietly(RETRY_MS)
                continue
            }
            decoder = dec
            val pacer = VsyncPacer(render = { dec.render(it) }, drop = { dec.drop(it) })
            dec.frameSink = pacer
            pacer.start()
            val gate = KeyframeGate()
            // UDP first; TCP is the fallback until UDP is proven on this link.
            val udpOk = runUdpSession(endpoint, host, dec, gate)
            if (!destroyed.get() && !udpOk) {
                runTcpSession(endpoint, host, dec, gate, viaNsd)
            }
            pacer.stop()
            runCatching { dec.release() }
            decoder = null
            if (!destroyed.get()) onStatus?.invoke("disconnected, searching again")
        }
    }

    /**
     * One UDP session: subscribes, reassembles frames with FEC recovery,
     * and feeds them to the decoder. False when UDP never produced a
     * frame, stalled, or lost its control channel — the caller falls back
     * to TCP. True only when the loop ended because the receiver stopped.
     */
    private fun runUdpSession(
        endpoint: Pair<String, Int>,
        host: String,
        dec: VideoDecoder,
        gate: KeyframeGate,
    ): Boolean {
        val udpPort = UdpVideo.videoPort(endpoint.second)
        val controlPort = UdpVideo.controlPort(endpoint.second)
        val control = UdpControlClient()
        if (!control.connect(host, controlPort)) return false
        val socket = runCatching { DatagramSocket() }.getOrNull() ?: run {
            control.close()
            return false
        }
        socket.soTimeout = UDP_SOCKET_TIMEOUT_MS
        udpSocket = socket
        gate.reset()
        var firstFrameAtMs = -1L
        var lastFrameAtMs = -1L
        var controlDead = false
        val assembler = UdpFrameAssembler(
            onFrame = { _, timestampMs, bytes ->
                if (firstFrameAtMs < 0) firstFrameAtMs = SystemClock.elapsedRealtime()
                lastFrameAtMs = SystemClock.elapsedRealtime()
                val scanner = AnnexBScanner()
                for (unit in scanner.feed(bytes) + scanner.flush()) {
                    gate.offer(unit) { dec.feed(it, timestampMs) }
                }
            },
            onFrameLost = { index ->
                if (!control.sendRfi(index, index)) controlDead = true
            },
        )
        val subscribe = UdpVideo.subscribeDatagram()
        val subscribedAtMs = SystemClock.elapsedRealtime()
        var lastSubscribeMs = subscribedAtMs
        sendSubscribe(socket, host, udpPort, subscribe)
        val buf = ByteArray(UdpVideo.HEADER_BYTES + UdpVideo.PAYLOAD_BYTES)
        try {
            while (!destroyed.get() && !controlDead) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastSubscribeMs >= SUBSCRIBE_INTERVAL_MS) {
                    sendSubscribe(socket, host, udpPort, subscribe)
                    lastSubscribeMs = now
                }
                val packet = DatagramPacket(buf, buf.size)
                val received = runCatching { socket.receive(packet) }.isSuccess
                if (!received) {
                    assembler.sweepStale(now)
                    if (firstFrameAtMs < 0 && now - subscribedAtMs > UDP_FIRST_FRAME_TIMEOUT_MS) return false
                    if (firstFrameAtMs >= 0 && now - lastFrameAtMs > UDP_STALL_MS) return false
                    continue
                }
                val video = UdpVideoPacket.decode(packet.data, packet.length) ?: continue
                assembler.offer(video)
            }
        } finally {
            runCatching { socket.close() }
            udpSocket = null
            control.close()
        }
        return destroyed.get() && !controlDead
    }

    private fun sendSubscribe(socket: DatagramSocket, host: String, port: Int, datagram: ByteArray) {
        runCatching {
            socket.send(DatagramPacket(datagram, datagram.size, InetSocketAddress(host, port)))
        }
    }

    /** One TCP session (the fallback): the existing length-prefixed stream, keyframe-gated. */
    private fun runTcpSession(
        endpoint: Pair<String, Int>,
        host: String,
        dec: VideoDecoder,
        gate: KeyframeGate,
        viaNsd: Boolean,
    ) {
        var failure: Throwable? = null
        val socket = runCatching {
            Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, endpoint.second), CONNECT_TIMEOUT_MS)
            }
        }.onFailure { failure = it }.getOrNull()
        if (socket == null) {
            onStatus?.invoke(context.getString(usbFailureStringId(viaNsd, failure), host, endpoint.second))
            sleepQuietly(RETRY_MS)
            return
        }
        currentSocket = socket
        onStatus?.invoke("connected to $host:${endpoint.second}")
        gate.reset()
        val input = DataInputStream(socket.getInputStream())
        readUnits(input, { destroyed.get() || socket.isClosed }) { unit ->
            gate.offer(unit) { dec.feed(it) }
        }
        runCatching { socket.close() }
        currentSocket = null
    }

    /** Discovers one _droiddeck-tv._tcp service over NSD; null on timeout. */
    private fun discover(): Pair<String, Int>? = discoverNsdController(context, DISCOVERY_TIMEOUT_MS)

    /**
     * One endpoint to try this cycle, and whether NSD resolved it. In USB
     * mode NSD comes first but only on the tether subnet: an advertisement
     * arriving over home Wi-Fi would silently switch the cable transport
     * to Wi-Fi. The fixed gateway is the fallback.
     */
    private fun resolveEndpoint(): Pair<Pair<String, Int>, Boolean>? {
        val fixed = fixedEndpoint
        if (fixed == null) {
            val found = discover() ?: return null
            return found to true
        }
        val nsd = discoverNsdController(context, USB_DISCOVERY_TIMEOUT_MS)
            ?.takeIf { UsbTether.isTetherAddress(it.first) }
        return usbEndpointCandidates(nsd, fixed).first() to (nsd != null)
    }

    private fun sleepQuietly(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }

    private val started = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private var worker: Thread? = null

    @Volatile
    private var currentSocket: Socket? = null

    @Volatile
    private var udpSocket: DatagramSocket? = null

    @Volatile
    private var decoder: VideoDecoder? = null

    private companion object {
        const val DISCOVERY_TIMEOUT_MS = 10_000L
        const val CONNECT_TIMEOUT_MS = 5_000
        const val RETRY_MS = 2_000L
        const val UDP_SOCKET_TIMEOUT_MS = 100
        const val SUBSCRIBE_INTERVAL_MS = 1_000L
        const val UDP_FIRST_FRAME_TIMEOUT_MS = 3_000L
        const val UDP_STALL_MS = 2_000L
    }
}

/** The NSD service type the controller advertises; shared by the stream receiver and the gamepad finder. */
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

/** Why a controller connection failed; drives the status line the user sees. */
internal enum class ConnectFailure { REFUSED, NO_ROUTE, TIMEOUT, OTHER }

internal fun classifyConnectFailure(error: Throwable?): ConnectFailure = when (error) {
    is NoRouteToHostException -> ConnectFailure.NO_ROUTE
    is ConnectException -> ConnectFailure.REFUSED
    is SocketTimeoutException -> ConnectFailure.TIMEOUT
    else -> ConnectFailure.OTHER
}

internal fun connectFailureStringId(error: Throwable?): Int = when (classifyConnectFailure(error)) {
    ConnectFailure.REFUSED -> R.string.receiver_connect_refused
    ConnectFailure.NO_ROUTE -> R.string.receiver_connect_no_route
    ConnectFailure.TIMEOUT -> R.string.receiver_connect_timeout
    ConnectFailure.OTHER -> R.string.receiver_connect_failed
}

/** USB-mode endpoints to try, in order: the NSD-resolved tether address first, the fixed gateway second. */
internal fun usbEndpointCandidates(nsd: Pair<String, Int>?, fixed: Pair<String, Int>): List<Pair<String, Int>> =
    if (nsd == null) listOf(fixed) else listOf(nsd, fixed).distinct()

/**
 * USB-mode failure message: a timeout with no NSD resolution means the
 * packets never reached the phone (a live link with a down server refuses
 * instead), so it reads as a dead USB link, not a slow controller.
 */
internal fun usbFailureStringId(viaNsd: Boolean, error: Throwable?): Int {
    if (!viaNsd && error is SocketTimeoutException) return R.string.receiver_connect_no_route
    return connectFailureStringId(error)
}

/**
 * Decoder format; low-latency keys only when the OS defines them and the
 * codec supports them (mirrors the encoder's API-gated buildFormat).
 * The vendor key is set in addition to the generic one: on Qualcomm,
 * Exynos and other vendors the generic key alone is a no-op.
 */
internal fun buildDecoderFormat(
    width: Int,
    height: Int,
    sdkInt: Int = Build.VERSION.SDK_INT,
    lowLatency: Boolean = false,
    vendorKey: String? = null,
): MediaFormat =
    MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
        if (sdkInt >= Build.VERSION_CODES.M) setInteger(MediaFormat.KEY_PRIORITY, 0)
        if (lowLatency && sdkInt >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        if (lowLatency && sdkInt >= Build.VERSION_CODES.R && vendorKey != null) {
            setInteger(vendorKey, 1)
        }
    }

/**
 * The vendor low-latency key for a decoder name, or null when the vendor
 * is unknown. The generic KEY_LOW_LATENCY alone does not engage the
 * low-latency path on several vendors' decoders.
 */
internal fun decoderVendorLowLatencyKey(codecName: String?): String? {
    if (codecName == null) return null
    val name = codecName.lowercase()
    return when {
        "qcom" in name || "qti" in name -> "vendor.qti-ext-dec-low-latency.enable"
        "exynos" in name -> "vendor.rtc-ext-dec-low-latency.enable"
        "hisi" in name -> "vendor.hisi-ext-dec-low-latency.enable"
        "amlogic" in name -> "vendor.low-latency.enable"
        else -> null
    }
}

/**
 * The preferred hardware AVC decoder's name when it advertises
 * low-latency decoding (API 30+); null otherwise. False on any failure.
 */
internal fun findLowLatencyDecoderName(sdkInt: Int = Build.VERSION.SDK_INT): String? {
    if (sdkInt < Build.VERSION_CODES.R) return null
    val list = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS) }.getOrNull() ?: return null
    // findDecoderForFormat returns the preferred decoder's NAME, not its info.
    val name = runCatching {
        list.findDecoderForFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080))
    }.getOrNull() ?: return null
    val info = runCatching { list.codecInfos.firstOrNull { it.name == name } }.getOrNull() ?: return null
    val supported = runCatching {
        info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
    }.getOrDefault(false)
    return if (supported) name else null
}

/**
 * True when a hardware AVC decoder on this device advertises low-latency
 * decoding (API 30+). False on any failure — the plain format is always safe.
 */
internal fun decoderSupportsLowLatency(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    findLowLatencyDecoderName(sdkInt) != null

/** Receives decoded frames; the pacer releases them to the screen at the next vsync where they are due. */
internal fun interface FrameSink {
    fun onDecoded(index: Int, timestampMs: Int)
}

/** Hardware H.264 decoder rendering to a surface; calls onFrame per displayed frame. */
internal class VideoDecoder(
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
    private val onFrame: () -> Unit,
) {
    /** When set, decoded frames go to the sink (the vsync pacer) instead of straight to the screen. */
    var frameSink: FrameSink? = null

    private var decoder: MediaCodec? = null
    private var decoderName: String? = null

    /** Creates and starts the decoder; false when this device has no AVC decoder. */
    fun start(): Boolean {
        val lowLatencyName = findLowLatencyDecoderName()
        val vendorKey = decoderVendorLowLatencyKey(lowLatencyName)
        val first = runCatching { MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull()
            ?: return false
        decoderName = runCatching { first.name }.getOrNull()
        if (tryStart(first, lowLatencyName != null, vendorKey)) {
            decoder = first
            return true
        }
        // A rejected low-latency configure can leave the codec in its error
        // state, so the plain-format retry uses a fresh decoder.
        runCatching { first.release() }
        decoderName = null
        if (lowLatencyName == null) return false
        val plain = runCatching { MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull()
            ?: return false
        decoderName = runCatching { plain.name }.getOrNull()
        if (tryStart(plain, false, null)) {
            decoder = plain
            return true
        }
        runCatching { plain.release() }
        decoderName = null
        return false
    }

    private fun tryStart(dec: MediaCodec, lowLatency: Boolean, vendorKey: String?): Boolean = runCatching {
        dec.configure(buildDecoderFormat(width, height, lowLatency = lowLatency, vendorKey = vendorKey), surface, null, 0)
        dec.start()
    }.isSuccess

    fun release() {
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        decoder = null
        decoderName = null
    }

    /** Releases a decoded frame to the screen and counts it. */
    fun render(index: Int) {
        runCatching { decoder?.releaseOutputBuffer(index, true) }
        onFrame()
    }

    /** Drops a decoded frame without showing it; the decoder keeps its reference. */
    fun drop(index: Int) {
        runCatching { decoder?.releaseOutputBuffer(index, false) }
    }

    /**
     * Queues one NAL unit and drains decoded frames. [timestampMs] is the
     * sender timestamp of the frame the unit belongs to (the receiver's
     * clock for TCP units); the sink uses it to pace the display.
     */
    fun feed(unit: ByteArray, timestampMs: Int = (SystemClock.elapsedRealtime() and 0xFFFFFFFF).toInt()) {
        val dec = decoder ?: return
        val fixed = if (isQualcommDecoder() && firstNalType(unit) == 7) {
            SpsFixup.rewriteNumRefFrames(unit)
        } else {
            unit
        }
        val ptsUs = System.nanoTime() / 1000
        val index = runCatching { dec.dequeueInputBuffer(INPUT_TIMEOUT_US) }.getOrDefault(-1)
        if (index >= 0) {
            val buf = dec.getInputBuffer(index)
            if (buf != null) {
                if (fixed.size > buf.remaining()) {
                    Log.w(TAG, "skipping oversized unit: ${fixed.size}")
                    runCatching { dec.queueInputBuffer(index, 0, 0, ptsUs, 0) }
                } else {
                    buf.clear()
                    buf.put(fixed)
                    dec.queueInputBuffer(index, 0, fixed.size, ptsUs, 0)
                }
            } else {
                runCatching { dec.queueInputBuffer(index, 0, 0, ptsUs, 0) }
            }
        }
        drain(dec, timestampMs)
    }

    private fun isQualcommDecoder(): Boolean {
        val name = decoderName?.lowercase() ?: return false
        return "qcom" in name || "qti" in name
    }

    private fun drain(dec: MediaCodec, timestampMs: Int) {
        val info = MediaCodec.BufferInfo()
        val sink = frameSink
        while (true) {
            val index = runCatching { dec.dequeueOutputBuffer(info, 0) }.getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (index < 0) return
            if (sink != null) {
                sink.onDecoded(index, timestampMs)
            } else {
                render(index)
            }
        }
    }

    private companion object {
        const val TAG = "TvStreamReceiver"
        const val INPUT_TIMEOUT_US = 10_000L
    }
}

/**
 * Drops units until the first keyframe, keeping the SPS/PPS that precede
 * it: a tablet joining mid-stream must not feed the decoder a P-frame
 * with no reference. Resets on every transport switch.
 */
internal class KeyframeGate {
    fun reset() {
        pending.clear()
        sawKeyFrame = false
    }

    fun offer(unit: ByteArray, sink: (ByteArray) -> Unit) {
        if (sawKeyFrame) {
            sink(unit)
            return
        }
        when (firstNalType(unit)) {
            5 -> {
                sawKeyFrame = true
                for (held in pending) sink(held)
                pending.clear()
                sink(unit)
            }
            7, 8 -> {
                pending.add(unit)
                if (pending.size > MAX_PENDING) pending.removeAt(0)
            }
            else -> Unit
        }
    }

    private val pending = ArrayList<ByteArray>()
    private var sawKeyFrame = false

    companion object {
        const val MAX_PENDING = 8
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
