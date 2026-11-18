package com.droiddeck.launcher.session

import android.content.Context
import java.io.File

/**
 * The controller stream, prepared explicitly from the Receiver page: the Wi-Fi Direct group,
 * the stream server and the pad server, started outside any session so a receiver can discover
 * and connect before a game starts. In-memory only: a dead process leaves nothing behind, so
 * the next session starts clean. A session adds only the encoder while prepared, and stops
 * everything when it ends.
 */
object ControllerStream {
    enum class Status { IDLE, FORMING, READY, FAILED }

    @Volatile
    var status: Status = Status.IDLE
        private set

    @Volatile
    var groupName: String? = null
        private set

    @Volatile
    var failureReason: String? = null
        private set

    val isPrepared: Boolean get() = status == Status.FORMING || status == Status.READY

    fun streamFile(context: Context): File = File(context.filesDir, "venc/surface.h264")

    /** Starts the group, the stream server and the pad server; null when preparation started, else the start-failed reason. */
    fun prepare(context: Context): String? {
        synchronized(lock) {
            if (status != Status.IDLE && status != Status.FAILED) return null
            status = Status.FORMING
            groupName = null
            failureReason = null
        }
        val app = context.applicationContext
        val pad = P2pPadServer(UsbTether.USB_PAD_PORT).also { it.attach(app) }
        padServer = pad
        pad.begin()?.let { return fail(it) }
        val server = TvStreamServer(streamFile(app), UsbTether.USB_STREAM_PORT).also { it.attach(app) }
        streamServer = server
        server.begin()?.let { return fail(it) }
        val grp = P2pGroup(pad, server.port)
        grp.attach(app)
        grp.onState = {
            synchronized(lock) {
                if (grp === group) {
                    when (grp.status) {
                        P2pGroup.Status.READY -> {
                            status = Status.READY
                            groupName = grp.groupName
                        }
                        P2pGroup.Status.FAILED -> {
                            status = Status.FAILED
                            failureReason = grp.failureReason
                        }
                        else -> Unit
                    }
                }
            }
        }
        group = grp
        grp.begin()?.let { return fail(it) }
        // A stop tap while preparing must not leave started servers behind.
        synchronized(lock) {
            if (status != Status.FORMING) {
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
            padServer = null
            status = Status.IDLE
            groupName = null
            failureReason = null
        }
        runCatching { grp?.stop() }
        runCatching { server?.stop() }
        runCatching { pad?.stop() }
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
}

/** Which stream parts a session start adds: the encoder only when the controller stream was prepared, the pad server + group for a second player without preparation, nothing otherwise. */
internal enum class StreamPlan { NONE, PAD_AND_GROUP, ENCODER_ONLY }

internal fun sessionStreamPlan(isPrepared: Boolean, secondPlayer: Boolean): StreamPlan = when {
    isPrepared -> StreamPlan.ENCODER_ONLY
    secondPlayer -> StreamPlan.PAD_AND_GROUP
    else -> StreamPlan.NONE
}
