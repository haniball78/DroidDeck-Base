package com.droiddeck.launcher.session

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gives each encoded chunk a frame index and a sender timestamp and
 * forwards it to the transports. When the encoder is idle it re-emits
 * the last keyframe, so a tablet that missed the subscribe keyframe
 * still recovers without waiting for the next one.
 *
 * It deliberately does NOT re-send P-frame bytes: a P-frame's motion
 * vectors are relative to the previous frame, so decoding the same
 * bytes twice would apply the motion twice. The tablet's display
 * repeat (the vsync pacer holding the last frame) is the correct
 * cadence mechanism for P-frames.
 */
class SteadyFramePump(
    private val onFrame: (StreamFrame) -> Unit,
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val tickIntervalMs: Long = TICK_INTERVAL_MS,
) {
    fun offer(chunk: ByteArray) {
        val now = clockMs()
        val frame = StreamFrame(nextIndex.getAndIncrement(), now, containsIdrNal(chunk), chunk)
        synchronized(lock) {
            lastFrame = frame
            lastOfferMs = now
        }
        onFrame(frame)
    }

    /**
     * One pump tick; the pump thread calls this every [tickIntervalMs].
     * Re-emits the last keyframe when the encoder has been idle for a
     * full tick, and never re-emits a P-frame.
     */
    fun tick(nowMs: Long = clockMs()) {
        val (last, idleSince) = synchronized(lock) { lastFrame to lastOfferMs }
        if (last == null || !last.isKeyFrame) return
        if (nowMs - idleSince < tickIntervalMs) return
        synchronized(lock) { lastOfferMs = nowMs }
        onFrame(StreamFrame(nextIndex.getAndIncrement(), nowMs, true, last.bytes))
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        pumpThread = Thread({
            while (running.get()) {
                runCatching { Thread.sleep(tickIntervalMs) }
                if (running.get()) tick()
            }
        }, "steady-frame-pump").apply { start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        pumpThread?.join(STOP_JOIN_MS)
        pumpThread = null
    }

    private val nextIndex = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var lastFrame: StreamFrame? = null

    @Volatile
    private var lastOfferMs: Long = 0

    private val running = AtomicBoolean(false)
    private var pumpThread: Thread? = null
    private val lock = Any()

    companion object {
        const val TICK_INTERVAL_MS = 33L
        const val STOP_JOIN_MS = 1_000L
    }
}
