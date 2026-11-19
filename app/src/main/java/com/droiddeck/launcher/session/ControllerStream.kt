package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.R
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
    enum class Status { IDLE, FORMING, READY, READY_NO_GROUP, WAITING_USB, FAILED }

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

    val isPrepared: Boolean get() = status == Status.FORMING || status == Status.READY || status == Status.READY_NO_GROUP || status == Status.WAITING_USB

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
        streamSink = server::feed
        server.begin()?.let { return fail(it) }
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
                P2pGroup.Status.FAILED -> teardown = listOfNotNull(onGroupFailed(grp, grp.failureReason, app))
                else -> Unit
            }
            teardown?.forEach { runCatching { it.stop() } }
        }
        group = grp
        grp.begin()?.let { reason ->
            onGroupFailed(grp, reason, app)?.let { runCatching { it.stop() } }
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
            }
        }
        return null
    }

    fun stop() {
        val grp: P2pGroup?
        val server: TvStreamServer?
        val pad: P2pPadServer?
        synchronized(lock) {
            grp = group
            server = streamServer
            pad = padServer
            group = null
            streamServer = null
            streamSink = null
            padServer = null
            status = Status.IDLE
            groupName = null
            usedExistingGroup = false
            failureReason = null
        }
        runCatching { grp?.stop() }
        runCatching { server?.stop() }
        runCatching { pad?.stop() }
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

    /**
     * Handles a failed group: when USB tethering is on, the cable link may
     * still be coming up, so the preparation waits for it instead of claiming
     * ready on a dead link; otherwise it falls over to ready without a group.
     * Returns the group to stop, or null when it was already torn down.
     */
    private fun onGroupFailed(grp: P2pGroup, reason: String?, app: Context): P2pGroup? {
        if (UsbTether.systemGateway() == null) return enterReadyNoGroup(grp, reason)
        if (UsbTether.linkUpNow()) return enterReadyNoGroup(grp, reason)
        val waiting = synchronized(lock) {
            if (grp !== group || status != Status.FORMING) null else {
                status = Status.WAITING_USB
                failureReason = reason
                group
            }
        } ?: return null
        Thread {
            val up = UsbTether.waitForUsbLink(
                keepWaiting = { synchronized(lock) { status == Status.WAITING_USB && grp === group } },
            )
            synchronized(lock) {
                if (status != Status.WAITING_USB || grp !== group) return@Thread
            }
            if (up) {
                enterReadyNoGroup(grp, reason)?.let { runCatching { it.stop() } }
            } else {
                TvDiagnosticsLog.log("tv", "p2p-group", fields = mapOf("step" to "usb-link-down", "reason" to (reason ?: "")))
                fail(app.getString(R.string.receiver_prepare_usb_link_down))
            }
        }.start()
        return waiting
    }

    private fun fail(reason: String): String {
        synchronized(lock) {
            // A stop tap that landed first wins: stay idle. Checked before
            // stop(), which itself resets the status to idle.
            if (status == Status.IDLE) return reason
        }
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
}

/** Which stream parts a session start adds: the encoder only when the controller stream was prepared, the pad server + group for a second player without preparation, nothing otherwise. */
internal enum class StreamPlan { NONE, PAD_AND_GROUP, ENCODER_ONLY }

internal fun sessionStreamPlan(isPrepared: Boolean, secondPlayer: Boolean): StreamPlan = when {
    isPrepared -> StreamPlan.ENCODER_ONLY
    secondPlayer -> StreamPlan.PAD_AND_GROUP
    else -> StreamPlan.NONE
}
