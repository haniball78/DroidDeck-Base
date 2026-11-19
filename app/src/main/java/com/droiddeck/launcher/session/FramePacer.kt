package com.droiddeck.launcher.session

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import java.util.concurrent.atomic.AtomicBoolean

/** What the pacer says to do with a decoded frame at a given moment. */
sealed class PaceDecision {
    object RenderNow : PaceDecision()
    data class HoldUntil(val delayMs: Long) : PaceDecision()
    object Drop : PaceDecision()
}

/**
 * Decides when a decoded frame should reach the screen. The sender's
 * timestamp is anchored to the receiver's clock on the first frame, so
 * the two unsynchronised monotonic clocks never need to agree; a frame
 * is held up to one frame interval to absorb network jitter, and a
 * frame that arrives after a stall re-anchors the stream instead of
 * being dropped forever. Sender timestamps are 32-bit and compared by
 * Int subtraction, so the ordering survives their wraparound.
 */
class FramePacer(
    private val targetDelayMs: Long = TARGET_DELAY_MS,
    private val maxLateMs: Long = MAX_LATE_MS,
) {
    fun decide(senderTs: Int, nowMs: Long): PaceDecision {
        val last = lastDisplayedSenderTs
        if (last != null && senderTs - last <= 0) return PaceDecision.Drop
        val display = displayTimeMs(senderTs, nowMs)
        return when {
            nowMs >= display + maxLateMs -> {
                anchorSenderTs = senderTs
                anchorReceiverMs = nowMs
                PaceDecision.RenderNow
            }
            nowMs >= display -> PaceDecision.RenderNow
            else -> PaceDecision.HoldUntil(display - nowMs)
        }
    }

    fun markDisplayed(senderTs: Int) {
        lastDisplayedSenderTs = senderTs
    }

    private fun displayTimeMs(senderTs: Int, nowMs: Long): Long {
        val anchor = anchorSenderTs
        if (anchor == null) {
            anchorSenderTs = senderTs
            anchorReceiverMs = nowMs
            return nowMs + targetDelayMs
        }
        val delta = (senderTs - anchor).toLong()
        if (delta < 0) return nowMs + targetDelayMs
        val display = anchorReceiverMs + delta + targetDelayMs
        if (display - nowMs > REANCHOR_AHEAD_MS) {
            anchorSenderTs = senderTs
            anchorReceiverMs = nowMs
            return nowMs + targetDelayMs
        }
        return display
    }

    private var anchorSenderTs: Int? = null
    private var anchorReceiverMs: Long = 0
    private var lastDisplayedSenderTs: Int? = null

    companion object {
        const val TARGET_DELAY_MS = 33L
        const val MAX_LATE_MS = 33L
        const val REANCHOR_AHEAD_MS = 132L
    }
}

/**
 * Holds at most one decoded frame and releases it at the next vsync
 * where the pacer says it is due; a newer frame replaces a still-held
 * one (drop-oldest), and a missed frame means the previous frame simply
 * stays on screen one more vsync. The Choreographer callback runs on
 * the main looper; offers come from the decode thread.
 */
class VsyncPacer(
    private val pacer: FramePacer = FramePacer(),
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val render: (index: Int) -> Unit,
    private val drop: (index: Int) -> Unit,
    mainHandler: Handler = Handler(Looper.getMainLooper()),
) : Choreographer.FrameCallback, FrameSink {
    private val handler = mainHandler
    private val running = AtomicBoolean(false)
    private var framePending = false

    @Volatile
    private var pendingIndex: Int? = null

    @Volatile
    private var pendingSenderTs: Int? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        schedule()
    }

    fun stop() {
        running.set(false)
        if (framePending) {
            framePending = false
            handler.post { runCatching { Choreographer.getInstance().removeFrameCallback(this) } }
        }
        val old = takePending()
        if (old != null) drop(old.first)
    }

    /** Offers a decoded frame; the pacer decides whether it is shown, held, or dropped. */
    override fun onDecoded(index: Int, timestampMs: Int) {
        offer(index, timestampMs)
    }

    fun offer(index: Int, senderTs: Int) {
        val now = nowMs()
        when (pacer.decide(senderTs, now)) {
            is PaceDecision.RenderNow -> {
                val old = takePending()
                if (old != null) drop(old.first)
                pacer.markDisplayed(senderTs)
                render(index)
            }
            is PaceDecision.Drop -> drop(index)
            is PaceDecision.HoldUntil -> {
                var dropOffered = false
                var dropPending: Int? = null
                synchronized(lock) {
                    val pendingTs = pendingSenderTs
                    if (pendingTs != null && senderTs - pendingTs <= 0) {
                        // Older than the frame already held: drop the offered one.
                        dropOffered = true
                    } else {
                        dropPending = pendingIndex
                        pendingIndex = index
                        pendingSenderTs = senderTs
                    }
                }
                if (dropPending != null) drop(dropPending!!)
                if (dropOffered) drop(index)
            }
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        framePending = false
        if (!running.get()) return
        val now = nowMs()
        val taken = takePending()
        if (taken != null) {
            val (index, senderTs) = taken
            when (pacer.decide(senderTs, now)) {
                is PaceDecision.RenderNow -> {
                    pacer.markDisplayed(senderTs)
                    render(index)
                }
                is PaceDecision.Drop -> drop(index)
                is PaceDecision.HoldUntil -> {
                    val replaced = synchronized(lock) {
                        if (pendingIndex == null) {
                            pendingIndex = index
                            pendingSenderTs = senderTs
                            null
                        } else {
                            pendingIndex
                        }
                    }
                    if (replaced != null) drop(index)
                }
            }
        }
        if (running.get()) schedule()
    }

    private fun takePending(): Pair<Int, Int>? = synchronized(lock) {
        val index = pendingIndex
        val senderTs = pendingSenderTs
        pendingIndex = null
        pendingSenderTs = null
        if (index != null && senderTs != null) index to senderTs else null
    }

    private fun schedule() {
        if (framePending) return
        framePending = true
        handler.post { Choreographer.getInstance().postFrameCallback(this) }
    }

    private val lock = Any()
}
