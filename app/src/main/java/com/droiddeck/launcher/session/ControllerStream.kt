package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.core.privileged.TvDiagnosticsLog

/**
 * The controller stream, prepared explicitly from the Receiver page: the Wi-Fi Direct group,
 * the stream server and the pad server, started outside any session so a receiver can discover
 * and connect before a game starts. In-memory only: a dead process leaves nothing behind, so
 * the next session starts clean. A session adds only the encoder while prepared, and stops
 * everything when it ends.
 */
object ControllerStream {
    enum class Status { IDLE, FORMING, READY, READY_NO_GROUP, FAILED }

    @Volatile
    var status: Status = Status.IDLE
        private set

    @Volatile
    var groupName: String? = null
        private set

    /** True when the preparation runs on a group already up, not one it created. */
    @Volatile
    var usedExistingGroup: Boolean = false
        private set

    @Volatile
    var failureReason: String? = null
        private set

    /** The in-memory sink feeding the running stream server; null unless prepared. */
    @Volatile
    var streamSink: ((ByteArray) -> Unit)? = null
        private set

    /**
     * Asks the running encoder for a keyframe; set by the session when it
     * creates the encoder, cleared on stop. A stale callback is harmless:
     * requesting a sync frame on a stopped encoder is a caught no-op.
     */
    @Volatile
    var requestSyncFrame: (() -> Unit)? = null

    val isPrepared: Boolean get() = status == Status.FORMING || status == Status.READY || status == Status.READY_NO_GROUP

    /** Starts the group, the stream server and the pad server; null when preparation started, else the start-failed reason. */
    fun prepare(context: Context): String? {
        synchronized(lock) {
            if (status != Status.IDLE && status != Status.FAILED) return null
            status = Status.FORMING
            groupName = null
            usedExistingGroup = false
            failureReason = null
        }
        val app = context.applicationContext
        val pad = P2pPadServer(UsbTether.USB_PAD_PORT).also { it.attach(app) }
        padServer = pad
        pad.begin()?.let { return fail(it) }
        val server = TvStreamServer(UsbTether.USB_STREAM_PORT).also { it.attach(app) }
        streamServer = server
        server.onClientConnected = { requestSyncFrame?.invoke() }
        server.begin()?.let { return fail(it) }
        // The UDP transport is best-effort: a bind failure leaves the TCP
        // transport in place, so the preparation still works.
        val udp = UdpVideoServer(UsbTether.USB_VIDEO_UDP_PORT).also { it.attach(app) }
        udp.onSubscribe = { requestSyncFrame?.invoke() }
        val udpError = udp.begin()
        if (udpError == null) {
            udpVideoServer = udp
        } else {
            TvDiagnosticsLog.log("tv", "udp-video-server", fields = mapOf("step" to "start-failed", "reason" to udpError))
            runCatching { udp.stop() }
        }
        val control = UdpControlServer(UsbTether.USB_CONTROL_PORT) { message ->
            if (message.kind == ControlMessage.KIND_RFI || message.kind == ControlMessage.KIND_IDR) {
                requestSyncFrame?.invoke()
            }
        }.also { it.attach(app) }
        val controlError = control.begin()
        if (controlError == null) {
            controlServer = control
        } else {
            TvDiagnosticsLog.log("tv", "udp-control-server", fields = mapOf("step" to "start-failed", "reason" to controlError))
            runCatching { control.stop() }
        }
        val pump = SteadyFramePump({ frame ->
            server.feed(frame.bytes)
            udpVideoServer?.feed(frame)
        })
        framePump = pump
        streamSink = pump::offer
        pump.start()
        val grp = P2pGroup(pad, server.port)
        grp.attach(app)
        grp.onState = {
            var teardown: List<SessionPart>? = null
            val current = synchronized(lock) { if (grp === group) grp.status else null }
            when (current) {
                P2pGroup.Status.READY -> synchronized(lock) {
                    if (grp === group) {
                        status = Status.READY
                        groupName = grp.groupName
                        usedExistingGroup = grp.usedExistingGroup
                    }
                }
                // The group failed, but the pad and stream servers do not need it:
                // a receiver over a USB cable reaches them directly, so they stay
                // up and the preparation reads as usable without a group.
                P2pGroup.Status.FAILED -> teardown = listOfNotNull(enterReadyNoGroup(grp, grp.failureReason))
                else -> Unit
            }
            teardown?.forEach { runCatching { it.stop() } }
        }
        group = grp
        grp.begin()?.let { reason ->
            enterReadyNoGroup(grp, reason)?.let { runCatching { it.stop() } }
            return null
        }
        // A stop tap while preparing must not leave started servers behind.
        // Only a stop sets IDLE: a group that reports READY (or falls over to
        // READY_NO_GROUP) while this runs is a live preparation, not a stop.
        synchronized(lock) {
            if (status == Status.IDLE) {
                runCatching { grp.stop() }
                runCatching { server.stop() }
                runCatching { pad.stop() }
                runCatching { udp.stop() }
                runCatching { control.stop() }
                runCatching { pump.stop() }
            }
        }
        return null
    }

    fun stop() {
        val grp: P2pGroup?
        val server: TvStreamServer?
        val pad: P2pPadServer?
        val udp: UdpVideoServer?
        val control: UdpControlServer?
        val pump: SteadyFramePump?
        synchronized(lock) {
            grp = group
            server = streamServer
            pad = padServer
            udp = udpVideoServer
            control = controlServer
            pump = framePump
            group = null
            streamServer = null
            streamSink = null
            padServer = null
            udpVideoServer = null
            controlServer = null
            framePump = null
            requestSyncFrame = null
            status = Status.IDLE
            groupName = null
            usedExistingGroup = false
            failureReason = null
        }
        runCatching { grp?.stop() }
        runCatching { server?.stop() }
        runCatching { pad?.stop() }
        runCatching { udp?.stop() }
        runCatching { control?.stop() }
        runCatching { pump?.stop() }
    }

    /**
     * Moves a failed group aside and keeps the pad and stream servers up: they
     * do not need the group, so the preparation stays usable without one.
     * Returns the group to stop, or null when it was already torn down.
     */
    private fun enterReadyNoGroup(grp: P2pGroup, reason: String?): P2pGroup? {
        val failed = synchronized(lock) {
            if (grp !== group) null else {
                val failed = group
                group = null
                groupName = null
                usedExistingGroup = false
                status = Status.READY_NO_GROUP
                failureReason = reason
                failed
            }
        }
        if (failed != null) {
            TvDiagnosticsLog.log("tv", "p2p-group", fields = mapOf("step" to "ready-no-group", "reason" to (reason ?: "")))
        }
        return failed
    }

    private fun fail(reason: String): String {
        stop()
        synchronized(lock) {
            status = Status.FAILED
            failureReason = reason
        }
        return reason
    }

    private val lock = Any()

    @Volatile
    private var group: P2pGroup? = null

    @Volatile
    private var streamServer: TvStreamServer? = null

    @Volatile
    private var padServer: P2pPadServer? = null

    @Volatile
    internal var udpVideoServer: UdpVideoServer? = null
        private set

    @Volatile
    internal var controlServer: UdpControlServer? = null
        private set

    @Volatile
    internal var framePump: SteadyFramePump? = null
        private set
}

/** Which stream parts a session start adds: the encoder only when the controller stream was prepared, the pad server + group for a second player without preparation, nothing otherwise. */
internal enum class StreamPlan { NONE, PAD_AND_GROUP, ENCODER_ONLY }

internal fun sessionStreamPlan(isPrepared: Boolean, secondPlayer: Boolean): StreamPlan = when {
    isPrepared -> StreamPlan.ENCODER_ONLY
    secondPlayer -> StreamPlan.PAD_AND_GROUP
    else -> StreamPlan.NONE
}
